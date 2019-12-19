;;circuit VecBundle1 :
;;  module VecBundle1 :
;;    input in: { a : UInt<32>, b : UInt<32> }[4]
;;    input sel: UInt<2>
;;    output out : { a : UInt<32>, b : UInt<32> }
;;
;;    out.a <= in[sel].a
;;    out.b <= in[sel].b

;;circuit VecBundle2 :
;;  module VecBundle2 :
;;    input in: { a : UInt<32>[3] }[4]
;;    input sel: UInt<2>
;;    output out : { a : UInt<32>[3] }
;;    out.a <= in[sel].a

;;circuit VecBundle2 :
;;  module VecBundle2 :
;;    input in: { a : { b : UInt<8>[2] } [3] }[4]
;;    input sel: UInt<2>
;;    output out : { b : UInt<8>[2] }
;;    out.b <= in[0].a[1].b


;;circuit VecBundle3 :
;;  module VecBundle3 :
;;    input in: UInt<32>[3][4]
;;    input sel: UInt<2>
;;    output out : UInt<32>[3]
;;    out <= in[1]

;;circuit VecBundle4 :
;;  module VecBundle4 :
;;    input in: UInt<8>[4]
;;    input sel: UInt<2>
;;    output out : UInt<8>
;;    out <= in[sel]

circuit VecBundle5 :
  module VecBundle5 :
    input in: { a : { b : UInt<8>[2] } [3] }[4]
    input sel1: UInt<2>
    input sel2: UInt<2>
    output out : { b : UInt<8>[2] }
    out.b <= in[sel1].a[sel2].b
