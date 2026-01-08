error id: file://<WORKSPACE>/src/main/scala/SiLU/Log2.scala:`<none>`.
file://<WORKSPACE>/src/main/scala/SiLU/Log2.scala
empty definition using pc, found symbol in pc: `<none>`.
empty definition using semanticdb
empty definition using fallback
non-local guesses:
	 -chisel3/chisel3.
	 -chisel3/util/chisel3.
	 -chisel3.
	 -scala/Predef.chisel3.
offset: 38
uri: file://<WORKSPACE>/src/main/scala/SiLU/Log2.scala
text:
```scala
package SiLU
import chisel3._
import c@@hisel3.util._

class Log2(val V: Int, val DW: Int = 8, val FW: Int = 8) extends Module {
  val io = IO(new Bundle {
    val in  = Input(Vec(V, SInt((DW+FW).W)))
    val out = Output(Vec(V, SInt((DW+FW).W)))
  })
    val intWidth = DW.U // 整数部分位宽
    val fracWidth = FW.U   // 小数部分位宽
    val fixWidth = DW.U + FW.U // 固定点位宽

    for (i <- 0 until V) {

        val leading_one_pos = fixWidth - PriorityEncoder(Reverse(io.in(i).asUInt))

        val x_gt_one = (leading_one_pos >= fracWidth)

        val w = Mux(x_gt_one, leading_one_pos - fracWidth - 1.U, fracWidth - leading_one_pos - 1.U)

        val x = Mux(x_gt_one, io.in(i).asUInt >> w, io.in(i).asUInt << w).asUInt

        io.out(i) := Mux(x_gt_one, (w << fracWidth) + x - "b100000000".U, x - (w << fracWidth) - "b100000000".U).asSInt

        // printf(p"[LOG2] i=$i in=${Binary(io.in(i))} leading_one_pos=${leading_one_pos} x_gt_one=${x_gt_one} w=${w} x=${Binary(x)}\n")
    }
}
```


#### Short summary: 

empty definition using pc, found symbol in pc: `<none>`.