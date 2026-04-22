package QKVLinear

import QKVLinear._
import QuantCommon.Precision._
import QuantCommon.{FpBackend, XilinxFpTargetConfig}
import chisel3._
import chisel3.util._
import QKVLinear.Param._

// QKVLinear: QKV 映射模块
// 功能: 将 LayerNorm 输出的 768 维向量映射为 Q/K/V 三个 768 维向量
// 输入: 96-bit/cycle (12 elements)，768 维向量
// 输出: 48-bit/cycle [V1,V0|K1,K0|Q1,Q0]，输出给 Attention
//
// 矩阵乘法: X(768) × W(768×2304) = [Q|K|V](2304)
// 然后提取 Q[0:63], K[64:127], V[128:191] 各 64 维给 Attention
class QKVLinear extends Module {
  val io = IO(new Bundle() {
    val layer_st = Input(Bool())

    // 权重初始化接口
    val weight_init_mode = Input(Bool())  // true: 初始化模式, false: 计算模式
    val weight_init_data = Input(UInt(WMEM_WIDTH.W))
    val weight_init_addr = Output(UInt(log2Up(WMEM_DEPTH).W))
    val weight_active_bank = Input(Bool())
    val weight_preload_bank = Input(Bool())
    val weight_preload_valid = Input(Bool())
    val weight_preload_addr = Input(UInt(log2Up(WMEM_DEPTH).W))
    val weight_preload_data = Input(UInt(WMEM_WIDTH.W))
    val bias_init_data = Input(UInt(MEM_WIDTH.W))
    val bias_init_valid = Input(Bool())
    val q_out_inv_scale = Input(UInt(FP32_WIDTH.W))
    val k_out_inv_scale = Input(UInt(FP32_WIDTH.W))
    val v_out_inv_scale = Input(UInt(FP32_WIDTH.W))
    val q_bias_scale = Input(UInt(FP32_WIDTH.W))
    val k_bias_scale = Input(UInt(FP32_WIDTH.W))
    val v_bias_scale = Input(UInt(FP32_WIDTH.W))

    // 来自 LayerNorm 的输入
    val data_in_st = Input(Bool())
    val data_in = Input(UInt(MEM_WIDTH.W))
    val data_addr = Input(UInt(log2Up(MEM_DEPTH).W))
    val data_valid = Input(Bool())
    val data_last = Input(Bool())
    val data_ready = Output(Bool())

    // 配置信号
    val cfg_prefill = Input(Bool())
    val cfg_seqlen = Input(UInt(log2Up(MAX_SEQLEN).W))
    val cfg_valid = Input(Bool())
    val cfg_single_query = Input(Bool())

    // 输出到 Attention (Load0 格式: 48-bit [V1,V0|K1,K0|Q1,Q0])
    val data_out = Output(UInt(48.W))
    val data_out_st = Output(Bool())
    val data_out_head = Output(UInt(log2Up(HEAD_NUM).W))
    val data_out_addr = Output(UInt(32.W))
    val data_out_valid = Output(Bool())
    val data_out_last = Output(Bool())
    val data_out_ready = Input(Bool())
  })

  // 锁存配置
  val is_prefill = RegEnable(io.cfg_prefill, io.cfg_valid)
  val seqlen = RegEnable(io.cfg_seqlen, io.cfg_valid)
  val is_single_query = RegEnable(io.cfg_single_query, false.B, io.cfg_valid)

  // ========================================
  // Linear 计算核心
  // ========================================
  val mem_inst = Module(new DataMem(MEM_DEPTH, MEM_WIDTH))
  val lu_inst = Module(new LoadUnit)
  val cu_inst = Module(new CUQuant)
  val su_inst = Module(new StoreUnit)
  val lw_inst = Module(new LoadWeight)
  val bias_mem = Reg(Vec(OUT_VECTOR / ROW, UInt(MEM_WIDTH.W)))
  val bias_init_cnt = RegInit(0.U(log2Up(OUT_VECTOR / ROW).W))

  when(io.bias_init_valid) {
    bias_mem(bias_init_cnt) := io.bias_init_data
    bias_init_cnt := Mux(bias_init_cnt === (OUT_VECTOR / ROW - 1).U, 0.U, bias_init_cnt + 1.U)
  }

  mem_inst.io.w_st := io.data_in_st
  mem_inst.io.w_last := io.data_last
  mem_inst.io.w_data := io.data_in
  mem_inst.io.w_addr := io.data_addr
  mem_inst.io.w_valid := io.data_valid
  mem_inst.io.r_last := lu_inst.io.data_in_last
  mem_inst.io.r_addr := lu_inst.io.data_in_addr

