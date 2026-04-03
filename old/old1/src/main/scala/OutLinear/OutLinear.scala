package OutLinear

import OutLinear._
import chisel3._
import chisel3.util._
import OutLinear.Param._

// OutLinear: 输出映射模块
// 功能: 将 DM2 输出的 64 维向量映射为 768 维向量
// 输入: 512-bit/cycle (64 elements) from DM2
// 输出: 96-bit/cycle (12 elements) to ResAdd
//
// 矩阵乘法: X(64) × W(64×768) = Y(768)
class OutLinear extends Module {
  val io = IO(new Bundle() {
    val layer_st = Input(Bool())

    // 来自 DM2 的输入 (512-bit = 64 elements)
    val data_in_st = Input(Bool())
    val data_in = Input(UInt(DM2_WIDTH.W))
    val data_in_addr = Input(UInt(log2Up(MEM_DEPTH).W))
    val data_in_valid = Input(Bool())
    val data_in_last = Input(Bool())
    val data_ready = Output(Bool())

    // 权重初始化接口
    val weight_init_mode = Input(Bool())
    val weight_init_data = Input(UInt(WMEM_WIDTH.W))
    val weight_init_addr = Output(UInt(log2Up(WMEM_DEPTH).W))

    // 配置信号
    val cfg_prefill = Input(Bool())
    val cfg_seqlen = Input(UInt(log2Up(MAX_SEQLEN).W))
    val cfg_valid = Input(Bool())

    // 输出到 ResAdd (96-bit = 12 elements)
    val data_out = Output(UInt(MEM_WIDTH.W))
    val data_out_st = Output(Bool())
    val data_out_addr = Output(UInt(log2Up(MEM_DEPTH).W))
    val data_out_valid = Output(Bool())
    val data_out_last = Output(Bool())
    val data_out_ready = Input(Bool())
  })

  // 锁存配置
  val is_prefill = RegEnable(io.cfg_prefill, io.cfg_valid)
  val seqlen = RegEnable(io.cfg_seqlen, io.cfg_valid)

  // ========================================
  // 输入格式转换: 512-bit -> 96-bit chunks
  // ========================================
  // DM2 输出 512-bit (64 elements)，需要拆分为 96-bit (12 elements) 送入 Linear
  // 64 / 12 = 5.33，需要 6 次传输 (最后一次只有 4 个有效元素)

  val INPUT_CHUNKS = ROWBLOCK  // = 6

  // 输入缓存
  val input_buffer = Reg(UInt(DM2_WIDTH.W))
  val input_valid = RegInit(false.B)

  // 输入分块计数器
  val chunk_cnt = Wire(UInt(log2Up(INPUT_CHUNKS + 1).W))
  val chunk_last = chunk_cnt === (INPUT_CHUNKS - 1).U

  // 状态机
  val idle :: buffering :: feeding :: Nil = Enum(3)
  val state = RegInit(idle)

  val is_idle = state === idle
  val is_buffering = state === buffering
  val is_feeding = state === feeding

  // 双缓冲存储器 (存储转换后的 96-bit 数据)
  val mem_inst = Module(new DataMen(MEM_DEPTH, MEM_WIDTH))
  val lu_inst = Module(new LoadU)
  val cu_inst = Module(new CU)
  val su_inst = Module(new StoreU)
  val lw_inst = Module(new LoadW)

  // 输入数据写入计数
  val write_cnt = Wire(UInt(log2Up(MEM_DEPTH).W))
  val write_batch_cnt = Wire(UInt(log2Up(BATCHSIZE).W))
  val actual_batchsize = Mux(is_prefill, seqlen, (BATCHSIZE - 1).U)
  val write_batch_last = write_batch_cnt === actual_batchsize

  write_batch_cnt := RegEnable(
    Mux(write_batch_last, 0.U, write_batch_cnt + 1.U),
    0.U,
    io.data_in_valid
  )

