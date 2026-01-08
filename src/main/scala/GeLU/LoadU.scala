// for 0 to 768 / 12
//  for 0 to 32

package GeLU
import chisel3._
import chisel3.util._
import Common.Param._

class LoadU extends Module {
  val io = IO(new Bundle() {
    val data_in = Input(UInt(MEM_WIDTH.W))
    val data_in_addr = Output(UInt(log2Up(MEM_DEPTH).W))
    val data_in_last = Output(Bool())
    val data_in_ready = Input(Bool())

    val data_out = Output(UInt(MEM_WIDTH.W))
    val data_out_valid = Output(Bool())
    val data_out_ready = Input(Bool()) // 如果数据可以输出，需要一直保持有效
    val data_out_start = Output(Bool())
  })
  val batchsize_cnt = Wire(UInt(log2Up(BATCHSIZE).W))
  val batchsize_last = batchsize_cnt===(BATCHSIZE - 1).U

  val vector_num = COL_D/V
  val vector_cnt = Wire(UInt(log2Up(vector_num).W))
  val vector_last = vector_cnt === (vector_num - 1).U

  val idle :: busy :: Nil = Enum(2)
  val state = RegInit(idle)
  val is_idle = state === idle
  val is_busy = state === busy
  val idle_mux = Mux(
    io.data_in_ready && io.data_out_ready,
    busy,
    idle
  )
  val busy_mux = Mux(
    vector_last && batchsize_last,
    idle,
    busy
  )
 state := Mux(is_idle, idle_mux, busy_mux)
// 内层循环
  batchsize_cnt:= RegEnable(
    Mux(
      batchsize_last,
      0.U,
      batchsize_cnt + 1.U
    ),
    0.U,
    is_busy
  )

  // 外层循环
  vector_cnt := RegEnable(
    Mux(
      vector_last,
      0.U,
      vector_cnt + 1.U
    ),
    0.U,
    is_busy && batchsize_last
  )

  val addr = vector_cnt + batchsize_cnt * vector_num.U
  io.data_in_addr := addr
  io.data_in_last := batchsize_last && vector_last

  io.data_out := io.data_in
  io.data_out_valid := RegNext(is_busy, false.B)

  val state_next = RegNext(state, idle)
  io.data_out_start := is_busy && state_next === idle
//   printf(p"[LoadU] state = $state, data_in_ready = ${io.data_in_ready}, data_out_ready = ${io.data_out_ready}, data_in = ${Binary(io.data_in)}\n")
//   printf(p"[LoadU] state = $state, data_in = ${Binary(io.data_in)}, batchsize_cnt = $batchsize_cnt, vector_cnt = $vector_cnt, addr = $addr\n")
}   