  lu_inst.io.data_in := mem_inst.io.r_data
  lu_inst.io.data_in_ready := mem_inst.io.r_ready
  lu_inst.io.data_out_ready := io.data_out_ready
  lu_inst.io.cfg_prefill := io.cfg_prefill
  lu_inst.io.cfg_seqlen := io.cfg_seqlen
  lu_inst.io.cfg_valid := io.cfg_valid
  lu_inst.io.cfg_single_query := io.cfg_single_query
  lu_inst.io.weight_ready := lw_inst.io.weight_ready

  cu_inst.io.data_in := lu_inst.io.data_out
  cu_inst.io.data_in_valid := lu_inst.io.data_out_valid
  cu_inst.io.w_data := lw_inst.io.data_out
  cu_inst.io.w_data_sel := lw_inst.io.data_out_sel
  cu_inst.io.w_valid := lw_inst.io.data_out_valid
  cu_inst.io.cfg_prefill := io.cfg_prefill
  cu_inst.io.cfg_seqlen := io.cfg_seqlen
  cu_inst.io.cfg_valid := io.cfg_valid
  cu_inst.io.cfg_single_query := io.cfg_single_query

  su_inst.io.data_in := cu_inst.io.data_out
  su_inst.io.data_in_valid := cu_inst.io.data_out_valid
  su_inst.io.cfg_prefill := io.cfg_prefill
  su_inst.io.cfg_seqlen := io.cfg_seqlen
  su_inst.io.cfg_valid := io.cfg_valid
  su_inst.io.cfg_single_query := io.cfg_single_query

  lw_inst.io.update := cu_inst.io.w_update
  lw_inst.io.st := io.layer_st || (io.data_in_st && !mem_inst.io.r_ready)
  lw_inst.io.init_mode := io.weight_init_mode
  lw_inst.io.init_data := io.weight_init_data
  lw_inst.io.active_bank := io.weight_active_bank
  lw_inst.io.preload_valid := io.weight_preload_valid
  lw_inst.io.preload_bank := io.weight_preload_bank
  lw_inst.io.preload_addr := io.weight_preload_addr
  lw_inst.io.preload_data := io.weight_preload_data

  io.data_ready := mem_inst.io.w_ready
  io.weight_init_addr := lw_inst.io.init_addr

  // ========================================
  // 输出格式转换: 2304维 -> Q/K/V 各64维 -> 48-bit/cycle
  // ========================================
  // Linear 输出: 96-bit/cycle, 共 192 次 (2304/12=192)
  // 需要收集完整的 2304 维向量，然后提取 Q/K/V

  // 状态机
  val idle :: collecting :: outputting :: Nil = Enum(3)
  val state = RegInit(idle)

  val is_idle = state === idle
  val is_collecting = state === collecting
  val is_outputting = state === outputting
  val all_output_done = WireDefault(false.B)
  val collect_start = is_idle || (is_outputting && all_output_done && io.data_out_ready)
  val collect_fire = su_inst.io.data_out_valid && (is_collecting || collect_start)
  val epilogueLatency =
    if (FpBackend.useVivadoIp)
      XilinxFpTargetConfig.FixedToFloatLatency +
        XilinxFpTargetConfig.MulLatency +
        XilinxFpTargetConfig.AddLatency +
        XilinxFpTargetConfig.FloatToFixedLatency
    else
      0

  // 收集计数器 (192 次收集 2304 维)
  val COLLECT_NUM = OUT_VECTOR / ROW  // = 2304 / 12 = 192
  val collect_addr = su_inst.io.data_out_addr
  val collect_done = su_inst.io.data_out_last
  val collect_commit = Wire(Bool())
  val collect_addr_d = Wire(UInt(collect_addr.getWidth.W))
  val collect_done_d = Wire(Bool())
  if (epilogueLatency == 0) {
    collect_commit := collect_fire
    collect_addr_d := collect_addr
    collect_done_d := collect_done && collect_fire
  } else {
    val collect_valid_pipe = RegInit(VecInit(Seq.fill(epilogueLatency)(false.B)))
    val collect_addr_pipe = RegInit(VecInit(Seq.fill(epilogueLatency)(0.U(collect_addr.getWidth.W))))
    val collect_done_pipe = RegInit(VecInit(Seq.fill(epilogueLatency)(false.B)))
    collect_valid_pipe(0) := collect_fire
    collect_addr_pipe(0) := collect_addr
    collect_done_pipe(0) := collect_done && collect_fire
    for (i <- 1 until epilogueLatency) {
      collect_valid_pipe(i) := collect_valid_pipe(i - 1)
      collect_addr_pipe(i) := collect_addr_pipe(i - 1)
      collect_done_pipe(i) := collect_done_pipe(i - 1)
    }
    collect_commit := collect_valid_pipe.last
    collect_addr_d := collect_addr_pipe.last
    collect_done_d := collect_done_pipe.last
  }
  val collect_token = (collect_addr_d / COLLECT_NUM.U)(log2Up(BATCHSIZE) - 1, 0)
  val collect_vec = collect_addr_d % COLLECT_NUM.U

