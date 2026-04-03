package DM

import DM.Param._
import chisel3._
import chisel3.util._

class StoreU extends Module {
  val io = IO(new Bundle() {
    val cfg_seqlen = Input(UInt(log2Up(MAX_PREFILL).W))
    val cfg_prefill = Input(Bool())
    val cfg_valid = Input(Bool())


    val data_in = Input(UInt((MAX_SEQLEN*DATAW).W))
    val data_in_valid = Input(Bool())

    val data_out = Output(UInt((MAX_SEQLEN*DATAW).W))
    val data_out_addr = Output(UInt(log2Up(BATCHSIZE).W))
    val data_out_valid = Output(Bool())
    val data_out_last = Output(Bool())
  })

  val seqlen = RegEnable(io.cfg_seqlen,io.cfg_valid)
  val is_prefill = RegEnable(io.cfg_prefill,io.cfg_valid)
  val batch_cnt = Wire(UInt(log2Up(math.max(BATCHSIZE,MAX_PREFILL)).W))//prefill的时候是prelen，decode的时候是batchsize
  val batch_last = batch_cnt === Mux(is_prefill,seqlen, (BATCHSIZE - 1).U)
  batch_cnt := RegEnable(
    Mux(
      batch_last,
      0.U,
      batch_cnt +1.U
    ),
    0.U,
    io.data_in_valid
  )

  io.data_out := io.data_in
  io.data_out_addr := batch_cnt
  io.data_out_valid := io.data_in_valid
  io.data_out_last := batch_last && io.data_in_valid


//  val seqlen = Wire(UInt(log2Up(MAX_SEQLEN).W))
//  val prelen = Wire(UInt(log2Up(MAX_PREFILL).W))
//  val data_cnt = Wire(UInt(log2Up(MAX_SEQLEN*2*BATCHSIZE).W))
////  val q_cnt = Wire(UInt(log2Up(MAX_PREFILL).W))
//
//  val prefill = seqlen === prelen
//  val decode = seqlen > prefill
//
//  val prefill_num = RegNext((prelen + 1.U)*(prelen + 1.U))*BATCHSIZE.U
//
//  val decode_num = (seqlen + 1.U)*BATCHSIZE.U
//  val data_last = data_cnt === (Mux(prefill,prefill_num,decode_num) - 1.U)
//
//  data_cnt := RegEnable(
//    Mux(
//      data_last,
//      0.U,
//      data_cnt + 1.U
//    ),
//    0.U,
//    io.data_in_valid
//  )
//
//  prelen := RegEnable(io.data_in,io.data_in_valid)
//  seqlen := RegEnable(
//    Mux(
//      io.cfg_valid,
//      io.cfg_seqlen,
//      seqlen + 1.U
//    ),
//    0.U,
//    io.cfg_valid ||  (io.data_in_valid && data_last)
//  )
//
//  io.data_out := io.data_in
//  io.data_out_valid := io.data_in_valid
//  io.data_out_last := data_last
//  io.data_out_addr := data_cnt

}
