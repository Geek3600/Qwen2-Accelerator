import LayerNorm.Param._
import LayerNorm.PipLine
import Load0.Load0
import chisel3._
import chisel3.util._

class Top extends Module {
  val io = IO(new Bundle() {
    val data_in = Input(UInt(MEM_WIDTH.W))
    val data_in_ready = Input(Bool())
    val data_in_addr = Output(UInt(32.W))

    val w_in = Input(UInt(MEM_WIDTH.W))
    val w_valid = Input(Bool())


    val res = Output(UInt(MEM_WIDTH.W))
    val res_st = Output(Bool())
    val res_addr = Output(UInt(log2Up(MEM_DEPTH).W))
    val res_valid = Output(Bool())
    val res_last = Output(Bool())
    val res_ready = Input(Bool())
  })

  val pipline_ins = Module(new PipLine)
  val load_inst = Module(new Load0)

  load_inst.io.data_in := io.data_in
  load_inst.io.data_in_ready := io.data_in_ready
  load_inst.io.data_out_ready := pipline_ins.io.data_ready




  pipline_ins.io.data_in_st := load_inst.io.data_out_st
  pipline_ins.io.data_in := load_inst.io.data_out
  pipline_ins.io.data_addr := load_inst.io.data_out_addr
  pipline_ins.io.data_valid := load_inst.io.data_out_valid
  pipline_ins.io.data_last := load_inst.io.data_out_last


  pipline_ins.io.w_in := io.w_in
  pipline_ins.io.w_valid := io.w_valid

  pipline_ins.io.res_ready := io.res_ready


  io.data_in_addr := load_inst.io.data_in_addr
  io.res := pipline_ins.io.res
  io.res_st := pipline_ins.io.res_st
  io.res_addr := pipline_ins.io.res_addr
  io.res_valid := pipline_ins.io.res_valid
  io.res_last := pipline_ins.io.res_last



}
object TopGen extends App {
  emitVerilog(new Top, Array("--target-dir", "generated"))
}