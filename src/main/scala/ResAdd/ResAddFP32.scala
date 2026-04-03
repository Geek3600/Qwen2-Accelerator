package ResAdd

import QuantCommon.Fp32VecAdd
import QuantCommon.Precision._
import ResAdd.FP32Param._
import chisel3._
import chisel3.util._

// ResAddFP32: 浮点版本残差连接模块
// 功能: 缓存原始 FP32 输入，与 attention 输出投影后的 FP32 数据相加
class ResAddFP32 extends Module {
  val io = IO(new Bundle() {
    val cfg_seqlen = Input(UInt(log2Up(MAX_SEQLEN).W))
    val cfg_prefill = Input(Bool())
    val cfg_valid = Input(Bool())

    val orig_in = Input(UInt(DATA_WIDTH.W))
    val orig_in_st = Input(Bool())
    val orig_in_addr = Input(UInt(log2Up(MEM_DEPTH).W))
    val orig_in_valid = Input(Bool())
    val orig_in_last = Input(Bool())
    val orig_ready = Output(Bool())

    val dm2_in = Input(UInt(DATA_WIDTH.W))
    val dm2_in_st = Input(Bool())
    val dm2_in_addr = Input(UInt(log2Up(MEM_DEPTH).W))
    val dm2_in_valid = Input(Bool())
    val dm2_in_last = Input(Bool())
    val dm2_ready = Output(Bool())

    val res = Output(UInt(DATA_WIDTH.W))
    val res_st = Output(Bool())
    val res_addr = Output(UInt(log2Up(MEM_DEPTH).W))
    val res_valid = Output(Bool())
    val res_last = Output(Bool())
    val res_ready = Input(Bool())
  })

  val mem = Module(new DataMem(MEM_DEPTH, DATA_WIDTH, 3))

  mem.io.w_st := io.orig_in_st
  mem.io.w_last := io.orig_in_last
  mem.io.w_data := io.orig_in
  mem.io.w_addr := io.orig_in_addr
  mem.io.w_valid := io.orig_in_valid

  io.orig_ready := mem.io.w_ready

  val idle :: reading :: Nil = Enum(2)
  val state = RegInit(idle)

  val is_idle = state === idle
  val is_reading = state === reading

  val fire = io.dm2_in_valid && io.res_ready
  val active = (is_reading || (is_idle && mem.io.r_ready)) && fire

  switch(state) {
    is(idle) {
      when(mem.io.r_ready && io.dm2_in_valid) {
        state := reading
      }
    }
    is(reading) {
      when(io.dm2_in_last && fire) {
        when(mem.io.r_ready && io.dm2_in_valid) {
          state := reading
        }.otherwise {
          state := idle
        }
      }
    }
  }

  mem.io.r_addr := io.dm2_in_addr
  mem.io.r_en := active
  mem.io.r_last := active && io.dm2_in_last

  val dm2_data_r = RegEnable(io.dm2_in, 0.U(DATA_WIDTH.W), active)
  val dm2_valid_r = RegNext(active, false.B)
  val dm2_st_r = RegEnable(io.dm2_in_st, false.B, active)
  val dm2_last_r = RegEnable(io.dm2_in_last, false.B, active)
  val dm2_addr_r = RegEnable(io.dm2_in_addr, 0.U(log2Up(MEM_DEPTH).W), active)

  val add = Module(new Fp32VecAdd)
  add.io.a := mem.io.r_data
  add.io.b := dm2_data_r

  io.res := add.io.out
  io.res_valid := dm2_valid_r
  io.res_st := dm2_st_r
  io.res_last := dm2_last_r
  io.res_addr := dm2_addr_r

  io.dm2_ready := (is_reading || (is_idle && mem.io.r_ready)) && io.res_ready
}

object ResAddFP32Gen extends App {
  emitVerilog(new ResAddFP32, Array("--target-dir", "generated"))
}