  chunk_cnt := RegEnable(
    Mux(chunk_last, 0.U, chunk_cnt + 1.U),
    0.U,
    is_feeding
  )

  write_cnt := RegEnable(
    Mux(chunk_last && write_batch_last, 0.U, write_cnt + 1.U),
    0.U,
    is_feeding
  )

  // 状态机转换
  switch(state) {
    is(idle) {
      when(io.data_in_valid) {
        input_buffer := io.data_in
        input_valid := true.B
        state := feeding
      }
    }
    is(feeding) {
      when(chunk_last) {
        when(io.data_in_valid) {
          input_buffer := io.data_in
          input_valid := true.B
          state := feeding
        }.otherwise {
          input_valid := false.B
          state := idle
        }
      }
    }
  }

  // 从 512-bit 缓存中提取 96-bit 数据
  val chunk_data = Wire(UInt(MEM_WIDTH.W))
  val padded_buffer = Cat(0.U((INPUT_CHUNKS * MEM_WIDTH - DM2_WIDTH).W), input_buffer)

  chunk_data := MuxLookup(chunk_cnt, 0.U)(
    (0 until INPUT_CHUNKS).map { i =>
      i.U -> padded_buffer((i + 1) * MEM_WIDTH - 1, i * MEM_WIDTH)
    }
  )

  // 写入存储器
  val mem_write_valid = is_feeding
  val mem_write_addr = write_batch_cnt * INPUT_CHUNKS.U + chunk_cnt
  val mem_write_last = chunk_last && write_batch_last

  mem_inst.io.w_st := io.data_in_st
  mem_inst.io.w_last := mem_write_last
  mem_inst.io.w_data := chunk_data
  mem_inst.io.w_addr := mem_write_addr
  mem_inst.io.w_valid := mem_write_valid

  // ========================================
  // Linear 计算核心
  // ========================================
  mem_inst.io.r_last := lu_inst.io.data_in_last
  mem_inst.io.r_addr := lu_inst.io.data_in_addr

  lu_inst.io.data_in := mem_inst.io.r_data
  lu_inst.io.data_in_ready := mem_inst.io.r_ready
  lu_inst.io.data_out_ready := io.data_out_ready
  lu_inst.io.cfg_prefill := io.cfg_prefill
  lu_inst.io.cfg_seqlen := io.cfg_seqlen
  lu_inst.io.cfg_valid := io.cfg_valid

  cu_inst.io.data_in := lu_inst.io.data_out
  cu_inst.io.data_in_valid := lu_inst.io.data_out_valid
  cu_inst.io.w_data := lw_inst.io.data_out
  cu_inst.io.w_valid := lw_inst.io.data_out_valid
  cu_inst.io.cfg_prefill := io.cfg_prefill
  cu_inst.io.cfg_seqlen := io.cfg_seqlen
  cu_inst.io.cfg_valid := io.cfg_valid

  su_inst.io.data_in := cu_inst.io.data_out
  su_inst.io.data_in_valid := cu_inst.io.data_out_valid
  su_inst.io.cfg_prefill := io.cfg_prefill
  su_inst.io.cfg_seqlen := io.cfg_seqlen
  su_inst.io.cfg_valid := io.cfg_valid

  lw_inst.io.update := cu_inst.io.w_update
  lw_inst.io.st := io.layer_st
  lw_inst.io.init_mode := io.weight_init_mode
  lw_inst.io.init_data := io.weight_init_data

  io.data_ready := mem_inst.io.w_ready
  io.weight_init_addr := lw_inst.io.init_addr

  // ========================================
  // 输出信号
  // ========================================
  io.data_out := su_inst.io.data_out
  io.data_out_valid := su_inst.io.data_out_valid
  io.data_out_addr := su_inst.io.data_out_addr
  io.data_out_st := su_inst.io.data_out_st
  io.data_out_last := su_inst.io.data_out_last
}

object OutLinearGen extends App {
  emitVerilog(new OutLinear, Array("--target-dir", "generated"))
}
