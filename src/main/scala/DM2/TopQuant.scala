package DM2

import DM2.Param._
import DM2.QuantParam._
import QuantCommon.Fp32QuantizeToUInt8Vec
import QuantCommon.Precision._
import chisel3._
import chisel3.util._

class DM2Quant extends Module {
  val io = IO(new Bundle() {
    val cfg_seqlen = Input(UInt(log2Up(MAX_SEQLEN).W))
    val cfg_prefill = Input(Bool())
    val cfg_valid = Input(Bool())
    val cfg_single_query = Input(Bool())

    val ctx_inv_scale = Input(UInt(FP32_WIDTH.W))
    val ctx_zero_point = Input(UInt(UINT8_WIDTH.W))
    val out_inv_scale = Input(UInt(FP32_WIDTH.W))

    val data_in_v_st = Input(Bool())
    val data_in_v = Input(UInt((HEAD_VECNUM * DATAW).W))
    val data_in_v_addr = Input(UInt(log2Up(MEM_DEPTH).W))
    val data_in_v_valid = Input(Bool())
    val data_in_v_last = Input(Bool())
    val data_in_v_ready = Output(Bool())

    val data_in_ctx_st = Input(Bool())
    val data_in_ctx = Input(UInt(CTX_FP32_WIDTH.W))
    val data_in_ctx_addr = Input(UInt(log2Up(MEM_DEPTH).W))
    val data_in_ctx_valid = Input(Bool())
    val data_in_ctx_last = Input(Bool())
    val data_in_ctx_ready = Output(Bool())

    val res = Output(UInt((HEAD_VECNUM * DATAW).W))
    val res_st = Output(Bool())
    val res_addr = Output(UInt(log2Up(BATCHSIZE).W))
    val res_valid = Output(Bool())
    val res_last = Output(Bool())
    val res_ready = Input(Bool())
  })

  val ctxQuant = Module(new Fp32QuantizeToUInt8Vec(MAX_SEQLEN))
  ctxQuant.io.in := io.data_in_ctx
  ctxQuant.io.invScale := io.ctx_inv_scale
  ctxQuant.io.zeroPoint := io.ctx_zero_point

  val loaduInst = Module(new LoadUnit)
  val dmInst = Module(new DM2CUQuant)
  val vmemInst = Module(new DataMem(MEM_DEPTH, HEAD_VECNUM * DATAW))
  val ctxmemInst = Module(new DataMem(MEM_DEPTH, MAX_SEQLEN * DATAW))
  val suInst = Module(new StoreUnit)

  vmemInst.io.w_st := io.data_in_v_st
  vmemInst.io.w_last := io.data_in_v_last
  vmemInst.io.w_data := io.data_in_v
  vmemInst.io.w_addr := io.data_in_v_addr
  vmemInst.io.w_valid := io.data_in_v_valid
  vmemInst.io.r_en := loaduInst.io.data_in_v_en
  vmemInst.io.r_last := loaduInst.io.data_in_v_last
  vmemInst.io.r_addr := loaduInst.io.data_in_v_addr

  ctxmemInst.io.w_st := io.data_in_ctx_st
  ctxmemInst.io.w_last := io.data_in_ctx_last
  ctxmemInst.io.w_data := ctxQuant.io.out
  ctxmemInst.io.w_addr := io.data_in_ctx_addr
  ctxmemInst.io.w_valid := io.data_in_ctx_valid
  ctxmemInst.io.r_en := loaduInst.io.data_in_ctx_en
  ctxmemInst.io.r_last := loaduInst.io.data_in_ctx_last
  ctxmemInst.io.r_addr := loaduInst.io.data_in_ctx_addr

  loaduInst.io.cfg_valid := io.cfg_valid
  loaduInst.io.cfg_prefill := io.cfg_prefill
  loaduInst.io.cfg_seqlen := io.cfg_seqlen
  loaduInst.io.cfg_single_query := io.cfg_single_query
  loaduInst.io.data_in_v := vmemInst.io.r_data
  loaduInst.io.data_in_v_ready := vmemInst.io.r_ready
  loaduInst.io.data_in_ctx := ctxmemInst.io.r_data
  loaduInst.io.data_in_ctx_ready := ctxmemInst.io.r_ready
  loaduInst.io.data_out_ready := io.res_ready

  dmInst.io.cfg_seqlen := io.cfg_seqlen
  dmInst.io.cfg_valid := io.cfg_valid
  dmInst.io.cfg_prefill := io.cfg_prefill
  dmInst.io.cfg_single_query := io.cfg_single_query
  dmInst.io.out_inv_scale := io.out_inv_scale
  dmInst.io.data_in_v := loaduInst.io.data_out_v
  dmInst.io.data_in_v_valid := loaduInst.io.data_out_v_valid
  dmInst.io.data_in_ctx := loaduInst.io.data_out_ctx
  dmInst.io.data_in_ctx_valid := loaduInst.io.data_out_ctx_valid

  suInst.io.cfg_seqlen := io.cfg_seqlen
  suInst.io.cfg_valid := io.cfg_valid
  suInst.io.cfg_prefill := io.cfg_prefill
  suInst.io.cfg_single_query := io.cfg_single_query
  suInst.io.data_in := dmInst.io.data_out
  suInst.io.data_in_valid := dmInst.io.data_out_valid

  io.data_in_v_ready := vmemInst.io.w_ready
  io.data_in_ctx_ready := ctxmemInst.io.w_ready
  io.res := suInst.io.data_out
  io.res_st := loaduInst.io.data_out_start
  io.res_addr := suInst.io.data_out_addr
  io.res_valid := suInst.io.data_out_valid
  io.res_last := suInst.io.data_out_last
}

object DM2QuantGen extends App {
  emitVerilog(new DM2Quant, Array("--target-dir", "generated"))
}
