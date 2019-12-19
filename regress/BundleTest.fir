circuit BundleTest :
  module BundleTest :
    input in: { a : UInt<32>, b : UInt<32> }
    output out : { a : UInt<32>, ans: UInt<32> }

    out.a <= in.a
    out.ans <= add(in.a, in.b)
