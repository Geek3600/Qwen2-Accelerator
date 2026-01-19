package Softmax
import Param._
import chisel3._
import chisel3.util._

class StoreU extends Module {
  val io = IO (new Bundle() {
    val data_in = Input(UInt(MEM_WIDTH.W))
    val data_in_valid = Input(Bool())

    val data_out = Output(UInt(MEM_WIDTH.W))
    val data_out_addr = Output(UInt(log2Up(MEM_DEPTH).W))
    val data_out_valid = Output(Bool())
    val data_out_last = Output(Bool())
  })

  val batchsize_cnt = Wire(UInt(log2Up(BATCHSIZE).W)) //32
  val batchsize_last =  batchsize_cnt === (BATCHSIZE - 1).U

// 一层循环
//for vector in 0..31:
  batchsize_cnt := RegEnable(
    Mux(
      batchsize_last,
      0.U,
      batchsize_cnt + 1.U
    ),
    0.U,
    io.data_in_valid
  )

  val out_addr = batchsize_cnt
  val out_valid = io.data_in_valid && (~batchsize_last)
  val out_last = batchsize_last

  io.data_out := io.data_in
  io.data_out_valid := out_valid
  io.data_out_addr := RegNext(out_addr)
  io.data_out_last := RegNext(out_last, false.B)
//   printf(p"[StoreU] data_out_valid = ${io.data_out_valid}, batchsize_cnt = $batchsize_cnt, out_addr = $out_addr, data_out = ${Binary(io.data_out)}\n \n")
}

object StoreUGen extends App {
  emitVerilog(new StoreU, Array("--target-dir", "generated"))
}
