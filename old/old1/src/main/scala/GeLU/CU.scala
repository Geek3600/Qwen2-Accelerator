package GeLU

import GeLU.Param._
import chisel3._
import chisel3.util._

// GELU 激活函数计算单元
// 使用查找表（LUT）实现 GELU 近似
class CU extends Module {
  val io = IO(new Bundle() {
    val data_in = Input(UInt(MEM_WIDTH.W))
    val data_in_valid = Input(Bool())

    val data_out = Output(UInt(MEM_WIDTH.W))
    val data_out_valid = Output(Bool())
  })

  // GELU 查找表（256 项，覆盖 -128 到 127）
  // GELU(x) ≈ x * sigmoid(1.702 * x)
  // 对于 INT8，我们使用简化的近似
  val gelu_lut = VecInit(Seq.tabulate(256) { i =>
    val x = (i - 128).toDouble
    val gelu_val = if (x < -10) {
      0.0
    } else if (x > 10) {
      x
    } else {
      // 简化的 GELU 近似：x * sigmoid(1.702 * x)
      val sigmoid = 1.0 / (1.0 + math.exp(-1.702 * x))
      x * sigmoid
    }
    // 转换回 INT8 范围并饱和
    val result = math.round(gelu_val).toInt
    val saturated = if (result > 127) 127 else if (result < -128) -128 else result
    (saturated & 0xFF).U(8.W)
  })

  // 将输入拆分为 12 个 8-bit 元素
  val in_elems = io.data_in.asTypeOf(Vec(VECNUM, UInt(DATAW.W)))
  val out_elems = Wire(Vec(VECNUM, UInt(DATAW.W)))

  // 对每个元素应用 GELU
  for (i <- 0 until VECNUM) {
    out_elems(i) := gelu_lut(in_elems(i))
  }

  // 输出打一拍
  io.data_out := RegNext(out_elems.asUInt)
  io.data_out_valid := RegNext(io.data_in_valid, false.B)
}
