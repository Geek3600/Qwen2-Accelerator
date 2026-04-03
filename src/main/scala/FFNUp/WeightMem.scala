package FFNUp

import chisel3._
import chisel3.util._
import FFNUp.Param._

// FFNUp 权重存储器
// 容量: 66,048 × 288 位 = 19.02 Mb
// 拆分: 17 banks × 4 slices = 68 个 URAM
class WeightMem extends Module {
  val io = IO(new Bundle {
    // 初始化接口
    val init_mode = Input(Bool())
    val init_addr = Input(UInt(log2Up(WMEM_DEPTH).W))
    val init_data = Input(UInt(WMEM_WIDTH.W))
    val init_wen = Input(Bool())

    // 读取接口
    val read_en = Input(Bool())
    val read_addr = Input(UInt(log2Up(WMEM_DEPTH).W))
    val read_data = Output(UInt(WMEM_WIDTH.W))
  })

  // 参数
  val NUM_BANKS = 17    // 深度方向分 17 个 bank (66048/4096 ≈ 17)
  val BANK_DEPTH = 4096
  val NUM_SLICES = 4    // 位宽方向分 4 个 slice (288/72 = 4)
  val SLICE_WIDTH = 72

  // 创建存储器阵列：17 banks × 4 slices = 68 个 URAM
  val weight_banks = Seq.fill(NUM_BANKS)(
    Seq.fill(NUM_SLICES)(
      SyncReadMem(BANK_DEPTH, UInt(SLICE_WIDTH.W))
    )
  )

  // 地址解码
  val bank_sel = io.init_addr(16, 12)  // 高 5 位选择 bank (0-16)
  val bank_addr = io.init_addr(11, 0)  // 低 12 位是 bank 内地址 (0-4095)

  // 写入（初始化模式）
  for (b <- 0 until NUM_BANKS) {
    for (s <- 0 until NUM_SLICES) {
      when(io.init_mode && io.init_wen && bank_sel === b.U) {
        weight_banks(b)(s).write(
          bank_addr,
          io.init_data((s+1)*SLICE_WIDTH-1, s*SLICE_WIDTH)
        )
      }
    }
  }

  // 读取（计算模式）
  val read_bank_sel = io.read_addr(16, 12)
  val read_bank_addr = io.read_addr(11, 0)

  val read_datas = for (b <- 0 until NUM_BANKS) yield {
    val slices = for (s <- 0 until NUM_SLICES) yield {
      weight_banks(b)(s).read(read_bank_addr, io.read_en && !io.init_mode)
    }
    Cat(slices.reverse)
  }

  io.read_data := MuxLookup(RegNext(read_bank_sel), 0.U)(
    (0 until NUM_BANKS).map(i => i.U -> read_datas(i))
  )
}
