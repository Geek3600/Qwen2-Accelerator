package ResAdd2

import QuantCommon.Fp32VecAdd
import ResAdd2.FP32Param._
import chisel3._
import chisel3.util._

// ResAdd2FP32: FFN 后浮点残差连接
class ResAdd2FP32 extends Module {
  val io = IO(new Bundle() {
    val cfg_seqlen = Input(UInt(log2Up(MAX_SEQLEN).W))
    val cfg_prefill = Input(Bool())
    val cfg_valid = Input(Bool())

    val s7_in = Input(UInt(DATA_WIDTH.W))
    val s7_in_st = Input(Bool())
    val s7_in_addr = Input(UInt(log2Up(MEM_DEPTH).W))
    val s7_in_valid = Input(Bool())
    val s7_in_last = Input(Bool())
    val s7_ready = Output(Bool())

    val ffn_in = Input(UInt(DATA_WIDTH.W))
    val ffn_in_st = Input(Bool())
    val ffn_in_addr = Input(UInt(log2Up(MEM_DEPTH).W))
    val ffn_in_valid = Input(Bool())
    val ffn_in_last = Input(Bool())
    val ffn_ready = Output(Bool())

    val res = Output(UInt(DATA_WIDTH.W))
    val res_st = Output(Bool())
    val res_addr = Output(UInt(log2Up(MEM_DEPTH).W))
    val res_valid = Output(Bool())
    val res_last = Output(Bool())
    val res_ready = Input(Bool())
  })

  val mem = Module(new DataMem(MEM_DEPTH, DATA_WIDTH, 3))

  mem.io.w_st := io.s7_in_st
  mem.io.w_last := io.s7_in_last
  mem.io.w_data := io.s7_in
  mem.io.w_addr := io.s7_in_addr
  mem.io.w_valid := io.s7_in_valid

  io.s7_ready := mem.io.w_ready

  val idle :: reading :: Nil = Enum(2)
  val state = RegInit(idle)

  val is_idle = state === idle
  val is_reading = state === reading

  val fire = io.ffn_in_valid && io.res_ready
  val active = (is_reading || (is_idle && mem.io.r_ready)) && fire

  switch(state) {
    is(idle) {
      when(mem.io.r_ready && io.ffn_in_valid) {
        state := reading
      }
    }
    is(reading) {
      when(io.ffn_in_last && fire) {
        when(mem.io.r_ready && io.ffn_in_valid) {
          state := reading
        }.otherwise {
          state := idle
        }
      }
    }
  }

  mem.io.r_addr := io.ffn_in_addr
  mem.io.r_en := active
  mem.io.r_last := active && io.ffn_in_last

  val ffn_data_r = RegEnable(io.ffn_in, 0.U(DATA_WIDTH.W), active)
  val ffn_valid_r = RegNext(active, false.B)
  val ffn_st_r = RegEnable(io.ffn_in_st, false.B, active)
  val ffn_last_r = RegEnable(io.ffn_in_last, false.B, active)
  val ffn_addr_r = RegEnable(io.ffn_in_addr, 0.U(log2Up(MEM_DEPTH).W), active)

  val add = Module(new Fp32VecAdd)
  add.io.a := mem.io.r_data
  add.io.b := ffn_data_r

  io.res := add.io.out
  io.res_valid := ffn_valid_r
  io.res_st := ffn_st_r
  io.res_last := ffn_last_r
  io.res_addr := ffn_addr_r

  io.ffn_ready := (is_reading || (is_idle && mem.io.r_ready)) && io.res_ready
}

object ResAdd2FP32Gen extends App {
  emitVerilog(new ResAdd2FP32, Array("--target-dir", "generated"))
}
