circuit SimpleCircuit :
  module SimpleCircuit :
    output io : { flip in : UInt<32>, out : UInt<32>}
  
    io.out <= io.in
