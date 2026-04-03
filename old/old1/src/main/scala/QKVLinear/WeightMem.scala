package QKVLinear

import chisel3._
import chisel3.util._
import QKVLinear.Param._

// WeightMem: 片上权重存储模块
// 功能: 存储 QKVLinear 的权重矩阵 (768 × 2304)
// 容量: 49,152 × 288 位 = 14.2 Mb (约 1.68 MB for 8-bit, 6.72 MB for 32-bit)
//
// 存储器拆分策略:
// - 深度方向: 49,152 / 4,096 = 12 个 bank (适配 URAM 4K 深度限制)
// - 位宽方向: 288 / 72 = 4 个 slice (适配 URAM 72 位宽限制)
// - 总计: 12 × 4 = 48 个 URAM
//
// 地址映射:
// - addr[15:12]: bank 选择 (0-11)
// - addr[11:0]:  bank 内地址 (0-4095)
class WeightMem extends Module {
  val io = IO(new Bundle {
    // 初始化接口 (加载权重)
    val init_mode = Input(Bool())
    val init_addr = Input(UInt(log2Up(WMEM_DEPTH).W))
    val init_data = Input(UInt(WMEM_WIDTH.W))

    // 读取接口 (计算时使用)
    val read_en = Input(Bool())
    val read_addr = Input(UInt(log2Up(WMEM_DEPTH).W))
    val read_data = Output(UInt(WMEM_WIDTH.W))
  })

  // 存储器参数
  val NUM_BANKS = 12      // 深度方向分 12 个 bank
  val BANK_DEPTH = 4096   // 每个 bank 深度 4K (适配 URAM)
  val NUM_SLICES = 4      // 位宽方向分 4 个 slice
  val SLICE_WIDTH = 72    // 每个 slice 宽度 72 位 (适配 URAM)

  // 创建存储器阵列: 12 banks × 4 slices = 48 个 URAM
  val weight_banks = Seq.fill(NUM_BANKS)(
    Seq.fill(NUM_SLICES)(
      SyncReadMem(BANK_DEPTH, UInt(SLICE_WIDTH.W))
    )
  )

  // ========================================
  // 写入逻辑 (初始化模式)
  // ========================================

  // 地址解码
  val init_bank_sel = io.init_addr(15, 12)   // 高 4 位选择 bank (0-11)
  val init_bank_addr = io.init_addr(11, 0)   // 低 12 位是 bank 内地址 (0-4095)

  // 对每个 bank 进行条件写入
  for (b <- 0 until NUM_BANKS) {
    for (s <- 0 until NUM_SLICES) {
      when(io.init_mode && init_bank_sel === b.U) {
        weight_banks(b)(s).write(
          init_bank_addr,
          io.init_data((s+1)*SLICE_WIDTH-1, s*SLICE_WIDTH)
        )
      }
    }
  }

  // ========================================
  // 读取逻辑 (计算模式)
  // ========================================

  // 地址解码
  val read_bank_sel = io.read_addr(15, 12)
  val read_bank_addr = io.read_addr(11, 0)

  // 从所有 bank 读取 (只有选中的 bank 的数据会被使用)
  val read_datas = for (b <- 0 until NUM_BANKS) yield {
    val slices = for (s <- 0 until NUM_SLICES) yield {
      weight_banks(b)(s).read(read_bank_addr, io.read_en && !io.init_mode)
    }
    Cat(slices.reverse)  // 拼接 4 个 72 位 slice 为 288 位
  }

  // 使用 MuxLookup 选择正确的 bank
  // 注意: 需要 RegNext 因为 SyncReadMem 有一个周期的延迟
  io.read_data := MuxLookup(RegNext(read_bank_sel), 0.U)(
    (0 until NUM_BANKS).map(i => i.U -> read_datas(i))
  )
}
