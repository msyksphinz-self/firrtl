// See LICENSE for license details.

package firrtl.passes

import scala.collection.mutable
import firrtl._
import firrtl.ir._
import firrtl.Utils._
import MemPortUtils.memType
import firrtl.Mappers._

/** Removes all aggregate types from a [[firrtl.ir.Circuit]]
  *
  * @note Assumes [[firrtl.ir.SubAccess]]es have been removed
  * @note Assumes [[firrtl.ir.Connect]]s and [[firrtl.ir.IsInvalid]]s only operate on [[firrtl.ir.Expression]]s of ground type
  * @example
  * {{{
  *   wire foo : { a : UInt<32>, b : UInt<16> }
  * }}} lowers to
  * {{{
  *   wire foo_a : UInt<32>
  *   wire foo_b : UInt<16>
  * }}}
  */
object LowerTypes extends Transform {
  def inputForm = UnknownForm
  def outputForm = UnknownForm

  /** Delimiter used in lowering names */
  val delim = "_"
  def loweredTypeName(e: Expression): String = loweredTypeName(e, "")
  def loweredTypeName(e: Expression, substr: String): String = e match {
    case e: WRef => {
      if (substr == "") { e.name }
      else              { e.name + "_" + substr }
    }
    case e: WSubField => s"${loweredTypeName(e.expr, substr + e.name)}"
    case e: WSubIndex => s"${loweredTypeName(e.expr, substr)}[${e.value}]"
    case e: WSubAccess => s"${loweredTypeName(e.expr)}_${substr}[${loweredTypeName(e.index)}]"
  }

  def loweredExpName(e: Expression): String = loweredExpName(e, "")
  def loweredExpName(e: Expression, substr: String): String = e match {
    case e: WRef => {
      if (substr == "") { e.name }
      else              { e.name + "_" + substr }
    }
    case e: WSubField => s"${loweredExpName(e.expr, substr + e.name)}"
    case e: WSubIndex => s"${loweredExpName(e.expr, substr)}"
    case e: WSubAccess => s"${loweredExpName(e.expr)}_${substr}[${loweredExpName(e.index)}]"
  }
  /** Expands a chain of referential [[firrtl.ir.Expression]]s into the equivalent lowered name
    * @param e [[firrtl.ir.Expression]] made up of _only_ [[firrtl.WRef]], [[firrtl.WSubField]], and [[firrtl.WSubIndex]]
    * @return Lowered name of e
    */
  def loweredName(e: Expression): String = e match {
    case e: WRef => e.name
    case e: WSubField => s"${loweredName(e.expr)}$delim${e.name}"
    case e: WSubIndex => s"${loweredName(e.expr)}"
    case e: WSubAccess => s"${loweredName(e.expr)}[${loweredName(e.index)}]"
  }
  def loweredName(s: Seq[String]): String = s mkString delim
  def renameExps(renames: RenameMap, n: String, t: Type, root: String): Seq[String] =
    renameExps(renames, WRef(n, t, ExpKind, UnknownFlow), root)
  def renameExps(renames: RenameMap, n: String, t: Type): Seq[String] =
    renameExps(renames, WRef(n, t, ExpKind, UnknownFlow), "")
  def renameExps(renames: RenameMap, e: Expression, root: String): Seq[String] = e.tpe match {
    case (_: GroundType) =>
      val name = root + loweredTypeName(e)
      renames.rename(root + e.serialize, name)
      Seq(name)
    case (t: BundleType) => t.fields.flatMap { f =>
      val subNames = renameExps(renames, WSubField(e, f.name, f.tpe, times(flow(e), f.flip)), root)
      renames.rename(root + e.serialize, subNames)
      subNames
    }
    case (t: VectorType) =>
      // val name = root + loweredTypeName(e) + '[' + loweredTypeName(t.index) + ']'
      val name = root + loweredTypeName(e)
      Seq(name)
  }

