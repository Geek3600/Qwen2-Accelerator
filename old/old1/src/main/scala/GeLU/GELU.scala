package GeLU

import GeLU.Param._
import chisel3._
import chisel3.util._

// GELU 激活函数模块
// 功能: 对 3072 维向量应用 GELU 激活函数
// 输入: 96-bit/cycle (12 elements) from FFNUp
// 输出: 96-bit/cycle (12 elements) to FFNDown
class GELU extends Module {
  val io = IO(new Bundle() {
    // 来自 FFNUp 的输入
    val data_in_st = Input(Bool())
    val data_in = Input(UInt(MEM_WIDTH.W))
    val data_in_addr = Input(UInt(log2Up(MEM_DEPTH).W))
    val data_in_valid = Input(Bool())
    val data_in_last = Input(Bool())
    val data_ready = Output(Bool())

    // 配置信号
    val cfg_prefill = Input(Bool())
    val cfg_seqlen = Input(UInt(log2Up(MAX_SEQLEN).W))
    val cfg_valid = Input(Bool())

    // 输出到 FFNDown
    val data_out = Output(UInt(MEM_WIDTH.W))
    val data_out_st = Output(Bool())
    val data_out_addr = Output(UInt(log2Up(MEM_DEPTH).W))
    val data_out_valid = Output(Bool())
    val data_out_last = Output(Bool())
    val data_out_ready = Input(Bool())
  })

  // 锁存配置
  val is_prefill = RegEnable(io.cfg_prefill, false.B, io.cfg_valid)
  val seqlen = RegEnable(io.cfg_seqlen, 0.U, io.cfg_valid)

  // ========================================
  // 双缓冲存储器
  // ========================================
  val mem = Module(new DataMem(MEM_DEPTH, MEM_WIDTH, 3))

  mem.io.w_st := io.data_in_st
  mem.io.w_last := io.data_in_last
  mem.io.w_data := io.data_in
  mem.io.w_addr := io.data_in_addr
  mem.io.w_valid := io.data_in_valid

  io.data_ready := mem.io.w_ready

  // ========================================
  // GELU 计算单元
  // ========================================
  val cu = Module(new CU)

  // 状态机：idle / processing
  val idle :: processing :: Nil = Enum(2)
  val state = RegInit(idle)

  val is_idle = state === idle
  val is_processing = state === processing

  // 计算实际批次数
  val actual_batchsize = Mux(is_prefill, seqlen, (BATCHSIZE - 1).U)

  // 计数器：遍历所有数据
  val VECNUM_PER_TOKEN = DATA_DIM / VECNUM  // = 3072 / 12 = 256
  val batchsize_cnt = Wire(UInt(log2Up(BATCHSIZE).W))
  val vector_cnt = Wire(UInt(log2Up(VECNUM_PER_TOKEN + 1).W))

  val batchsize_last = batchsize_cnt === actual_batchsize
  val vector_last = vector_cnt === (VECNUM_PER_TOKEN - 1).U

  batchsize_cnt := RegEnable(
    Mux(batchsize_last, 0.U, batchsize_cnt + 1.U),
    0.U,
    is_processing
  )

  vector_cnt := RegEnable(
    Mux(vector_last, 0.U, vector_cnt + 1.U),
    0.U,
    is_processing && batchsize_last
  )

  // 状态机转换
  switch(state) {
    is(idle) {
      when(mem.io.r_ready && io.data_out_ready) {
        state := processing
      }
    }
    is(processing) {
      when(vector_last && batchsize_last) {
        when(mem.io.r_ready && io.data_out_ready) {
          state := processing  // 继续处理下一批
        }.otherwise {
          state := idle
        }
      }
    }
  }

  // 读取地址
  val read_addr = batchsize_cnt * VECNUM_PER_TOKEN.U + vector_cnt
  mem.io.r_addr := read_addr
  mem.io.r_last := is_processing && vector_last && batchsize_last

  // GELU 计算
  cu.io.data_in := mem.io.r_data
  cu.io.data_in_valid := is_processing

  // 输出信号（打一拍对齐 CU 延迟）
  val out_st = is_processing && batchsize_cnt === 0.U && vector_cnt === 0.U
  val out_last = is_processing && vector_last && batchsize_last

  io.data_out := cu.io.data_out
  io.data_out_valid := cu.io.data_out_valid
  io.data_out_addr := RegNext(read_addr)
  io.data_out_st := RegNext(out_st, false.B)
  io.data_out_last := RegNext(out_last, false.B)
}

object GELUGen extends App {
  emitVerilog(new GELU, Array("--target-dir", "generated"))
}
