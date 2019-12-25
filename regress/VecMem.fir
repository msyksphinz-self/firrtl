circuit VecMem :
  module VecMem :
    input clock : Clock

    input in: UInt<32>
    input wr_en: UInt<1>
    input rd_en: UInt<1>

    input addr: UInt<6>
    input data: UInt<1>

    output rdata : UInt<32>[4]

    smem array : UInt<32>[4][64]

    when rd_en :
      read mport rdata_tmp = array[addr], clock
    when wr_en :
      write mport wdata = array[addr], clock

    rdata <= rdata_tmp
