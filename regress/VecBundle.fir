circuit VecBundle1 :
  module VecBundle1 :
    input in: { a : UInt<32>, b : UInt<32> }[4]
    input sel: UInt<2>
    output out : { a : UInt<32>, b : UInt<32> }

    out.a <= in[sel].a
    out.b <= in[sel].b

  module VecBundle2 :
    input in: { a : UInt<32>[3] }[4]
    input sel: UInt<2>
    output out : { a : UInt<32>[3] }
    out.a <= in[sel].a

  module VecBundle3 :
    input in: { a : { b : UInt<8>[2] } [3] }[4]
    input sel: UInt<2>
    output out : { b : UInt<8>[2] }
    out.b <= in[0].a[1].b

  module VecBundle4 :
    input in: UInt<32>[3][4]
    input sel: UInt<2>
    output out : UInt<32>[3]
    out <= in[1]

  module VecBundle5 :
    input in: UInt<8>[4]
    input sel: UInt<2>
    output out : UInt<8>
    out <= in[sel]

  module VecBundle6 :
    input in: { a : { b : UInt<8>[2] } [3] }[4]
    input sel1: UInt<2>
    input sel2: UInt<2>
    output out : { b : UInt<8>[2] }
    out.b <= in[sel1].a[sel2].b

  module VecBundle7 :
    input in: { a : UInt<32>[3] }
    output out : UInt<32>
    out <= in.a[0]

  module VecBundle8  :
    input in: { a : { b : { c : UInt<8>[4] } [8] } [16] }[32]
    output out : { b : UInt<8>[4] }
    out.b <= in[0].a[1].b[2].c

  module VecBundle9  :
    input in: { a : { b : { c : UInt<8>[4] } [8] } [16] }[32]
    input sel1: UInt<5>
    input sel2: UInt<4>
    input sel3: UInt<3>
    output out : { b : UInt<8>[4] }
    out.b <= in[sel1].a[sel2].b[sel3].c
