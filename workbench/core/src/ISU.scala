package njumips

import chisel3._
import chisel3.util._
import njumips.configs._
import njumips.consts._


class ISU extends Module {
    val io = IO(new Bundle{
        val out_gpr_read = Flipped(new GPRReadIntput)
        val gpr_data = Flipped(new GPRReadOutput) //input
        val id_isu = Flipped(Decoupled(new ID_ISU)) //input
        val flush = Input(Bool())
        val rb_isu = new GPRWriteInput
        val alu_pass = Flipped(new ALU_PASS)
        val isu_alu = Decoupled(new ISU_ALU) //output
        val isu_bru = Decoupled(new ISU_BRU)
        val isu_lsu = Decoupled(new ISU_LSU)
        val isu_mdu = Decoupled(new ISU_MDU)
        val isu_pru = Decoupled(new ISU_PRU)
    })
    
    val reg_id_isu = RegEnable(next=io.id_isu.bits, enable=io.id_isu.fire())
    val reg_gpr_isu = RegEnable(next=io.gpr_data, enable=io.id_isu.fire())
    val reg_id_isu_prepared = RegInit(false.B)
    
    // XXX: OoO not allowed 
    val empty = Wire(Bool())
    empty := (io.isu_alu.ready && io.isu_bru.ready && io.isu_lsu.ready && io.isu_mdu.ready && io.isu_pru.ready)
    io.id_isu.ready := !reg_id_isu_prepared || io.isu_alu.fire() || io.isu_bru.fire() || io.isu_lsu.fire() || io.isu_mdu.fire() || io.isu_pru.fire()
    //printf("id_isu.ready %d\n", io.id_isu.ready)
    io.isu_alu <> DontCare
    io.isu_alu.valid := false.B
    io.isu_bru <> DontCare
    io.isu_bru.valid := false.B
    io.isu_lsu <> DontCare
    io.isu_lsu.valid := false.B
    io.isu_mdu <> DontCare
    io.isu_mdu.valid := false.B
    io.isu_pru <> DontCare
    io.isu_pru.valid := false.B
// * scoreboard
    io.out_gpr_read.rs_addr := reg_id_isu.read1
    io.out_gpr_read.rt_addr := reg_id_isu.read2
    val dirtys = Mem(32, Bool())
    when(reset.asBool() || io.flush){
        for (i <- 0 to 31){
            dirtys(i) := N
        }
    }
    def isValid(idx:UInt) = !dirtys(idx) || rwConflict(idx)
    def isReadValid(idx:UInt) = !dirtys(idx) || rwConflict(idx) || aluPass(idx)
    def rmDirty() = io.rb_isu.w_en =/= 0.U && io.rb_isu.addr =/= 0.U
    def rmDirtyByALU() = io.alu_pass.rm_dirty
    def aluPassing() = io.alu_pass.w_en =/= 0.U && io.alu_pass.w_addr =/= 0.U
    def rwConflict(idx:UInt) = rmDirty() && (io.rb_isu.addr === idx)
    def aluPass(idx:UInt) = aluPassing() && (io.alu_pass.w_addr === idx)
    def getData1(idx:UInt) = Mux(aluPass(idx), io.alu_pass.ALU_out, Mux(rwConflict(idx), io.rb_isu.data, io.gpr_data.rs_data))
    def getData2(idx:UInt) = Mux(aluPass(idx), io.alu_pass.ALU_out, Mux(rwConflict(idx), io.rb_isu.data, io.gpr_data.rt_data))
    //def getData1(idx:UInt) = io.gpr_data.rs_data
    //def getData2(idx:UInt) = io.gpr_data.rt_data
    val canLaunch = WireInit(N)
    val rsData, rtData = WireInit(0.U(32.W))
    when(io.id_isu.fire()){
        //printf("ok\n");
    }    
    when(rmDirty()){
        //printf("rm dirty %d\n", io.rb_isu.addr)
        dirtys(io.rb_isu.addr) := 0.U
    }
    when(rmDirtyByALU()){
        dirtys(io.alu_pass.w_addr) := 0.U
    }
    when(!io.flush && reg_id_isu_prepared && empty && (isReadValid(reg_id_isu.read1) || reg_id_isu.isRead1CP0) && (isReadValid((reg_id_isu.read2)) || reg_id_isu.isRead2CP0) && (isValid(reg_id_isu.write) || reg_id_isu.isWriteCP0)){
        when(reg_id_isu.write =/= 0.U && !reg_id_isu.isWriteCP0){
            dirtys(reg_id_isu.write) := Y
        }
        printf("launch %x\n", reg_id_isu.pcNext - 4.U)
        canLaunch := Y
        rsData := getData1(reg_id_isu.read1)
        rtData := getData2(reg_id_isu.read2)
        //printf("alu pass %x %x %x\n", io.alu_pass.ALU_out, io.rb_isu.data, io.gpr_data.rt_data)
        //printf(p"rt Data: ${rtData} @ ${io.out_gpr_read.rt_addr}\n")
    }.otherwise{
       // printf("%d %d %d %d %d\n",reg_id_isu_prepared, empty, isValid(reg_id_isu.read1) , isValid((reg_id_isu.read2)) ,isValid(reg_id_isu.write) )
        //printf("read1 %d\n", reg_id_isu.read1)
        canLaunch := N
    }    
    switch(reg_id_isu.exu){
        is (PRU_ID){
            io.isu_pru.valid := canLaunch && reg_id_isu_prepared
            io.isu_pru.bits.current_pc := reg_id_isu.pcNext - 4.U
            printf("@isu pru_op %d\n", reg_id_isu.op(3, 0))
            io.isu_pru.bits.pru_op := reg_id_isu.op(3, 0)
            io.isu_pru.bits.current_instr := reg_id_isu.current_instr
            io.isu_pru.bits.except_info <> reg_id_isu.except_info
            io.isu_pru.bits.rd_addr := reg_id_isu.write
            io.isu_pru.bits.rs_addr := reg_id_isu.read1
            io.isu_pru.bits.rt_addr := reg_id_isu.read2
            io.isu_pru.bits.rs_data := rsData
        }
        is(ALU_ID){
            val iaBundle = Wire(new ISU_ALU)
            iaBundle := DontCare
            iaBundle.imm := reg_id_isu.imm
            iaBundle.operand_1 := Mux(reg_id_isu.shamt_rs_sel, rsData, reg_id_isu.shamt)
            iaBundle.alu_op := reg_id_isu.op
            iaBundle.rd_addr := reg_id_isu.rd_addr
            iaBundle.current_instr := reg_id_isu.current_instr
            iaBundle.current_pc := reg_id_isu.pcNext-4.U
            io.isu_alu.valid := canLaunch && reg_id_isu_prepared
            io.isu_alu.bits.alu_op := reg_id_isu.op
            switch(reg_id_isu.imm_rt_sel){
                is(false.B){//imm
                    when(reg_id_isu.sign_ext){
                        iaBundle.operand_2 := Cat(Fill((16), reg_id_isu.imm(15)), reg_id_isu.imm).asUInt()
                    }.otherwise{
                        iaBundle.operand_2 := Cat(Fill((16), 0.U), reg_id_isu.imm).asUInt()
                    }
                }
                is (true.B){//reg
                    iaBundle.operand_2 := rtData
                }
            }
            io.isu_alu.bits <> iaBundle
        }
        is(BRU_ID){
            val bruBundle = Wire(new ISU_BRU)
            bruBundle.bru_op := reg_id_isu.op
            bruBundle.offset := reg_id_isu.imm
            bruBundle.rd := reg_id_isu.rd_addr
            bruBundle.instr_index := reg_id_isu.instr_index
            bruBundle.rsData := rsData //XXX:need handshake
            bruBundle.rtData := rtData
            bruBundle.pcNext := reg_id_isu.pcNext
            bruBundle.current_instr := reg_id_isu.current_instr
            bruBundle.current_pc := reg_id_isu.pcNext - 4.U
            io.isu_bru.valid := canLaunch && reg_id_isu_prepared
            io.isu_bru.bits <> bruBundle
        }
        is(LSU_ID){
            io.isu_lsu.valid := canLaunch && reg_id_isu_prepared
            io.isu_lsu.bits.imm := reg_id_isu.imm
            io.isu_lsu.bits.rsData := rsData
            io.isu_lsu.bits.rtData := rtData
            io.isu_lsu.bits.rt := reg_id_isu.rd_addr
            io.isu_lsu.bits.lsu_op := reg_id_isu.op

            io.isu_lsu.bits.current_instr := reg_id_isu.current_instr
            io.isu_lsu.bits.current_pc := reg_id_isu.pcNext - 4.U
        }
        is(MDU_ID){
            io.isu_mdu.valid := canLaunch && reg_id_isu_prepared
            io.isu_mdu.bits.mdu_op := reg_id_isu.op
            io.isu_mdu.bits.rsData := rsData
            io.isu_mdu.bits.rtData := rtData
            io.isu_mdu.bits.rd := reg_id_isu.rd_addr

            io.isu_mdu.bits.current_instr := reg_id_isu.current_instr
            io.isu_mdu.bits.current_pc := reg_id_isu.pcNext - 4.U
        }
    }

    when(io.flush || (!io.id_isu.fire() && (io.isu_alu.fire() || io.isu_bru.fire() || io.isu_mdu.fire() || io.isu_lsu.fire() || io.isu_pru.fire()))){
        reg_id_isu_prepared := false.B
    } .elsewhen(!io.flush && io.id_isu.fire()){
        reg_id_isu_prepared := true.B
    }
    //printf("@isu reg_id_isu_prepared %d\n", reg_id_isu_prepared)
}