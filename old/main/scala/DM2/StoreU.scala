package DM2

import DM2.Param._
import chisel3._
import chisel3.util._
class StoreU extends Module{
  val io = IO(new Bundle() {
    val cfg_seqlen = Input(UInt(log2Up(MAX_PREFILL).W))
    val cfg_prefill = Input(Bool()) //prefill 长度减去1
    val cfg_valid = Input(Bool())

    val data_in = Input(UInt((HEAD_VECNUM * DATAW).W))
    val data_in_valid = Input(Bool())

    val data_out = Output(UInt((HEAD_VECNUM * DATAW).W))
    val data_out_addr = Output(UInt(log2Up(MEM_DEPTH).W))
    val data_out_valid = Output(Bool())
    val data_out_last = Output(Bool())
  })

  val prefill = RegEnable(io.cfg_prefill, io.cfg_valid)
  val seqlen = RegEnable(io.cfg_seqlen, io.cfg_valid)

  val prefill_cnt = Wire(UInt(log2Up(MAX_PREFILL).W))
  val prefill_last = prefill_cnt === seqlen
  prefill_cnt := RegEnable(
    Mux(
      prefill_last,
      0.U,
      prefill_cnt + 1.U
    ),
    0.U,
    prefill && io.data_in_valid
  )

  val batch_cnt = Wire(UInt(log2Up(BATCHSIZE).W))
  val batch_last = batch_cnt === (BATCHSIZE - 1).U
  batch_cnt := RegEnable(
    Mux(
      batch_last,
      0.U,
      batch_cnt + 1.U
    ),
    !prefill && io.data_in_valid
  )

  io.data_out := io.data_in
  io.data_out_addr := Mux(prefill,prefill_cnt ,batch_cnt)
  io.data_out_valid := io.data_in_valid
  io.data_out_last := (prefill && prefill_last || !prefill && batch_last) && io.data_in_valid




}
