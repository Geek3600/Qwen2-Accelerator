import DM.Param._
import DM.DM
import DM2.PipDM2
import Load0.Param.MEMW
import Load0.Load0
import ResMEM.PipResForV
import Softmax.Param.{MEM_WIDTH, WMEM_DEPTH, WMEM_WIDTH}
import Softmax.SoftmaxPip
import chisel3._
import chisel3.util._
class Atten extends Module {
val io = IO(new Bundle() {
  val layer_st = Input(Bool())
  val cfg_seqlen = Input(UInt(log2Up( MAX_SEQLEN).W))
  val cfg_prefill = Input(Bool())
  val cfg_valid = Input(Bool())


  val w_in = Input(UInt(WMEM_WIDTH.W))
  val w_addr = Output(UInt(log2Up(WMEM_DEPTH).W))



  val data_in_st = Input(Bool())
  val data_in = Input(UInt((3 * LOAD_VECNUM * DATAW).W))
  val data_addr = Input(UInt(log2Up(HEAD_VECNUM / LOAD_VECNUM * BATCHSIZE).W))
  val data_valid = Input(Bool())
  val data_last = Input(Bool())
  val data_ready = Output(Bool())


  val res = Output(UInt(MEM_WIDTH.W))
  val res_st = Output(Bool())
  val res_addr = Output(UInt(log2Up(MEM_DEPTH).W))
  val res_valid = Output(Bool())
  val res_last = Output(Bool())
  val res_ready = Input(Bool())

})

  val dmpip_inst = Module(new DM)
  val softmaxpip_inst = Module(new SoftmaxPip)
  val resmempip_inst = Module(new PipResForV)
  val dm2pip_inst = Module(new PipDM2)



  dmpip_inst.io.cfg_seqlen := io.cfg_seqlen
  dmpip_inst.io.cfg_valid := io.cfg_valid
  dmpip_inst.io.cfg_prefill := io.cfg_prefill

  dmpip_inst.io.data_in_st := io.data_in_st
  dmpip_inst.io.data_in := io.data_in(2*LOAD_VECNUM * DATAW - 1 , 0)
  dmpip_inst.io.data_addr := io.data_addr
  dmpip_inst.io.data_valid := io.data_valid
  dmpip_inst.io.data_last := io.data_last

  dmpip_inst.io.res_ready := softmaxpip_inst.io.data_ready



  softmaxpip_inst.io.cfg_seqlen := io.cfg_seqlen
  softmaxpip_inst.io.cfg_valid := io.cfg_valid
  softmaxpip_inst.io.cfg_prefill := io.cfg_prefill

  softmaxpip_inst.io.layer_st := io.layer_st
  softmaxpip_inst.io.data_in_st := dmpip_inst.io.res_st
  softmaxpip_inst.io.data_in := dmpip_inst.io.res
  softmaxpip_inst.io.data_addr := dmpip_inst.io.res_addr
  softmaxpip_inst.io.data_valid := dmpip_inst.io.res_valid
  softmaxpip_inst.io.data_last := dmpip_inst.io.res_last

  softmaxpip_inst.io.res_ready := dm2pip_inst.io.data_in_ctx_ready

  softmaxpip_inst.io.w_in := io.w_in
  io.w_addr:= softmaxpip_inst.io.w_addr




  resmempip_inst.io.cfg_valid := io.cfg_valid
  resmempip_inst.io.cfg_seqlen := io.cfg_seqlen
  resmempip_inst.io.cfg_prefill := io.cfg_prefill

  resmempip_inst.io.data_in_st := io.data_in_st
  resmempip_inst.io.data_in := io.data_in(3 * LOAD_VECNUM * DATAW - 1, 2 * LOAD_VECNUM * DATAW)
  resmempip_inst.io.data_in_addr := io.data_addr
  resmempip_inst.io.data_in_valid := io.data_valid
  resmempip_inst.io.data_in_last := io.data_last
  resmempip_inst.io.res_ready := dm2pip_inst.io.data_in_v_ready





