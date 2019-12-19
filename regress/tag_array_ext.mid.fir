circuit tag_array_ext :
  module tag_array_ext :
    input RW0_clk : Clock
    input RW0_addr : UInt<6>
    input RW0_wdata : UInt<80>
    output RW0_rdata : UInt<80>
    input RW0_en : UInt<1>
    input RW0_wmode : UInt<1>
    input RW0_wmask : UInt<4>
  
    inst mem_0_0 of rawr
    inst mem_0_1 of rawr
    inst mem_0_2 of rawr
    inst mem_0_3 of rawr
    node RW0_rdata_0_0 = bits(mem_0_0.dout, 19, 0)
    node RW0_rdata_0_1 = bits(mem_0_1.dout, 19, 0)
    node RW0_rdata_0_2 = bits(mem_0_2.dout, 19, 0)
    node RW0_rdata_0_3 = bits(mem_0_3.dout, 19, 0)
    node RW0_rdata_0 = cat(RW0_rdata_0_3, cat(RW0_rdata_0_2, cat(RW0_rdata_0_1, RW0_rdata_0_0)))
    RW0_rdata <= mux(UInt<1>("h1"), RW0_rdata_0, UInt<1>("h0"))
    mem_0_0.clk <= RW0_clk
    mem_0_0.addr <= RW0_addr
    mem_0_0.din <= bits(RW0_wdata, 19, 0)
    mem_0_0.write_en <= and(and(RW0_wmode, bits(RW0_wmask, 0, 0)), UInt<1>("h1"))
    mem_0_1.clk <= RW0_clk
    mem_0_1.addr <= RW0_addr
    mem_0_1.din <= bits(RW0_wdata, 39, 20)
    mem_0_1.write_en <= and(and(RW0_wmode, bits(RW0_wmask, 1, 1)), UInt<1>("h1"))
    mem_0_2.clk <= RW0_clk
    mem_0_2.addr <= RW0_addr
    mem_0_2.din <= bits(RW0_wdata, 59, 40)
    mem_0_2.write_en <= and(and(RW0_wmode, bits(RW0_wmask, 2, 2)), UInt<1>("h1"))
    mem_0_3.clk <= RW0_clk
    mem_0_3.addr <= RW0_addr
    mem_0_3.din <= bits(RW0_wdata, 79, 60)
    mem_0_3.write_en <= and(and(RW0_wmode, bits(RW0_wmask, 3, 3)), UInt<1>("h1"))

  extmodule rawr :
    input clk : Clock
    input addr : UInt<6>
    input din : UInt<32>
    output dout : UInt<32>
    input write_en : UInt<1>
  
    defname = rawr
    
