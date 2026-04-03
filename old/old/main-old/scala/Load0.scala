package Load0


import LayerNorm.Param._
import chisel3._
import chisel3.util._
class Load0 extends Module {

  val io = IO(new Bundle() {


    val data_in = Input(UInt(MEM_WIDTH.W))
    val data_in_ready = Input(Bool())
    val data_in_addr = Output(UInt(32.W))


    val data_out_st = Output(Bool())
    val data_out_last = Output(Bool())
    val data_out_ready = Input(Bool())
    val data_out = Output(UInt(MEM_WIDTH.W))
    val data_out_valid = Output(Bool())
    val data_out_addr = Output(UInt(32.W))


  })



  val idle :: buzy :: Nil = Enum(2)
  val state = RegInit(idle)
  val is_idle = state === idle
  val is_buzy = state === buzy

  val fire = io.data_in_ready && io.data_out_ready
  dontTouch(fire)


  val vector_num = MEM_WIDTH/DATAW
  val vec_cnt =Wire(UInt(log2Up(vector_num).W))
  val vec_last = vec_cnt === (vector_num - 1).U
  vec_cnt := RegEnable(
    Mux(
      vec_last,
      0.U,
      vec_cnt + 1.U
    ),
    0.U,
    is_buzy
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
    vec_last && is_buzy
  )

  val idle_mux = Mux(fire,buzy,idle)
  val buzy_mux = Mux(vec_last &&  batch_last ,idle_mux,buzy)
  state := Mux(is_buzy,buzy_mux,idle_mux)


  io.data_in_addr := batch_cnt*vector_num.U + vec_cnt


  io.data_out := io.data_in
  io.data_out_st := RegNext( is_buzy && vec_cnt === 0.U && batch_cnt === 0.U,false.B)
  io.data_out_last := RegNext( is_buzy && vec_last &&  batch_last ,false.B)
  io.data_out_valid := RegNext(is_buzy,false.B)
  io.data_out_addr:= RegNext(batch_cnt*vector_num.U + vec_cnt)



}
object load0gen extends App {
  emitVerilog(new Load0, Array("--target-dir", "generated"))
}