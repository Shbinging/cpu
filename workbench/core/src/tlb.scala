package njumips
import chisel3._
import chisel3.util._
import njumips.configs._
import njumips.consts._


class TLBEntry extends Bundle{
    val hi = new EntryHi
    val lo_0 = new EntryLo
    val lo_1 = new EntryLo
}

class TLB extends Module{
    val io = IO(new Bundle{
        val in = new WrTLB
        val entries = Output(Vec(conf.tlb_size, new TLBEntry))
    })

    val tlb = Mem(conf.tlb_size, new TLBEntry)
    for(i <- 0 to conf.tlb_size-1){
        io.entries(i) := tlb(i)
    }
}

class TLBTranslator extends Module{
    val io = IO(new Bundle{
        val tlb = Input(Vec(conf.tlb_size, new TLBEntry))
        val asid = Input(UInt(8.W))
        val req = new TLBTranslatorReq
        val resp = new TLBTranslatorOutput
    })
    // FIX Page Size 4096 kB

    io.resp.exception := ET_None
    
    // unmapped addr
    when(io.req.va >= "h_8000_0000".U && io.req.va < "h_c000_0000".U){ 
        // uncached
        when(io.req.va >= "h_a000_0000".U){
            io.resp.pa := io.req.va - "h_a000_0000".U 
            io.resp.cached := false.B
        } .otherwise{
            io.resp.pa := io.req.va - "h_8000_0000".U 
            io.resp.cached := true.B
        }
    } .otherwise{
        // mapped
        val found = Bool()
        val entry = Wire(new EntryLo)
        found := false.B
        io.resp.cached := true.B
        for(i <- 0 to conf.tlb_size-1){
            when(
                (io.tlb(i).hi.vpn2 === (io.req.va>>12)) && (
                    (io.tlb(i).lo_0.global & io.tlb(i).lo_1.global) ||
                    (io.tlb(i).hi.asid === io.asid)
                )
            ){
                entry := Mux(io.req.va(13)===0.U, io.tlb(i).lo_0, io.tlb(i).lo_1)
                when(entry.valid === false.B){
                    io.resp.exception := ET_TLB_Inv
                } .elsewhen(entry.dirty===false.B && io.req.ref_type===MX_WR){
                    io.resp.exception := ET_TLB_Mod
                }
                io.resp.pa := Cat(entry.pfn(31, 12), io.req.va(11, 0))
                found := true.B
            }
        }

        when(!found){
            io.resp.exception := ET_TLB_Miss
        }
    }
}