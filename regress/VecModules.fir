circuit VecModules :
  module VecModules :
    input in: UInt<1>[4]
    input sel: UInt<2>
    output out : UInt<1>
    out <= in[sel]
