circuit BundleTest :
  module BundleTest :
    input  in  : { a : UInt<32>, b : UInt<32>[4] }
    output out : { a : UInt<32>, b: UInt<32>[4] }

    out <= in
