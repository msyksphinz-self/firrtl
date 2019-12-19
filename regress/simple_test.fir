circuit SimpleCircuit :
  module SimpleCircuit :
    input clk : Clock
    input rst : UInt<1>
    input arst : AsyncReset
    input arstn : AsyncResetN
    input en: UInt <1>
    input clr: UInt <1>
    output io : {flip in : UInt<32>, out : UInt<32>}
    output myreg2_out : UInt<32>
    output myreg3_out : UInt<32>

    reg myreg : UInt<32>, clk with : (reset => (rst, UInt<32>("h0")))
    when en :
      myreg <= io.in
      skip
    when clr :
      myreg <= UInt<32>("h1")
      skip
    io.out <= myreg

    reg myreg2 : UInt<32>, clk with : (reset => (arst, UInt<32>("h0")))
    when en :
      myreg2 <= io.in
      skip
    when clr :
      myreg2 <= UInt<32>("h1")
      skip
    myreg2_out <= myreg2

    reg myreg3 : UInt<32>, clk with : (reset => (arstn, UInt<32>("h0")))
    when en :
      myreg3 <= io.in
      skip
    when clr :
      myreg3 <= UInt<32>("h1")
      skip
    myreg3_out <= myreg3
