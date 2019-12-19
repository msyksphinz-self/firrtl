circuit VecBundle1 :
  module VecBundle1 :
    input in : { a : UInt<32>, b : UInt<32>}[4]
    input sel : UInt<2>
    output out : { a : UInt<32>, b : UInt<32>}
  
    out.a <= in[sel].a
    out.b <= in[sel].b
