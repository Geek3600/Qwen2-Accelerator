//import chisel3._
//import chisel3.util._
//import DM.Param._
//import DM.DM
//import Load0.Param.MEMW
//import Load0.Load0
//import Softmax.Param.{MEM_WIDTH, WMEM_DEPTH, WMEM_WIDTH}
//import Softmax.SoftmaxPip
//class PipTop extends Module {
//val io = IO(new Bundle() {
//  val layer_st = Input(Bool())
//  val cfg_seqlen = Input(UInt(log2Up( MAX_SEQLEN).W))
//  val cfg_prefill = Input(Bool())
//  val cfg_valid = Input(Bool())
//
//
//  val w_in = Input(UInt(WMEM_WIDTH.W))
//  val w_addr = Output(UInt(log2Up(WMEM_DEPTH).W))
//
//
//
//  val data_in_st = Input(Bool())
//  val data_in = Input(UInt((2 * LOAD_VECNUM * DATAW).W))
//  val data_addr = Input(UInt(log2Up(HEAD_VECNUM / LOAD_VECNUM * BATCHSIZE).W))
//  val data_valid = Input(Bool())
//  val data_last = Input(Bool())
//  val data_ready = Output(Bool())
//
//
//  val res = Output(UInt(MEM_WIDTH.W))
//  val res_st = Output(Bool())
//  val res_addr = Output(UInt(log2Up(MEM_DEPTH).W))
//  val res_valid = Output(Bool())
//  val res_last = Output(Bool())
//  val res_ready = Input(Bool())
//
//})
//
//  val dm_inst = Module(new DM)
//  val softmax_inst = Module(new SoftmaxPip)
//
//
//
//  dm_inst.io.cfg_seqlen := io.cfg_seqlen
//  dm_inst.io.cfg_valid := io.cfg_valid
//  dm_inst.io.cfg_prefill := io.cfg_prefill
//
//  dm_inst.io.data_in_st := io.data_in_st
//  dm_inst.io.data_in := io.data_in
//  dm_inst.io.data_addr := io.data_addr
//  dm_inst.io.data_valid := io.data_valid
//  dm_inst.io.data_last := io.data_last
//
//  dm_inst.io.res_ready := softmax_inst.io.data_ready
//
//
//
//  softmax_inst.io.cfg_seqlen := io.cfg_seqlen
//  softmax_inst.io.cfg_valid := io.cfg_valid
//  softmax_inst.io.cfg_prefill := io.cfg_prefill
//
//  softmax_inst.io.layer_st := io.layer_st
//  softmax_inst.io.data_in_st := dm_inst.io.res_st
//  softmax_inst.io.data_in := dm_inst.io.res
//  softmax_inst.io.data_addr := dm_inst.io.res_addr
//  softmax_inst.io.data_valid := dm_inst.io.res_valid
//  softmax_inst.io.data_last := dm_inst.io.res_last
//
//  softmax_inst.io.w_in := io.w_in
//  io.w_addr:= softmax_inst.io.w_addr
//
//  softmax_inst.io.res_ready := io.res_ready
//
//
//
//
//  io.data_ready := dm_inst.io.data_ready
//
//
//  io.res := softmax_inst.io.res
//  io.res_st := softmax_inst.io.res_st
//  io.res_addr := softmax_inst.io.res_addr
//  io.res_valid := softmax_inst.io.res_valid
//  io.res_last := softmax_inst.io.res_last
//
//
//}
//
//class Top extends Module{
//  val io = IO(new Bundle() {
//    val layer_st = Input(Bool())
//    val cfg_seqlen = Input(UInt(log2Up(MAX_SEQLEN).W))
//    val cfg_prefill = Input(Bool())
//    val cfg_valid = Input(Bool())
//
//    val data_in = Input(UInt(MEMW.W))
//    val data_in_ready = Input(Bool())
//    val data_in_addr = Output(UInt(32.W))
//
//    val w_in = Input(UInt(WMEM_WIDTH.W))
//    val w_addr = Output(UInt(log2Up(WMEM_DEPTH).W))
//
//
//    val res = Output(UInt(MEM_WIDTH.W))
//    val res_st = Output(Bool())
//    val res_addr = Output(UInt(log2Up(MEM_DEPTH).W))
//    val res_valid = Output(Bool())
//    val res_last = Output(Bool())
//    val res_ready = Input(Bool())
//
//  })
//
//  val load_inst = Module(new Load0)
//
//  val pipinst = Module(new PipTop)
//
//
//  load_inst.io.cfg_valid := io.cfg_valid
//  load_inst.io.cfg_seqlen := io.cfg_seqlen
//  load_inst.io.cfg_prefill := io.cfg_prefill
//  load_inst.io.data_in := io.data_in
//  load_inst.io.data_in_ready := io.data_in_ready
//  load_inst.io.data_out_ready := pipinst.io.data_ready
//
//  pipinst.io.cfg_valid := io.cfg_valid
//  pipinst.io.cfg_prefill := io.cfg_prefill
//  pipinst.io.cfg_seqlen := io.cfg_seqlen
//  pipinst.io.layer_st := io.layer_st
//  pipinst.io.w_in := io.w_in
//
//  pipinst.io.data_in_st := load_inst.io.data_out_st
//  pipinst.io.data_in := load_inst.io.data_out
//  pipinst.io.data_addr := load_inst.io.data_out_addr
//  pipinst.io.data_valid := load_inst.io.data_out_valid
//  pipinst.io.data_last := load_inst.io.data_out_last
//
//  pipinst.io.res_ready := io.res_ready
//
//
//  io.data_in_addr := load_inst.io.data_in_addr
//  io.w_addr := pipinst.io.w_addr
//  io.res := pipinst.io.res
//  io.res_st := pipinst.io.res_st
//  io.res_addr := pipinst.io.res_addr
//  io.res_valid := pipinst.io.res_valid
//  io.res_last := pipinst.io.res_last
//
//
//
//
//
//
//
//
//
//}
//object Top extends App {
//  emitVerilog(new Top, Array("--target-dir", "generated"))
//}