  // 缓存所有 token 的 2304 维向量
  val vec_buffer = Reg(Vec(BATCHSIZE, Vec(COLLECT_NUM, UInt(MEM_WIDTH.W))))
  val collectWriteEnableReg = RegInit(false.B)
  collectWriteEnableReg.suggestName("collectWriteEnableReg")
  val collectTokenReg = Reg(UInt(collect_token.getWidth.W))
  collectTokenReg.suggestName("collectTokenReg")
  val collectVecReg = Reg(UInt(collect_vec.getWidth.W))
  collectVecReg.suggestName("collectVecReg")
  val collectDataReg = Reg(UInt(MEM_WIDTH.W))
  collectDataReg.suggestName("collectDataReg")
  val collect_vec_now = collect_addr % COLLECT_NUM.U
  val scale_sel = Mux(
    collect_vec_now < (768 / ROW).U,
    io.q_out_inv_scale,
    Mux(collect_vec_now < (2 * 768 / ROW).U, io.k_out_inv_scale, io.v_out_inv_scale)
  )
  val bias_scale_sel = Mux(
    collect_vec_now < (768 / ROW).U,
    io.q_bias_scale,
    Mux(collect_vec_now < (2 * 768 / ROW).U, io.k_bias_scale, io.v_bias_scale)
  )
  val epilogue_inst = Module(new Int32VecScaleBiasToSInt32(ROW))
  epilogue_inst.io.in := Mux(collect_fire, su_inst.io.data_out, 0.U(su_inst.io.data_out.getWidth.W))
  epilogue_inst.io.accScale := Mux(collect_fire, scale_sel, 0.U(FP32_WIDTH.W))
  epilogue_inst.io.bias := Mux(collect_fire, bias_mem(collect_vec_now), 0.U(MEM_WIDTH.W))
  epilogue_inst.io.biasScale := Mux(collect_fire, bias_scale_sel, 0.U(FP32_WIDTH.W))

  val su_vec = epilogue_inst.io.out.asTypeOf(Vec(ROW, SInt(INT32_WIDTH.W)))
  val biased_vec = Wire(Vec(ROW, SInt(DATAW.W)))
  for (i <- 0 until ROW) {
    val maxVal = ((1 << (DATAW - 1)) - 1).S
    val minVal = (-(1 << (DATAW - 1))).S
    biased_vec(i) := Mux(
      su_vec(i) > maxVal,
      maxVal,
      Mux(su_vec(i) < minVal, minVal, su_vec(i)(DATAW - 1, 0).asSInt)
    )
  }
  collectWriteEnableReg := collect_commit
  when(collect_commit) {
    collectTokenReg := collect_token
    collectVecReg := collect_vec
    collectDataReg := biased_vec.asUInt
  }
  when(collectWriteEnableReg) {
    vec_buffer(collectTokenReg)(collectVecReg) := collectDataReg
  }

  // Head 计数器 (0~HEAD_NUM-1)
  val head_cnt = Wire(UInt(log2Up(HEAD_NUM + 1).W))
  val head_last = head_cnt === (HEAD_NUM - 1).U
  val prefill_cnt = Wire(UInt(log2Up(MAX_PREFILL + 1).W))
  val batch_cnt = Wire(UInt(log2Up(BATCHSIZE).W))
  val current_token = Mux(is_prefill, prefill_cnt, batch_cnt)
  // vec_buffer holds exactly BATCHSIZE tokens; batch_cnt never reaches 32 and
  // prefill_cnt is narrower, so trimming the unreachable high bit avoids
  // building a wider-than-necessary dynamic mux without changing semantics.
  val current_token_idx = current_token(log2Up(BATCHSIZE) - 1, 0)
  val full_vec = vec_buffer(current_token_idx).asUInt.asTypeOf(Vec(OUT_VECTOR, UInt(DATAW.W)))

