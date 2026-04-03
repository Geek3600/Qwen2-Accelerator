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
  dmpip_inst.io.data_in := io.data_in(2*LOAD_VECNUM * DATAW - 1 , 0) // 将QK数据输入到DM1，load0每个周期收到2个Q和K元素，需要32个周期收集完整的64维向量
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
  resmempip_inst.io.data_in := io.data_in(3 * LOAD_VECNUM * DATAW - 1, 2 * LOAD_VECNUM * DATAW) // 将V数据输入到RESMEM进行存储
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

    // 修改：输入改为 96-bit (LayerNorm 输出格式)
    val data_in = Input(UInt(96.W))  // 96-bit (12 elements)
    val data_in_ready = Input(Bool())
    val data_in_addr = Output(UInt(32.W))

    // 新增：LayerNorm 权重输入
    val ln_w_in = Input(UInt(96.W))
    val ln_w_valid = Input(Bool())

    // Softmax 权重输入
    val w_in = Input(UInt(WMEM_WIDTH.W))
    val w_addr = Output(UInt(log2Up(WMEM_DEPTH).W))

    val res = Output(UInt(MEM_WIDTH.W))
    val res_st = Output(Bool())
    val res_addr = Output(UInt(log2Up(MEM_DEPTH).W))
    val res_valid = Output(Bool())
    val res_last = Output(Bool())
    val res_ready = Input(Bool())

  })

  // ========================================
  // 模块实例化
  // ========================================
  val ln_addr_gen = Module(new TempAdapter.LNAddrGen)
  val ln_inst = Module(new LayerNorm.PipLine)
  val adapter_inst = Module(new TempAdapter.TempAdapter)
  val pipinst = Module(new Atten)

  // ========================================
  // LNAddrGen 连接
  // ========================================
  ln_addr_gen.io.cfg_seqlen := io.cfg_seqlen
  ln_addr_gen.io.cfg_prefill := io.cfg_prefill
  ln_addr_gen.io.cfg_valid := io.cfg_valid
  ln_addr_gen.io.data_ready := io.data_in_ready
  ln_addr_gen.io.adapter_ready := adapter_inst.io.ln_ready

  io.data_in_addr := ln_addr_gen.io.mem_addr

  // ========================================
  // LayerNorm 连接
  // ========================================
  ln_inst.io.data_in := io.data_in
  ln_inst.io.data_in_st := ln_addr_gen.io.data_st
  ln_inst.io.data_addr := ln_addr_gen.io.data_addr
  ln_inst.io.data_valid := ln_addr_gen.io.data_valid
  ln_inst.io.data_last := ln_addr_gen.io.data_last
  ln_inst.io.data_ready := true.B  // LayerNorm 总是准备好

  ln_inst.io.w_in := io.ln_w_in
  ln_inst.io.w_valid := io.ln_w_valid

  ln_inst.io.res_ready := adapter_inst.io.ln_ready

  // ========================================
  // TempAdapter 连接
  // ========================================
  adapter_inst.io.ln_in := ln_inst.io.res
  adapter_inst.io.ln_in_st := ln_inst.io.res_st
  adapter_inst.io.ln_in_addr := ln_inst.io.res_addr
  adapter_inst.io.ln_in_valid := ln_inst.io.res_valid
  adapter_inst.io.ln_in_last := ln_inst.io.res_last

  adapter_inst.io.cfg_seqlen := io.cfg_seqlen
  adapter_inst.io.cfg_prefill := io.cfg_prefill
  adapter_inst.io.cfg_valid := io.cfg_valid

  adapter_inst.io.data_out_ready := pipinst.io.data_ready

  // ========================================
  // Attention 流水线连接（保持不变）
  // ========================================
  pipinst.io.cfg_valid := io.cfg_valid
  pipinst.io.cfg_prefill := io.cfg_prefill
  pipinst.io.cfg_seqlen := io.cfg_seqlen
  pipinst.io.layer_st := io.layer_st
  pipinst.io.w_in := io.w_in

  pipinst.io.data_in_st := adapter_inst.io.data_out_st
  pipinst.io.data_in := adapter_inst.io.data_out
  pipinst.io.data_addr := adapter_inst.io.data_out_addr
  pipinst.io.data_valid := adapter_inst.io.data_out_valid
  pipinst.io.data_last := adapter_inst.io.data_out_last

  pipinst.io.res_ready := io.res_ready

  // ========================================
  // 输出连接
  // ========================================
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