  private def renameMemExps(renames: RenameMap, e: Expression, portAndField: Expression): Seq[String] = e.tpe match {
    case (_: GroundType) =>
      val (mem, tail) = splitRef(e)
      val loRef = mergeRef(WRef(loweredTypeName(e)), portAndField)
      val hiRef = mergeRef(mem, mergeRef(portAndField, tail))
      renames.rename(hiRef.serialize, loRef.serialize)
      Seq(loRef.serialize)
    case (t: BundleType) => t.fields.foldLeft(Seq[String]()){(names, f) =>
      val subNames = renameMemExps(renames, WSubField(e, f.name, f.tpe, times(flow(e), f.flip)), portAndField)
      val (mem, tail) = splitRef(e)
      val hiRef = mergeRef(mem, mergeRef(portAndField, tail))
      renames.rename(hiRef.serialize, subNames)
      names ++ subNames
    }
    case (t: VectorType) => (0 until t.size).foldLeft(Seq[String]()){(names, i) =>
      val subNames = renameMemExps(renames, WSubIndex(e, i, t.tpe,flow(e)), portAndField)
      val (mem, tail) = splitRef(e)
      val hiRef = mergeRef(mem, mergeRef(portAndField, tail))
      renames.rename(hiRef.serialize, subNames)
      names ++ subNames
    }
  }
  private case class LowerTypesException(msg: String) extends FirrtlInternalException(msg)
  private def error(msg: String)(info: Info, mname: String) =
    throw LowerTypesException(s"$info: [module $mname] $msg")

  // TODO Improve? Probably not the best way to do this
  private def splitMemRef(e1: Expression): (WRef, WRef, WRef, Option[Expression]) = {
    val (mem, tail1) = splitRef(e1)
    val (port, tail2) = splitRef(tail1)
    tail2 match {
      case e2: WRef =>
        (mem, port, e2, None)
      case _ =>
        val (field, tail3) = splitRef(tail2)
        (mem, port, field, Some(tail3))
    }
  }

  def create_exps(n: String, t: Type): Seq[Expression] =
    create_exps(WRef(n, t, ExpKind, UnknownFlow))
  def create_exps(e: Expression): Seq[Expression] = e match {
    case ex: Mux =>
      val e1s = create_exps(ex.tval)
      val e2s = create_exps(ex.fval)
      e1s zip e2s map {case (e1, e2) =>
        Mux(ex.cond, e1, e2, mux_type_and_widths(e1, e2))
      }
    case ex: ValidIf => create_exps(ex.value) map (e1 => ValidIf(ex.cond, e1, e1.tpe))
    case ex: WRef => {
      ex.tpe match {
        case (_: GroundType) => Seq(ex)
        case t: BundleType => {
          // val ret = t.fields.flatMap(f => create_exps(WSubField(ex, f.name, f.tpe, times(flow(ex), f.flip))))
          val ret = t.fields.flatMap(f => {
            println(s"t: BundleType : ${ex.name} _ ${f}")
            create_exps(WRef(loweredExpName(ex, f.name), f.tpe, ExpKind, flow(ex)))
          })
          ret
        }
        case t: VectorType => {
          val elem_exp = create_exps(WRef(ex.name, t.tpe, ExpKind, flow(ex)))
          elem_exp.map(e => WRef(loweredTypeName(e) , VectorType(e.tpe, t.size), ExpKind, flow(e)))
        }
      }
    }
    case ex => ex.tpe match {
      case (_: GroundType) => Seq(ex)
      case t: BundleType => t.fields.flatMap(f => create_exps(WSubField(ex, f.name, f.tpe, times(flow(ex), f.flip))))
      case t: VectorType => create_exps(WSubIndex(ex, t.size, t.tpe,flow(ex)))
    }
  }


  // Lowers an expression of MemKind
  // Since mems with Bundle type must be split into multiple ground type
  //   mem, references to fields addr, en, clk, and rmode must be replicated
  //   for each resulting memory
  // References to data, mask, rdata, wdata, and wmask have already been split in expand connects
  //   and just need to be converted to refer to the correct new memory
  type MemDataTypeMap = collection.mutable.HashMap[String, Type]
  def lowerTypesMemExp(memDataTypeMap: MemDataTypeMap,
      info: Info, mname: String)(e: Expression): Seq[Expression] = {
    val (mem, port, field, tail) = splitMemRef(e)
    field.name match {
      // Fields that need to be replicated for each resulting mem
      case "addr" | "en" | "clk" | "wmode" =>
        require(tail.isEmpty) // there can't be a tail for these
        memDataTypeMap(mem.name) match {
          case _: GroundType => Seq(e)
          case memType => create_exps(mem.name, memType) map { e =>
            val loMemName = loweredTypeName(e)
            val loMem = WRef(loMemName, UnknownType, kind(mem), UnknownFlow)
            mergeRef(loMem, mergeRef(port, field))
          }
        }
      // Fields that need not be replicated for each
      // eg. mem.reader.data[0].a
      // (Connect/IsInvalid must already have been split to ground types)
      case "data" | "mask" | "rdata" | "wdata" | "wmask" =>
        val loMem = tail match {
          case Some(ex) =>
            val loMemExp = mergeRef(mem, ex)
            val loMemName = loweredTypeName(loMemExp)
            WRef(loMemName, UnknownType, kind(mem), UnknownFlow)
          case None => mem
        }
        Seq(mergeRef(loMem, mergeRef(port, field)))
      case name => error(s"Error! Unhandled memory field $name")(info, mname)
    }
  }

