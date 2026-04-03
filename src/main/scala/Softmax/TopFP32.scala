package Softmax

import chisel3._
import chisel3.util._

class SoftmaxPipFP32 extends Module {
  import FP32Param._

  val io = IO(new Bundle() {
    val cfg_seqlen = Input(UInt(log2Up(SEQ_LEN).W))
    val cfg_prefill = Input(Bool())
    val cfg_valid = Input(Bool())
    val cfg_single_query = Input(Bool())

    val layer_st = Input(Bool())
    val data_in_st = Input(Bool())
    val data_in = Input(UInt(MEM_WIDTH.W))
    val data_addr = Input(UInt(log2Up(MEM_DEPTH).W))
    val data_valid = Input(Bool())
    val data_last = Input(Bool())
    val data_ready= Output(Bool())

    val w_in = Input(UInt(WMEM_WIDTH.W))
    val w_addr = Output(UInt(log2Up(WMEM_DEPTH).W))

    val res = Output(UInt(MEM_WIDTH.W))
    val res_st = Output(Bool())
    val res_addr = Output(UInt(log2Up(MEM_DEPTH).W))
    val res_valid = Output(Bool())
    val res_last = Output(Bool())
    val res_ready = Input(Bool())
  })

  val memInst = Module(new DataMem(MEM_DEPTH, MEM_WIDTH))
  val luInst = Module(new LoadUnitFP32)
  val lwInst = Module(new LoadWeight)
  val cuInst = Module(new CUFP32)
  val suInst = Module(new StoreUnitFP32)
  val isSingleQuery = RegEnable(io.cfg_single_query, false.B, io.cfg_valid)

  memInst.io.w_st := Mux(isSingleQuery, false.B, io.data_in_st)
  memInst.io.w_last := Mux(isSingleQuery, false.B, io.data_last)
  memInst.io.w_data := io.data_in
  memInst.io.w_addr := io.data_addr
  memInst.io.w_valid := Mux(isSingleQuery, false.B, io.data_valid)
  memInst.io.r_last := luInst.io.data_in_last
  memInst.io.r_addr := luInst.io.data_in_addr

  luInst.io.cfg_seqlen := io.cfg_seqlen
  luInst.io.cfg_prefill := io.cfg_prefill
  luInst.io.cfg_valid := io.cfg_valid
  luInst.io.cfg_single_query := io.cfg_single_query
  luInst.io.data_in := memInst.io.r_data
  luInst.io.data_in_ready := memInst.io.r_ready
  luInst.io.data_out_ready := io.res_ready

  cuInst.io.data_in := Mux(isSingleQuery, io.data_in, luInst.io.data_out)
  cuInst.io.data_in_valid := Mux(isSingleQuery, io.data_valid, luInst.io.data_out_valid)
  cuInst.io.data_in_w := Mux(isSingleQuery, Fill(V, 1.U(1.W)), lwInst.io.data_out)
  cuInst.io.data_in_w_valid := Mux(isSingleQuery, io.data_valid, lwInst.io.data_out_valid)

  suInst.io.cfg_seqlen := io.cfg_seqlen
  suInst.io.cfg_prefill := io.cfg_prefill
  suInst.io.cfg_valid := io.cfg_valid
  suInst.io.cfg_single_query := io.cfg_single_query
  suInst.io.data_in := cuInst.io.data_out
  suInst.io.data_in_valid := cuInst.io.data_out_valid

  lwInst.io.cfg_seqlen := io.cfg_seqlen
  lwInst.io.cfg_prefill := io.cfg_prefill
  lwInst.io.cfg_valid := io.cfg_valid
  lwInst.io.cfg_single_query := io.cfg_single_query
  lwInst.io.data_in := io.w_in
  lwInst.io.st := io.layer_st

  io.data_ready := Mux(isSingleQuery, io.res_ready, memInst.io.w_ready)
  io.w_addr := lwInst.io.data_in_addr
  io.res := suInst.io.data_out
  io.res_st := Mux(isSingleQuery, io.data_in_st, luInst.io.data_out_start)
  io.res_addr := suInst.io.data_out_addr
  io.res_valid := suInst.io.data_out_valid
  io.res_last := suInst.io.data_out_last
}

object SoftmaxPipFP32Gen extends App {
  emitVerilog(new SoftmaxPipFP32, Array("--target-dir", "generated"))
}
