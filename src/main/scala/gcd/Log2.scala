package log2
import chisel3._
import chisel3.util._

class Log2(val V: Int, val DW: Int = 16, val FW: Int = 8) extends Module {
  val io = IO(new Bundle {
    val in  = Input(Vec(V, SInt(DW.W)))
    val out = Output(Vec(V, SInt(DW.W)))
  })
    val intWidth = (DW - FW).U // 整数部分位宽
    val fracWidth = FW.U   // 小数部分位宽

    for (i <- 0 until V) {

        val leading_one_pos = DW.U - PriorityEncoder(Reverse(io.in(i).asUInt))

        val x_gt_one = (leading_one_pos >= fracWidth)

        val w = Mux(x_gt_one, leading_one_pos - fracWidth - 1.U, fracWidth - leading_one_pos - 1.U)

        val x = Mux(x_gt_one, io.in(i).asUInt >> w, io.in(i).asUInt << w).asUInt

        io.out(i) := Mux(x_gt_one, (w << fracWidth) + x - "b100000000".U, x - (w << fracWidth) - "b100000000".U).asSInt

        // printf(p"[LOG2] i=$i in=${Binary(io.in(i))} leading_one_pos=${leading_one_pos} x_gt_one=${x_gt_one} w=${w} x=${Binary(x)}\n")
    }
}