  def lowerTypesExp(memDataTypeMap: MemDataTypeMap,
    info: Info, mname: String)(e: Expression): Expression = {
    e match {
      case e: WRef => e
      case e: WSubAccess => e map lowerTypesExp(memDataTypeMap, info, mname)
      case e: WSubField => {
        kind(e) match {
          case InstanceKind =>
            val (root, tail) = splitRef(e)
            val name = loweredTypeName(tail)
            WSubField(root, name, e.tpe, flow(e))
          case MemKind =>
            val exps = lowerTypesMemExp(memDataTypeMap, info, mname)(e)
            exps.size match {
              case 1 => exps.head
              case _ => error("Error! lowerTypesExp called on MemKind " +
                  "SubField that needs to be expanded!")(info, mname)
            }
          case _ => {
            val exps = lowerTypesExp(memDataTypeMap, info, mname)(e.expr)
            exps match {
              case ex: WSubAccess => WSubAccess(WRef(loweredName(ex.expr) + delim + e.name, e.tpe, kind(e), flow(e)), ex.index, ex.tpe, flow(ex))
              case ex: WSubIndex  => WSubIndex(WRef(loweredExpName(e), e.tpe, kind(e), flow(e)), ex.value, ex.tpe, flow(ex))
              case _ => WRef(loweredTypeName(e), e.tpe, kind(e), flow(e))
            }
          }
        }
      }
      case (e: WSubIndex) => kind(e) match {
        case InstanceKind =>
          val (root, tail) = splitRef(e)
          val name = loweredTypeName(tail)
          WSubField(root, name, e.tpe, flow(e))
        case MemKind =>
          val exps = lowerTypesMemExp(memDataTypeMap, info, mname)(e)
          exps.size match {
            case 1 => exps.head
            case _ => error("Error! lowerTypesExp called on MemKind " +
                "SubField that needs to be expanded!")(info, mname)
          }
        case _ => WSubIndex(e.expr, e.value, e.tpe, flow(e))
      }
      case e: Mux => e map lowerTypesExp(memDataTypeMap, info, mname)
      case e: ValidIf => e map lowerTypesExp(memDataTypeMap, info, mname)
      case e: DoPrim => e map lowerTypesExp(memDataTypeMap, info, mname)
      case e @ (_: UIntLiteral | _: SIntLiteral) => e
    }
  }
  def lowerTypesStmt(memDataTypeMap: MemDataTypeMap,
      minfo: Info, mname: String, renames: RenameMap)(s: Statement): Statement = {
    val info = get_info(s) match {case NoInfo => minfo case x => x}
    s map lowerTypesStmt(memDataTypeMap, info, mname, renames) match {
      case s: DefWire => s.tpe match {
        case _: GroundType => s
        case _ =>
          val exps = create_exps(s.name, s.tpe)
          val names = exps map loweredTypeName
          renameExps(renames, s.name, s.tpe)
          Block((exps zip names) map { case (e, n) =>
            DefWire(s.info, n, e.tpe)
          })
      }
      case sx: DefRegister => sx.tpe match {
        case _: GroundType => sx map lowerTypesExp(memDataTypeMap, info, mname)
        case _ =>
          val es = create_exps(sx.name, sx.tpe)
          val names = es map loweredTypeName
          renameExps(renames, sx.name, sx.tpe)
          val inits = create_exps(sx.init) map lowerTypesExp(memDataTypeMap, info, mname)
          val clock = lowerTypesExp(memDataTypeMap, info, mname)(sx.clock)
          val reset = lowerTypesExp(memDataTypeMap, info, mname)(sx.reset)
          Block((es zip names) zip inits map { case ((e, n), i) =>
            DefRegister(sx.info, n, e.tpe, clock, reset, i)
          })
      }
      // Could instead just save the type of each Module as it gets processed
      case sx: WDefInstance => sx.tpe match {
        case t: BundleType =>
          val fieldsx = t.fields flatMap { f =>
            renameExps(renames, f.name, f.tpe, s"${sx.name}.")
            create_exps(WRef(f.name, f.tpe, ExpKind, times(f.flip, SourceFlow))) map { e =>
              // Flip because inst flows are reversed from Module type
              Field(loweredTypeName(e), swap(to_flip(flow(e))), e.tpe)
            }
          }
          WDefInstance(sx.info, sx.name, sx.module, BundleType(fieldsx))
        case _ => error("WDefInstance type should be Bundle!")(info, mname)
      }
      case sx: DefMemory =>
        memDataTypeMap(sx.name) = sx.dataType
        sx.dataType match {
          case _: GroundType => sx
          case _ =>
            // Rename ports
            val seen: mutable.Set[String] = mutable.Set[String]()
            create_exps(sx.name, memType(sx)) foreach { e =>
              val (mem, port, field, tail) = splitMemRef(e)
              if (!seen.contains(field.name)) {
                seen += field.name
                val d = WRef(mem.name, sx.dataType)
                tail match {
                  case None =>
                    create_exps(mem.name, sx.dataType) foreach { x =>
                      renames.rename(e.serialize, s"${loweredTypeName(x)}.${port.serialize}.${field.serialize}")
                    }
                  case Some(_) =>
                    renameMemExps(renames, d, mergeRef(port, field))
                }
              }
            }
            Block(create_exps(sx.name, sx.dataType) map {e =>
              val newName = loweredTypeName(e)
              // Rename mems
              renames.rename(sx.name, newName)
              sx copy (name = newName, dataType = e.tpe)
            })
        }
      // wire foo : { a , b }
      // node x = foo
      // node y = x.a
      //  ->
      // node x_a = foo_a
      // node x_b = foo_b
      // node y = x_a
      case sx: DefNode =>
        val names = create_exps(sx.name, sx.value.tpe) map lowerTypesExp(memDataTypeMap, info, mname)
        val exps = create_exps(sx.value) map lowerTypesExp(memDataTypeMap, info, mname)
        renameExps(renames, sx.name, sx.value.tpe)
        Block(names zip exps map { case (n, e) =>
          DefNode(info, loweredTypeName(n), e)
        })
      case sx: IsInvalid => kind(sx.expr) match {
        case MemKind =>
          Block(lowerTypesMemExp(memDataTypeMap, info, mname)(sx.expr) map (IsInvalid(info, _)))
        case _ => sx map lowerTypesExp(memDataTypeMap, info, mname)
      }
      case sx: Connect => kind(sx.loc) match {
        case MemKind =>
          val exp = lowerTypesExp(memDataTypeMap, info, mname)(sx.expr)
          val locs = lowerTypesMemExp(memDataTypeMap, info, mname)(sx.loc)
          Block(locs map (Connect(info, _, exp)))
        case _ => {
          sx map lowerTypesExp(memDataTypeMap, info, mname)
        }
      }
      case sx => sx map lowerTypesExp(memDataTypeMap, info, mname)
    }
  }

  def lowerTypes(renames: RenameMap)(m: DefModule): DefModule = {
    val memDataTypeMap = new MemDataTypeMap
    renames.setModule(m.name)
    // Lower Ports
    val portsx = m.ports flatMap { p =>
      val exps = create_exps(WRef(p.name, p.tpe, PortKind, to_flow(p.direction)))
      val names = exps map loweredName
      renameExps(renames, p.name, p.tpe)
      (exps zip names) map { case (e, n) =>
        Port(p.info, n, to_dir(flow(e)), e.tpe)
      }
    }
    m match {
      case m: ExtModule =>
        m copy (ports = portsx)
      case m: Module =>
        m copy (ports = portsx) map lowerTypesStmt(memDataTypeMap, m.info, m.name, renames)
    }
  }

  def execute(state: CircuitState): CircuitState = {
    val c = state.circuit
    val renames = RenameMap()
    renames.setCircuit(c.main)
    val result = c copy (modules = c.modules map lowerTypes(renames))
    CircuitState(result, outputForm, state.annotations, Some(renames))
  }
}
