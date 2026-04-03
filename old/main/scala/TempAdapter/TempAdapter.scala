package TempAdapter

import TempAdapter.Param._
import chisel3._
import chisel3.util._

// 临时数据适配器
// 功能：将 LayerNorm 输出的 768 维向量转换为 Attention 需要的格式
// 输入：96-bit/cycle (12 elements)，收集 64 次得到 768 维向量
// 输出：48-bit/cycle [V1,V0|K1,K0|Q1,Q0]，输出 32 次
//
// 数据提取策略：
//   Q = [0:63]     - 前 64 维
//   K = [64:127]   - 中间 64 维
//   V = [128:191]  - 后 64 维
//   丢弃 [192:767] - 剩余 576 维（等待 QKV 映射模块完成后会使用）
class TempAdapter extends Module {
  val io = IO(new Bundle() {
    // ========================================
    // 来�� LayerNorm 的输入
    // ========================================
    val ln_in       = Input(UInt(LN_WIDTH.W))              // 96-bit (12 elements)
    val ln_in_st    = Input(Bool())                        // 开始信号
    val ln_in_addr  = Input(UInt(log2Up(LN_DEPTH).W))      // 地址
    val ln_in_valid = Input(Bool())                        // 有效信号
    val ln_in_last  = Input(Bool())                        // 结束信号
    val ln_ready    = Output(Bool())                       // 准备好信号

    // ========================================
    // 配置信号
    // ========================================
    val cfg_seqlen  = Input(UInt(log2Up(MAX_SEQLEN).W))
    val cfg_prefill = Input(Bool())
    val cfg_valid   = Input(Bool())

    // ========================================
    // 输出到 Attention (Load0 格式)
    // ========================================
    val data_out       = Output(UInt(OUT_WIDTH.W))        // 48-bit [V1,V0|K1,K0|Q1,Q0]
    val data_out_st    = Output(Bool())                   // 开始信号
    val data_out_addr  = Output(UInt(32.W))               // 地址
    val data_out_valid = Output(Bool())                   // 有效信号
    val data_out_last  = Output(Bool())                   // 结束信号
    val data_out_ready = Input(Bool())                    // 下游准备好
  })

  // 锁存配置
  val is_prefill = RegEnable(io.cfg_prefill, io.cfg_valid)
  val seqlen = RegEnable(io.cfg_seqlen, io.cfg_valid)

  // ========================================
  // 状态机
  // ========================================
  val idle :: collecting :: outputting :: Nil = Enum(3)
  val state = RegInit(idle)

  val is_idle = state === idle
  val is_collecting = state === collecting
  val is_outputting = state === outputting

  // ========================================
  // 阶段 1：收集 768 维向量
  // ========================================

  // 收集计数器（64 次）
  val collect_cnt = Wire(UInt(log2Up(COLLECT_NUM).W))
  val collect_last = collect_cnt === (COLLECT_NUM - 1).U

  collect_cnt := RegEnable(
    Mux(collect_last, 0.U, collect_cnt + 1.U),
    0.U,
    io.ln_in_valid && is_collecting
  )

  // 缓存 768 维向量（64 × 96-bit）
  val vec_buffer = Reg(Vec(COLLECT_NUM, UInt(LN_WIDTH.W)))
  when(io.ln_in_valid && is_collecting) {
    vec_buffer(collect_cnt) := io.ln_in
  }

  // ========================================
  // 阶段 2：提取 Q/K/V（各 64 维）
  // ========================================

  // 将 768 维向量重组为 8-bit 元素数组
  val full_vec = vec_buffer.asUInt.asTypeOf(Vec(LN_VECTOR, UInt(LN_DATAW.W)))

  // 提取 Q/K/V
  val Q_vec = Wire(Vec(HEAD_DIM, UInt(LN_DATAW.W)))
  val K_vec = Wire(Vec(HEAD_DIM, UInt(LN_DATAW.W)))
  val V_vec = Wire(Vec(HEAD_DIM, UInt(LN_DATAW.W)))

  for (i <- 0 until HEAD_DIM) {
    Q_vec(i) := full_vec(i)              // Q: [0:63]
    K_vec(i) := full_vec(HEAD_DIM + i)   // K: [64:127]
    V_vec(i) := full_vec(HEAD_DIM * 2 + i)  // V: [128:191]
  }

  // ========================================
  // 阶段 3：按 Load0 格式输出
  // ========================================

  // 输出计数器（32 次，每次输出 2 个 Q + 2 个 K + 2 个 V）
  val output_cnt = Wire(UInt(log2Up(OUTPUT_NUM).W))
  val output_last = output_cnt === (OUTPUT_NUM - 1).U

  output_cnt := RegEnable(
    Mux(output_last, 0.U, output_cnt + 1.U),
    0.U,
    is_outputting && io.data_out_ready
  )

  // 每周期输出 2 个元素
  val idx = output_cnt << 1  // ×2

  io.data_out := Cat(
    V_vec(idx + 1), V_vec(idx),      // V1, V0 (高 16 位)
    K_vec(idx + 1), K_vec(idx),      // K1, K0 (中 16 位)
    Q_vec(idx + 1), Q_vec(idx)       // Q1, Q0 (低 16 位)
  )

  // ========================================
  // 外层循环：Prefill 或 Decode
  // ========================================

  // Prefill 计数器
  val prefill_cnt = Wire(UInt(log2Up(MAX_PREFILL).W))
  val prefill_last = prefill_cnt === seqlen
  prefill_cnt := RegEnable(
    Mux(prefill_last, 0.U, prefill_cnt + 1.U),
    0.U,
    is_outputting && output_last && is_prefill && io.data_out_ready
  )

  // Decode 计数器
  val batch_cnt = Wire(UInt(log2Up(BATCHSIZE).W))
  val batch_last = batch_cnt === (BATCHSIZE - 1).U
  batch_cnt := RegEnable(
    Mux(batch_last, 0.U, batch_cnt + 1.U),
    0.U,
    is_outputting && output_last && !is_prefill && io.data_out_ready
  )

  // 所有数据输出完毕
  val all_output_done = output_last && (is_prefill && prefill_last || !is_prefill && batch_last)

  // ========================================
  // 状态机转换
  // ========================================
  switch(state) {
    is(idle) {
      when(io.ln_in_valid && io.ln_in_st) {
        state := collecting
      }
    }
    is(collecting) {
      when(collect_last && io.ln_in_valid) {
        state := outputting
      }
    }
    is(outputting) {
      when(all_output_done && io.data_out_ready) {
        // 检查是否还有下一个 token
        when(io.ln_in_valid) {
          state := collecting  // 继续收集下一个 token
        }.otherwise {
          state := idle
        }
      }
    }
  }

  // ========================================
  // 输出信号
  // ========================================
  io.data_out_valid := is_outputting
  io.data_out_st := is_outputting && output_cnt === 0.U && prefill_cnt === 0.U && batch_cnt === 0.U
  io.data_out_last := is_outputting && all_output_done

  // 地址生成
  io.data_out_addr := Mux(
    is_prefill,
    prefill_cnt * OUTPUT_NUM.U + output_cnt,
    batch_cnt * OUTPUT_NUM.U + output_cnt
  )

  // 准备好信号
  io.ln_ready := is_collecting || is_idle
}

object TempAdapterGen extends App {
  emitVerilog(new TempAdapter, Array("--target-dir", "generated"))
}