  // 提取 Q/K/V (用 head_cnt 偏移选择当前 head)
  // Q: [head*64 : head*64+63], K: [768+head*64 : ...], V: [1536+head*64 : ...]
  val Q_vec = Wire(Vec(HEAD_DIM, UInt(DATAW.W)))
  val K_vec = Wire(Vec(HEAD_DIM, UInt(DATAW.W)))
  val V_vec = Wire(Vec(HEAD_DIM, UInt(DATAW.W)))

  // 用 VecInit + head_cnt 索引（因为 full_vec 的动态索引需要这种方式）
  for (i <- 0 until HEAD_DIM) {
    Q_vec(i) := VecInit((0 until HEAD_NUM).map(h => full_vec(h * HEAD_DIM + i)))(head_cnt)
    K_vec(i) := VecInit((0 until HEAD_NUM).map(h => full_vec(768 + h * HEAD_DIM + i)))(head_cnt)
    V_vec(i) := VecInit((0 until HEAD_NUM).map(h => full_vec(768 * 2 + h * HEAD_DIM + i)))(head_cnt)
  }

  // 输出计数器 (32 次，每次输出 2 个 Q + 2 个 K + 2 个 V)
  val OUTPUT_NUM = HEAD_DIM / 2  // = 32
  val output_cnt = Wire(UInt(log2Up(OUTPUT_NUM).W))
  val output_last = output_cnt === (OUTPUT_NUM - 1).U

  output_cnt := RegEnable(
    Mux(output_last, 0.U, output_cnt + 1.U),
    0.U,
    is_outputting && io.data_out_ready
  )

  // 每周期输出 2 个元素
  val idx = (output_cnt << 1)(5, 0)
  val idx_plus1 = (idx + 1.U)(5, 0)

  val out_data = Cat(
    V_vec(idx_plus1), V_vec(idx),      // V1, V0 (高 16 位)
    K_vec(idx_plus1), K_vec(idx),      // K1, K0 (中 16 位)
    Q_vec(idx_plus1), Q_vec(idx)       // Q1, Q0 (低 16 位)
  )

  // Prefill 计数器
  val prefill_last = prefill_cnt === seqlen
  prefill_cnt := RegEnable(
    Mux(prefill_last, 0.U, prefill_cnt + 1.U),
    0.U,
    is_outputting && output_last && is_prefill && io.data_out_ready
  )

  // Decode 计数器
  val decodeBatchLast = Mux(is_single_query, 0.U(batch_cnt.getWidth.W), (BATCHSIZE - 1).U(batch_cnt.getWidth.W))
  val batch_last = batch_cnt === decodeBatchLast
  batch_cnt := RegEnable(
    Mux(batch_last, 0.U, batch_cnt + 1.U),
    0.U,
    is_outputting && output_last && !is_prefill && io.data_out_ready
  )

  // token 级别的 last（prefill 或 decode 的所有 token 输出完毕）
  val token_last = is_prefill && prefill_last || !is_prefill && batch_last

  // head_cnt 计数器：在所有 token 的 output 输出完后递增
  head_cnt := RegEnable(
    Mux(head_last, 0.U, head_cnt + 1.U),
    0.U,
    is_outputting && output_last && token_last && io.data_out_ready
  )

  // 所有数据输出完毕（所有 head 的所有 token）
  all_output_done := output_last && token_last && head_last

  // 状态机转换
  switch(state) {
    is(idle) {
      when(su_inst.io.data_out_valid) {
        state := collecting
      }
    }
    is(collecting) {
      when(collectWriteEnableReg && collectVecReg === (COLLECT_NUM - 1).U) {
        state := outputting
      }
    }
    is(outputting) {
      when(all_output_done && io.data_out_ready) {
        state := idle
      }
    }
  }

  // 输出信号
  io.data_out := out_data
  io.data_out_valid := is_outputting
  // data_out_st should mark the first beat of each head stream, not the first
  // beat of every token inside that head. Otherwise DM1/VCache open a new write
  // bank on each token while only closing once per head.
  io.data_out_st := is_outputting && output_cnt === 0.U && current_token === 0.U
  io.data_out_head := head_cnt
  // data_out_last: 每个 head 的所有 token 输出完毕（不是 12 个 head 全部结束）
  io.data_out_last := is_outputting && output_last && token_last

  // 地址生成
  io.data_out_addr := Mux(
    is_prefill,
    prefill_cnt * OUTPUT_NUM.U + output_cnt,
    batch_cnt * OUTPUT_NUM.U + output_cnt
  )
}

object QKVLinearGen extends App {
  emitVerilog(new QKVLinear, Array("--target-dir", "generated"))
}
