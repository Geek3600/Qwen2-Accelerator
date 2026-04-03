package Load0

import Load0.Param._
import chisel3._
import chisel3.util._
class Load0 extends Module {

  val io = IO(new Bundle() {
    val cfg_seqlen = Input(UInt(log2Up(MAX_PREFILL).W))
    val cfg_prefill = Input(Bool())
    val cfg_valid = Input(Bool())

    val data_in = Input(UInt(MEMW.W))
    val data_in_ready = Input(Bool())
    val data_in_addr = Output(UInt(32.W))


    val data_out_st = Output(Bool())
    val data_out_last = Output(Bool())
    val data_out_ready = Input(Bool())
    val data_out = Output(UInt(MEMW.W))
    val data_out_valid = Output(Bool())
    val data_out_addr = Output(UInt(32.W))


  })

  val is_prefill = RegEnable(io.cfg_prefill, io.cfg_valid)
  val seqlen = RegEnable(io.cfg_seqlen, io.cfg_valid)

  val idle :: buzy :: Nil = Enum(2)
  val state = RegInit(idle)
  val is_idle = state === idle
  val is_buzy = state === buzy

  val fire = io.data_in_ready && io.data_out_ready
  dontTouch(fire)

  val vec_cnt = Wire(UInt(log2Up(VECNUM).W))
  val vec_last = vec_cnt === (VECNUM - 1).U
  vec_cnt := RegEnable(
    Mux(
      vec_last,
      0.U,
      vec_cnt + 1.U
    ),
    0.U,
     is_buzy
  )

  val prefill_cnt = Wire(UInt(log2Up(MAX_PREFILL).W))
  val prefill_last = prefill_cnt === seqlen
  prefill_cnt := RegEnable(
    Mux(
      prefill_last,
      0.U,
      prefill_cnt + 1.U
    ),
    0.U,
    vec_last && is_buzy && is_prefill
  )

  val batch_cnt = Wire(UInt(log2Up(BATCHSIZE).W))
  val batch_last = batch_cnt === (BATCHSIZE - 1).U
  batch_cnt := RegEnable(
    Mux(
      batch_last,
      0.U,
      batch_cnt + 1.U
    ),
    0.U,
    vec_last && is_buzy && !is_prefill
  )

  val idle_mux = Mux(fire,buzy,idle)
  val buzy_mux = Mux(vec_last && (is_prefill && prefill_last || !is_prefill && batch_last ),idle_mux,buzy)
  state := Mux(is_buzy,buzy_mux,idle_mux)


  io.data_in_addr := Mux(is_prefill,prefill_cnt*VECNUM.U + vec_cnt,batch_cnt*VECNUM.U + vec_cnt)


  io.data_out := io.data_in
  io.data_out_st := RegNext( is_buzy && vec_cnt === 0.U && prefill_cnt === 0.U && batch_cnt === 0.U,false.B)
  io.data_out_last := RegNext( is_buzy && vec_last && (is_prefill && prefill_last || !is_prefill && batch_last ),false.B)
  io.data_out_valid := RegNext(is_buzy,false.B)
  io.data_out_addr:= RegNext(Mux(is_prefill,prefill_cnt*VECNUM.U + vec_cnt,batch_cnt*VECNUM.U + vec_cnt))



}
object load0gen extends App {
  emitVerilog(new Load0, Array("--target-dir", "generated"))
}