  dm2pip_inst.io.cfg_valid := io.cfg_valid
  dm2pip_inst.io.cfg_seqlen := io.cfg_seqlen
  dm2pip_inst.io.cfg_prefill := io.cfg_prefill

  dm2pip_inst.io.data_in_ctx_st := softmaxpip_inst.io.res_st
  dm2pip_inst.io.data_in_ctx := softmaxpip_inst.io.res
  dm2pip_inst.io.data_in_ctx_addr := softmaxpip_inst.io.res_addr
  dm2pip_inst.io.data_in_ctx_valid := softmaxpip_inst.io.res_valid
  dm2pip_inst.io.data_in_ctx_last := softmaxpip_inst.io.res_last

  dm2pip_inst.io.data_in_v_st := resmempip_inst.io.res_st
  dm2pip_inst.io.data_in_v := resmempip_inst.io.res
  dm2pip_inst.io.data_in_v_addr := resmempip_inst.io.res_addr
  dm2pip_inst.io.data_in_v_valid := resmempip_inst.io.res_valid
  dm2pip_inst.io.data_in_v_last := resmempip_inst.io.res_last

  dm2pip_inst.io.res_ready := io.res_ready




  io.data_ready := resmempip_inst.io.data_in_ready && dmpip_inst.io.data_ready

  io.res := dm2pip_inst.io.res
  io.res_st := dm2pip_inst.io.res_st
  io.res_addr := dm2pip_inst.io.res_addr
  io.res_valid := dm2pip_inst.io.res_valid
  io.res_last := dm2pip_inst.io.res_last





}

class Top extends Module{
  val io = IO(new Bundle() {
    val layer_st = Input(Bool())
    val cfg_seqlen = Input(UInt(log2Up(MAX_SEQLEN).W))
    val cfg_prefill = Input(Bool())
    val cfg_valid = Input(Bool())

    val data_in = Input(UInt((3 * LOAD_VECNUM * DATAW).W))
    val data_in_ready = Input(Bool())
    val data_in_addr = Output(UInt(32.W))

    val w_in = Input(UInt(WMEM_WIDTH.W))
    val w_addr = Output(UInt(log2Up(WMEM_DEPTH).W))


    val res = Output(UInt(MEM_WIDTH.W))
    val res_st = Output(Bool())
    val res_addr = Output(UInt(log2Up(MEM_DEPTH).W))
    val res_valid = Output(Bool())
    val res_last = Output(Bool())
    val res_ready = Input(Bool())

  })

  val load_inst = Module(new Load0)

  val pipinst = Module(new Atten)


  load_inst.io.cfg_valid := io.cfg_valid
  load_inst.io.cfg_seqlen := io.cfg_seqlen
  load_inst.io.cfg_prefill := io.cfg_prefill
  load_inst.io.data_in := io.data_in
  load_inst.io.data_in_ready := io.data_in_ready
  load_inst.io.data_out_ready := pipinst.io.data_ready

  pipinst.io.cfg_valid := io.cfg_valid
  pipinst.io.cfg_prefill := io.cfg_prefill
  pipinst.io.cfg_seqlen := io.cfg_seqlen
  pipinst.io.layer_st := io.layer_st
  pipinst.io.w_in := io.w_in

  pipinst.io.data_in_st := load_inst.io.data_out_st
  pipinst.io.data_in := load_inst.io.data_out
  pipinst.io.data_addr := load_inst.io.data_out_addr
  pipinst.io.data_valid := load_inst.io.data_out_valid
  pipinst.io.data_last := load_inst.io.data_out_last

  pipinst.io.res_ready := io.res_ready


  io.data_in_addr := load_inst.io.data_in_addr
  io.w_addr := pipinst.io.w_addr
  io.res := pipinst.io.res
  io.res_st := pipinst.io.res_st
  io.res_addr := pipinst.io.res_addr
  io.res_valid := pipinst.io.res_valid
  io.res_last := pipinst.io.res_last









}
object AttenTopGen extends App {
  emitVerilog(new Top, Array("--target-dir", "generated"))
}
