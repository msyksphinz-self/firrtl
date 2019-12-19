circuit SimpleCircuit :
  module SimpleCircuit :
    input io_in : UInt<32>
    output io_out : UInt<32>
  
    io_out <= io_in
