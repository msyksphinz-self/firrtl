// See LICENSE for license details.

package firrtl

import java.io.Writer

import scala.collection.mutable

import firrtl.ir._
import firrtl.passes._
import firrtl.transforms._
import firrtl.annotations._
import firrtl.traversals.Foreachers._
import firrtl.PrimOps._
import firrtl.WrappedExpression._
import Utils._
import MemPortUtils.{memPortField, memType}
import firrtl.options.{HasShellOptions, ShellOption, StageUtils, PhaseException}
import firrtl.stage.RunFirrtlTransformAnnotation
// Datastructures
import scala.collection.mutable.ArrayBuffer

class SystemVerilogEmitter extends VerilogEmitter with Emitter {

  class SystemVerilogRender(description: Description,
                      portDescriptions: Map[String, Description],
                      m: Module,
                      moduleMap: Map[String, DefModule])(implicit writer: Writer) {

    def this(m: Module, moduleMap: Map[String, DefModule])(implicit writer: Writer) {
      this(EmptyDescription, Map.empty, m, moduleMap)(writer)
    }

    val netlist = mutable.LinkedHashMap[WrappedExpression, Expression]()
    val namespace = Namespace(m)
    namespace.newName("_RAND") // Start rand names at _RAND_0
    def build_netlist(s: Statement): Unit = {
      s.foreach(build_netlist)
      s match {
        case sx: Connect => netlist(sx.loc) = sx.expr
        case sx: IsInvalid => error("Should have removed these!")
        case sx: DefNode =>
          val e = WRef(sx.name, sx.value.tpe, NodeKind, SourceFlow)
          netlist(e) = sx.value
        case _ =>
      }
    }

    val portdefs = ArrayBuffer[Seq[Any]]()
    val declares = ArrayBuffer[Seq[Any]]()
    val instdeclares = ArrayBuffer[Seq[Any]]()
    val assigns = ArrayBuffer[Seq[Any]]()
    val attachSynAssigns = ArrayBuffer.empty[Seq[Any]]
    val attachAliases = ArrayBuffer.empty[Seq[Any]]
    // No (aka synchronous) always blocks, keyed by clock
    val noResetAlwaysBlocks = mutable.LinkedHashMap[Expression, ArrayBuffer[Seq[Any]]]()
    // One always block per async reset register, (Clock, Reset, Content)
    // An alternative approach is to have one always block per combination of clock and async reset,
    // but Formality doesn't allow more than 1 statement inside async reset always blocks
    val asyncResetAlwaysBlocks  = mutable.ArrayBuffer[(Expression, Expression, Seq[Any])]()
    val asyncResetNAlwaysBlocks = mutable.ArrayBuffer[(Expression, Expression, Seq[Any])]()
    // Used to determine type of initvar for initializing memories
    var maxMemSize: BigInt = BigInt(0)
    val initials = ArrayBuffer[Seq[Any]]()
    // In Verilog, async reset registers are expressed using always blocks of the form:
    // always @(posedge clock or posedge reset) begin
    //   if (reset) ...
    // There is a fundamental mismatch between this representation which treats async reset
    // registers as edge-triggered when in reality they are level-triggered.
    // This can result in silicon-simulation mismatch in the case where async reset is held high
    // upon power on with no clock, then async reset is dropped before the clock starts. In this
    // circumstance, the async reset register will be randomized in simulation instead of being
    // reset. To fix this, we need extra initial block logic for async reset registers
    val asyncInitials = ArrayBuffer[Seq[Any]]()
    val simulates = ArrayBuffer[Seq[Any]]()

    def bigIntToVLit(bi: BigInt): String =
      if (bi.isValidInt) bi.toString else s"${bi.bitLength}'d$bi"

    def declareVectorType(b: String, n: String, tpe: Type, size: BigInt, info: Info) = {
      declares += Seq(b, " ", tpe, " ", n, " [0:", bigIntToVLit(size - 1), "];", info)
    }

    def declare(b: String, n: String, t: Type, info: Info) = t match {
      case tx: VectorType =>
        declareVectorType(b, n, tx.tpe, tx.size, info)
      case tx =>
        declares += Seq(b, " ", tx, " ", n,";",info)
    }

    def assign(e: Expression, value: Expression, info: Info): Unit = {
      assigns += Seq("assign ", e, " = ", value, ";", info)
    }

    // In simulation, assign garbage under a predicate
    def garbageAssign(e: Expression, syn: Expression, garbageCond: Expression, info: Info) = {
      assigns += Seq("`ifndef RANDOMIZE_GARBAGE_ASSIGN")
      assigns += Seq("assign ", e, " = ", syn, ";", info)
      assigns += Seq("`else")
      assigns += Seq("assign ", e, " = ", garbageCond, " ? ", rand_string(syn.tpe), " : ", syn,
                     ";", info)
      assigns += Seq("`endif // RANDOMIZE_GARBAGE_ASSIGN")
    }

    def invalidAssign(e: Expression) = {
      assigns += Seq("`ifdef RANDOMIZE_INVALID_ASSIGN")
      assigns += Seq("assign ", e, " = ", rand_string(e.tpe), ";")
      assigns += Seq("`endif // RANDOMIZE_INVALID_ASSIGN")
    }

    def regUpdate(r: Expression, clk: Expression, reset: Expression, init: Expression) = {
      def addUpdate(expr: Expression, tabs: String): Seq[Seq[Any]] = expr match {
        case m: Mux =>
          if (m.tpe == ClockType) throw EmitterException("Cannot emit clock muxes directly")
          if (m.tpe == AsyncResetType) throw EmitterException("Cannot emit async reset muxes directly")
          if (m.tpe == AsyncResetNType) throw EmitterException("Cannot emit async reset N muxes directly")

          lazy val _if     = Seq(tabs, "if (", m.cond, ") begin")
          lazy val _else   = Seq(tabs, "end else begin")
          lazy val _ifNot  = Seq(tabs, "if (!(", m.cond, ")) begin")
          lazy val _end    = Seq(tabs, "end")
          lazy val _true   = addUpdate(m.tval, tabs + tab)
          lazy val _false  = addUpdate(m.fval, tabs + tab)
          lazy val _elseIfFalse = {
            val _falsex = addUpdate(m.fval, tabs) // _false, but without an additional tab
            Seq(tabs, "end else ", _falsex.head.tail) +: _falsex.tail
          }

          /* For a Mux assignment, there are five possibilities:
           *   1. Both the true and false condition are self-assignments; do nothing
           *   2. The true condition is a self-assignment; invert the false condition and use that only
           *   3. The false condition is a self-assignment; skip the false condition
           *   4. The false condition is a Mux; use the true condition and use 'else if' for the false condition
           *   5. Default; use both the true and false conditions
           */
          (m.tval, m.fval) match {
            case (t, f) if weq(t, r) && weq(f, r) => Nil
            case (t, _) if weq(t, r)              =>  _ifNot +: _false                           :+ _end
            case (_, f) if weq(f, r)              =>  _if    +: _true                            :+ _end
            case (_, _: Mux)                      => (_if    +: _true) ++ _elseIfFalse
            case _                                => (_if    +: _true  :+ _else)       ++ _false :+ _end
          }
        case e => Seq(Seq(tabs, r, " <= ", e, ";"))
      }
      if (weq(init, r)) { // Synchronous Reset
        noResetAlwaysBlocks.getOrElseUpdate(clk, ArrayBuffer[Seq[Any]]()) ++= addUpdate(netlist(r), "")
      } else { // Asynchronous Reset
        assert(reset.tpe == AsyncResetType || reset.tpe == AsyncResetNType, "Error! Synchronous reset should have been removed!")
        val tv = init
        val fv = netlist(r)
        if (reset.tpe == AsyncResetType) {
          asyncResetAlwaysBlocks  += ((clk, reset, addUpdate(Mux(reset, tv, fv, mux_type_and_widths(tv, fv)), "")))
        } else {
          asyncResetNAlwaysBlocks += ((clk, reset, addUpdate(Mux(DoPrim(Not, Seq(reset), Nil, BoolType), tv, fv, mux_type_and_widths(tv, fv)), "")))
        }
      }
    }

    def update(e: Expression, value: Expression, clk: Expression, en: Expression, info: Info) = {
      val lines = noResetAlwaysBlocks.getOrElseUpdate(clk, ArrayBuffer[Seq[Any]]())
      if (weq(en, one)) lines += Seq(e, " <= ", value, ";")
      else {
        lines += Seq("if(", en, ") begin")
        lines += Seq(tab, e, " <= ", value, ";", info)
        lines += Seq("end")
      }
    }

    // Declares an intermediate wire to hold a large enough random number.
    // Then, return the correct number of bits selected from the random value
    def rand_string(t: Type): Seq[Any] = {
      val nx = namespace.newName("_RAND")
      val rand = VRandom(bitWidth(t))
      val tx = SIntType(IntWidth(rand.realWidth))
      declare("logic", nx, tx, NoInfo)
      initials += Seq(wref(nx, tx), " = ", VRandom(bitWidth(t)), ";")
      Seq(nx, "[", bitWidth(t) - 1, ":0]")
    }

    def initialize(e: Expression, reset: Expression, init: Expression) = {
      initials += Seq("`ifdef RANDOMIZE_REG_INIT")
      initials += Seq(e, " = ", rand_string(e.tpe), ";")
      initials += Seq("`endif // RANDOMIZE_REG_INIT")
      reset.tpe match {
        case AsyncResetType =>
          asyncInitials += Seq("if (", reset, ") begin")
          asyncInitials += Seq(tab, e, " = ", init, ";")
          asyncInitials += Seq("end")
        case AsyncResetNType =>
          asyncInitials += Seq("if (!", reset, ") begin")
          asyncInitials += Seq(tab, e, " = ", init, ";")
          asyncInitials += Seq("end")
        case _ => // do nothing
      }
    }

    def initialize_mem(s: DefMemory): Unit = {
      if (s.depth > maxMemSize) {
        maxMemSize = s.depth
      }
      val index = wref("initvar", s.dataType)
      val rstring = rand_string(s.dataType)
      initials += Seq("`ifdef RANDOMIZE_MEM_INIT")
      initials += Seq("for (initvar = 0; initvar < ", bigIntToVLit(s.depth), "; initvar = initvar+1)")
      initials += Seq(tab, WSubAccess(wref(s.name, s.dataType), index, s.dataType, SinkFlow),
                      " = ", rstring, ";")
      initials += Seq("`endif // RANDOMIZE_MEM_INIT")
    }

    def simulate(clk: Expression, en: Expression, s: Seq[Any], cond: Option[String], info: Info) = {
      val lines = noResetAlwaysBlocks.getOrElseUpdate(clk, ArrayBuffer[Seq[Any]]())
      lines += Seq("`ifndef SYNTHESIS")
      if (cond.nonEmpty) {
        lines += Seq(s"`ifdef ${cond.get}")
        lines += Seq(tab, s"if (`${cond.get}) begin")
        lines += Seq("`endif")
      }
      lines += Seq(tab, tab, "if (", en, ") begin")
      lines += Seq(tab, tab, tab, s, info)
      lines += Seq(tab, tab, "end")
      if (cond.nonEmpty) {
        lines += Seq(s"`ifdef ${cond.get}")
        lines += Seq(tab, "end")
        lines += Seq("`endif")
      }
      lines += Seq("`endif // SYNTHESIS")
    }

    def stop(ret: Int): Seq[Any] = Seq(if (ret == 0) "$finish;" else "$fatal;")

    def printf(str: StringLit, args: Seq[Expression]): Seq[Any] = {
      val strx = str.verilogEscape +: args.flatMap(Seq(",", _))
      Seq("$fwrite(32'h80000002,", strx, ");")
    }

    // turn strings into Seq[String] verilog comments
    def build_comment(desc: String): Seq[Seq[String]] = {
      val lines = desc.split("\n").toSeq

      if (lines.size > 1) {
        val lineSeqs = lines.tail.map {
          case "" => Seq(" *")
          case nonEmpty => Seq(" * ", nonEmpty)
        }
        Seq("/* ", lines.head) +: lineSeqs :+ Seq(" */")
      } else {
        Seq(Seq("// ", lines(0)))
      }
    }

    // Turn ports into Seq[String] and add to portdefs
    def build_ports(): Unit = {
      def padToMax(strs: Seq[String]): Seq[String] = {
        val len = if (strs.nonEmpty) strs.map(_.length).max else 0
        strs map (_.padTo(len, ' '))
      }

      // Turn directions into strings (and AnalogType into inout)
      val dirs = m.ports map { case Port(_, name, dir, tpe) =>
        (dir, tpe) match {
          case (_, AnalogType(_)) => "inout " // padded to length of output
          case (Input, _) => "input logic "
          case (Output, _) => "output logic"
        }
      }
      // Turn types into strings, all ports must be GroundTypes
      val tpes = m.ports map {
        case Port(_, _, _, tpe: GroundType) => stringify(tpe)
        case Port(_, _, _, tpe: VectorType) => stringify(tpe)
        case port: Port => error(s"Trying to emit non-GroundType Port $port")
      }

      // dirs are already padded
      (dirs, padToMax(tpes), m.ports).zipped.toSeq.zipWithIndex.foreach {
        case ((dir, tpe, Port(info, name, _, _)), i) =>
          portDescriptions.get(name) match {
            case Some(DocString(s)) =>
              portdefs += Seq("")
              portdefs ++= build_comment(s.string)
            case other =>
          }

          if (i != m.ports.size - 1) {
            portdefs += Seq(dir, " ", tpe, " ", name, ",", info)
          } else {
            portdefs += Seq(dir, " ", tpe, " ", name, info)
          }
      }
    }
    def build_streams(s: Statement): Unit = {
      val withoutDescription = s match {
        case DescribedStmt(DocString(desc), stmt) =>
          val comment = Seq("") +: build_comment(desc.string)
          stmt match {
            case sx: IsDeclaration =>
              declares ++= comment
            case sx =>
          }
          stmt
        case DescribedStmt(EmptyDescription, stmt) => stmt
        case other => other
      }
      withoutDescription.foreach(build_streams)
      withoutDescription match {
        case sx@Connect(info, loc@WRef(_, _, PortKind | WireKind | InstanceKind, _), expr) =>
          assign(loc, expr, info)
        case sx: DefWire =>
          declare("logic", sx.name, sx.tpe, sx.info)
        case sx: DefRegister =>
          declare("logic", sx.name, sx.tpe, sx.info)
          val e = wref(sx.name, sx.tpe)
          regUpdate(e, sx.clock, sx.reset, sx.init)
          initialize(e, sx.reset, sx.init)
        case sx: DefNode =>
          declare("logic", sx.name, sx.value.tpe, sx.info)
          assign(WRef(sx.name, sx.value.tpe, NodeKind, SourceFlow), sx.value, sx.info)
        case sx: Stop =>
          simulate(sx.clk, sx.en, stop(sx.ret), Some("STOP_COND"), sx.info)
        case sx: Print =>
          simulate(sx.clk, sx.en, printf(sx.string, sx.args), Some("PRINTF_COND"), sx.info)
        // If we are emitting an Attach, it must not have been removable in VerilogPrep
        case sx: Attach =>
          // For Synthesis
          // Note that this is quadratic in the number of things attached
          for (set <- sx.exprs.toSet.subsets(2)) {
            val (a, b) = set.toSeq match {
              case Seq(x, y) => (x, y)
            }
            // Synthesizable ones as well
            attachSynAssigns += Seq("assign ", a, " = ", b, ";", sx.info)
            attachSynAssigns += Seq("assign ", b, " = ", a, ";", sx.info)
          }
          // alias implementation for everything else
          attachAliases += Seq("alias ", sx.exprs.flatMap(e => Seq(e, " = ")).init, ";", sx.info)
        case sx: WDefInstanceConnector =>
          val (module, params) = moduleMap(sx.module) match {
            case DescribedMod(_, _, ExtModule(_, _, _, extname, params)) => (extname, params)
            case DescribedMod(_, _, Module(_, name, _, _)) => (name, Seq.empty)
            case ExtModule(_, _, _, extname, params) => (extname, params)
            case Module(_, name, _, _) => (name, Seq.empty)
          }
          val ps = if (params.nonEmpty) params map stringify mkString("#(", ", ", ") ") else ""
          instdeclares += Seq(module, " ", ps, sx.name, " (", sx.info)
          for (((port, ref), i) <- sx.portCons.zipWithIndex) {
            val line = Seq(tab, ".", remove_root(port), "(", ref, ")")
            if (i != sx.portCons.size - 1) instdeclares += Seq(line, ",")
            else instdeclares += line
          }
          instdeclares += Seq(");")
        case sx: DefMemory =>
          val fullSize = sx.depth * (sx.dataType match {
                                       case GroundType(IntWidth(width)) => width
                                     })
          val decl = if (fullSize > (1 << 29)) "reg /* sparse */" else "logic"
          declareVectorType(decl, sx.name, sx.dataType, sx.depth, sx.info)
          initialize_mem(sx)
          if (sx.readLatency != 0 || sx.writeLatency != 1)
            throw EmitterException("All memories should be transformed into " +
                                     "blackboxes or combinational by previous passses")
          for (r <- sx.readers) {
            val data = memPortField(sx, r, "data")
            val addr = memPortField(sx, r, "addr")
            // Ports should share an always@posedge, so can't have intermediary wire

            declare("logic", LowerTypes.loweredName(data), data.tpe, sx.info)
            declare("logic", LowerTypes.loweredName(addr), addr.tpe, sx.info)
            // declare("logic", LowerTypes.loweredName(en), en.tpe)

            //; Read port
            assign(addr, netlist(addr), NoInfo) // Info should come from addr connection
                                                // assign(en, netlist(en))     //;Connects value to m.r.en
            val mem = WRef(sx.name, memType(sx), MemKind, UnknownFlow)
            val memPort = WSubAccess(mem, addr, sx.dataType, UnknownFlow)
            val depthValue = UIntLiteral(sx.depth, IntWidth(sx.depth.bitLength))
            val garbageGuard = DoPrim(Geq, Seq(addr, depthValue), Seq(), UnknownType)

            if ((sx.depth & (sx.depth - 1)) == 0)
              assign(data, memPort, sx.info)
            else
              garbageAssign(data, memPort, garbageGuard, sx.info)
          }

          for (w <- sx.writers) {
            val data = memPortField(sx, w, "data")
            val addr = memPortField(sx, w, "addr")
            val mask = memPortField(sx, w, "mask")
            val en = memPortField(sx, w, "en")
            //Ports should share an always@posedge, so can't have intermediary wire
            val clk = netlist(memPortField(sx, w, "clk"))

            declare("logic", LowerTypes.loweredName(data), data.tpe, sx.info)
            declare("logic", LowerTypes.loweredName(addr), addr.tpe, sx.info)
            declare("logic", LowerTypes.loweredName(mask), mask.tpe, sx.info)
            declare("logic", LowerTypes.loweredName(en), en.tpe, sx.info)

            // Write port
            // Info should come from netlist
            assign(data, netlist(data), NoInfo)
            assign(addr, netlist(addr), NoInfo)
            assign(mask, netlist(mask), NoInfo)
            assign(en, netlist(en), NoInfo)

            val mem = WRef(sx.name, memType(sx), MemKind, UnknownFlow)
            val memPort = WSubAccess(mem, addr, sx.dataType, UnknownFlow)
            update(memPort, data, clk, AND(en, mask), sx.info)
          }

          if (sx.readwriters.nonEmpty)
            throw EmitterException("All readwrite ports should be transformed into " +
                                     "read & write ports by previous passes")
        case _ =>
      }
    }

    def emit_streams(): Unit = {
      description match {
        case DocString(s) => build_comment(s.string).foreach(emit(_))
        case other =>
      }
      emit(Seq("module ", m.name, "(", m.info))
      for (x <- portdefs) emit(Seq(tab, x))
      emit(Seq(");"))

      if (declares.isEmpty && assigns.isEmpty) emit(Seq(tab, "initial begin end"))
      for (x <- declares) emit(Seq(tab, x))
      for (x <- instdeclares) emit(Seq(tab, x))
      for (x <- assigns) emit(Seq(tab, x))
      if (attachAliases.nonEmpty) {
        emit(Seq("`ifdef SYNTHESIS"))
        for (x <- attachSynAssigns) emit(Seq(tab, x))
        emit(Seq("`elsif verilator"))
        emit(Seq(tab, "`error \"Verilator does not support alias and thus cannot arbirarily connect bidirectional wires and ports\""))
        emit(Seq("`else"))
        for (x <- attachAliases) emit(Seq(tab, x))
        emit(Seq("`endif"))
      }
      if (initials.nonEmpty) {
        emit(Seq("`ifdef RANDOMIZE_GARBAGE_ASSIGN"))
        emit(Seq("`define RANDOMIZE"))
        emit(Seq("`endif"))
        emit(Seq("`ifdef RANDOMIZE_INVALID_ASSIGN"))
        emit(Seq("`define RANDOMIZE"))
        emit(Seq("`endif"))
        emit(Seq("`ifdef RANDOMIZE_REG_INIT"))
        emit(Seq("`define RANDOMIZE"))
        emit(Seq("`endif"))
        emit(Seq("`ifdef RANDOMIZE_MEM_INIT"))
        emit(Seq("`define RANDOMIZE"))
        emit(Seq("`endif"))
        emit(Seq("`ifndef RANDOM"))
        emit(Seq("`define RANDOM $random"))
        emit(Seq("`endif"))
        emit(Seq("`ifdef RANDOMIZE_MEM_INIT"))
        // Since simulators don't actually support memories larger than 2^31 - 1, there is no reason
        // to change Verilog emission in the common case. Instead, we only emit a larger initvar
        // where necessary
        if (maxMemSize.isValidInt) {
          emit(Seq("  integer initvar;"))
        } else {
          // Width must be able to represent maxMemSize because that's the upper bound in init loop
          val width = maxMemSize.bitLength - 1 // minus one because [width-1:0] has a width of "width"
          emit(Seq(s"  reg [$width:0] initvar;"))
        }
        emit(Seq("`endif"))
        emit(Seq("initial begin"))
        emit(Seq("  `ifdef RANDOMIZE"))
        emit(Seq("    `ifdef INIT_RANDOM"))
        emit(Seq("      `INIT_RANDOM"))
        emit(Seq("    `endif"))
        // This enables testbenches to seed the random values at some time
        // before `RANDOMIZE_DELAY (or the legacy value 0.002 if
        // `RANDOMIZE_DELAY is not defined).
        // Verilator does not support delay statements, so they are omitted.
        emit(Seq("    `ifndef VERILATOR"))
        emit(Seq("      `ifdef RANDOMIZE_DELAY"))
        emit(Seq("        #`RANDOMIZE_DELAY begin end"))
        emit(Seq("      `else"))
        emit(Seq("        #0.002 begin end"))
        emit(Seq("      `endif"))
        emit(Seq("    `endif"))
        for (x <- initials) emit(Seq(tab, x))
        emit(Seq("  `endif // RANDOMIZE"))
        for (x <- asyncInitials) emit(Seq(tab, x))
        emit(Seq("end"))
      }

      for ((clk, content) <- noResetAlwaysBlocks if content.nonEmpty) {
        emit(Seq(tab, "always_ff @(posedge ", clk, ") begin"))
        for (line <- content) emit(Seq(tab, tab, line))
        emit(Seq(tab, "end"))
      }

      for ((clk, reset, content) <- asyncResetAlwaysBlocks if content.nonEmpty) {
        emit(Seq(tab, "always_ff @(posedge ", clk, ", posedge ", reset, ") begin"))
        for (line <- content) emit(Seq(tab, tab, line))
        emit(Seq(tab, "end"))
      }

      for ((clk, reset, content) <- asyncResetNAlwaysBlocks if content.nonEmpty) {
        emit(Seq(tab, "always_ff @(posedge ", clk, ", negedge ", reset, ") begin"))
        for (line <- content) emit(Seq(tab, tab, line))
        emit(Seq(tab, "end"))
      }

      emit(Seq("endmodule"))
    }

    /**
      * The standard verilog emitter, wraps up everything into the
      * verilog
      * @return
      */
    def emit_systemverilog(): DefModule = {

      build_netlist(m.body)
      build_ports()
      build_streams(m.body)
      emit_streams()
      m
    }

    /**
      * This emits a verilog module that can be bound to a module defined in chisel.
      * It uses the same machinery as the general emitter in order to insure that
      * parameters signature is exactly the same as the module being bound to
      * @param overrideName Override the module name
      * @param body the body of the bind module
      * @return A module constructed from the body
      */
    def emitVerilogBind(overrideName: String, body: String): DefModule = {
      build_netlist(m.body)
      build_ports()

      description match {
        case DocString(s) => build_comment(s.string).foreach(emit(_))
        case other =>
      }

      emit(Seq("module ", overrideName, "(", m.info))
      for (x <- portdefs) emit(Seq(tab, x))

      emit(Seq(");"))
      emit(body)
      emit(Seq("endmodule"), top = 0)
      m
    }
  }

  override def emit(state: CircuitState, writer: Writer): Unit = {
    val circuit = runTransforms(state).circuit
    val moduleMap = circuit.modules.map(m => m.name -> m).toMap
    circuit.modules.foreach {
      case dm @ DescribedMod(d, pds, m: Module) =>
        val renderer = new SystemVerilogRender(d, pds, m, moduleMap)(writer)
        renderer.emit_systemverilog()
      case m: Module =>
        val renderer = new SystemVerilogRender(m, moduleMap)(writer)
        renderer.emit_systemverilog()
      case _ => // do nothing
    }
  }

  override val outputSuffix: String = ".sv"
}
