package QKVLinear

import QKVLinear.Param._
import chisel3._
import chisel3.util._

// LoadW: 权重加载和读取模块
// 功能:
// - 初始化模式: 从外部加载权重到片上 WeightMem
// - 计算模式: 从片上 WeightMem 读取权重供计算使用
class LoadW extends Module {
  val io = IO(new Bundle() {
    // 模式控制
    val init_mode = Input(Bool())  // true: 初始化模式, false: 计算模式

    // 初始化接口 (从外部加载权重)
    val init_data = Input(UInt(WMEM_WIDTH.W))
    val init_addr = Output(UInt(log2Up(WMEM_DEPTH).W))

    // 计算接口
    val update = Input(Bool())
    val st = Input(Bool())

    // 输出到 CU
    val data_out = Output(UInt(WMEM_WIDTH.W))
    val data_out_valid = Output(Bool())
  })

  // ========================================
  // 实例化片上权重存储器
  // ========================================
  val weight_mem = Module(new WeightMem)

  // ========================================
  // 地址生成逻辑
  // ========================================

  // 0..11: 每个小块中的12行权重，每行36个权重
  val row_cnt = Wire(UInt(log2Up(ROW).W))
  val row_last = row_cnt === (ROW - 1).U

  // 0..63: 权重矩阵的行分块，768/12=64
  val rowblock_cnt = Wire(UInt(log2Up(ROWBLOCK).W))
  val rowblock_last = rowblock_cnt === (ROWBLOCK - 1).U

  // 0..63: 权重矩阵的列分块，2304/36=64
  val colblock_cnt = Wire(UInt(log2Up(COLBLOCK).W))
  val colblock_last = colblock_cnt === (COLBLOCK - 1).U

  // 状态机
  val st_idle :: st1 :: st2 :: st_load :: Nil = Enum(4)
  val state = RegInit(st_idle)

  val idle_mux = Mux(io.st, st1, Mux(io.update, st_load, st_idle))
  val st1_mux = Mux(row_last, st2, st1)
  val st2_mux = Mux(row_last, st_idle, st2)
  val load_mux = Mux(row_last, Mux(io.update, st_load, st_idle), st_load)

  state := MuxLookup(state, st_idle)(
    List(
      st_idle -> idle_mux,
      st1 -> st1_mux,
      st2 -> st2_mux,
      st_load -> load_mux
    )
  )

  val loading = state =/= st_idle

  // 计数器更新
  row_cnt := RegEnable(
    Mux(row_last, 0.U, row_cnt + 1.U),
    0.U,
    loading
  )

  rowblock_cnt := RegEnable(
    Mux(rowblock_last, 0.U, rowblock_cnt + 1.U),
    0.U,
    row_last
  )

  colblock_cnt := RegEnable(
    Mux(colblock_last, 0.U, colblock_cnt + 1.U),
    0.U,
    row_last && rowblock_last
  )

  // 地址计算
  val addr = (row_cnt + rowblock_cnt * ROW.U) * COLBLOCK.U + colblock_cnt

  // ========================================
  // 连接 WeightMem
  // ========================================

  weight_mem.io.init_mode := io.init_mode
  weight_mem.io.init_addr := RegNext(addr)
  weight_mem.io.init_data := io.init_data

  weight_mem.io.read_en := loading && !io.init_mode
  weight_mem.io.read_addr := addr

  // ========================================
  // 输出
  // ========================================

  // 初始化模式: 输出地址供外部使用
  io.init_addr := RegNext(addr)

  // 计算模式: 输出权重数据
  io.data_out := weight_mem.io.read_data
  io.data_out_valid := RegNext(RegNext(loading && !io.init_mode, false.B), false.B)
}
