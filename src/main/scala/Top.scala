import DM.Param._
import LayerNormQ.LayerNormQ
import QuantCommon.Precision._
import Softmax.Param.{WMEM_DEPTH => SM_WMEM_DEPTH, WMEM_WIDTH => SM_WMEM_WIDTH}
import QKVLinear.{QKVLinear, Param => QKVParam}
import OutLinear.{OutLinearFP32, Param => OutParam}
import ResAdd.ResAddFP32
import ResAdd.FP32Param.{DATA_WIDTH => RES_DATA_WIDTH, MEM_DEPTH => RES_MEM_DEPTH}
import FFNUp.{FFNUp, Param => FFNUpParam}
import FFNDown.{FFNDownFP32, Param => FFNDownParam}
import ResAdd2.ResAdd2FP32
import TempAdapter.LNAddrGen
import TempAdapter.Param.{MAX_SEQLEN => CORE_MAX_SEQLEN}
import chisel3._
import chisel3.util._

// 顶层模块：整合完整的 Transformer Block 流水线
// 数据流:
// 输入 -> LayerNorm -> QKVLinear -> Atten -> OutLinear -> ResAdd (S7)
//          └────────────────────────────────────────────────────────┘ 残差1
//                                                                    ↓
//                                              LayerNorm2 (S8) -> FFNUp(ReLU fused) -> FFNDown -> ResAdd2 -> 输出
//                                                                                                                  ↑
//                                                                                                            S7 输出
class Top extends Module{
  val io = IO(new Bundle() {
    val layer_st = Input(Bool())
    val cfg_seqlen = Input(UInt(log2Up(CORE_MAX_SEQLEN).W))
    val cfg_prefill = Input(Bool())
    val cfg_valid = Input(Bool())

    // Attention 单独配置口。
    // 默认可与整机 cfg_* 保持一致；后续做长序列 token-by-token 调度时，
    // 允许保持非 attention 路径按单 token 运行，而让 attention 单独看到历史长度。
    val attn_cfg_seqlen = Input(UInt(16.W))
    val attn_cfg_prefill = Input(Bool())
    val attn_cfg_valid = Input(Bool())
    val attn_cfg_single_query = Input(Bool())

    // 权重初始化模式控制
    val weight_init_mode = Input(Bool())  // true: 初始化模式, false: 计算模式

    // 输入: 12 x FP32
    val data_in = Input(UInt(FP32_PACK_WIDTH.W))
    val data_in_ready = Input(Bool())
    val data_in_addr = Output(UInt(32.W))

    // LayerNorm 权重输入
    val ln_w_in = Input(UInt(FP32_PACK_WIDTH.W))
    val ln_w_valid = Input(Bool())

    // 量化参数
    val ln1_out_inv_scale = Input(UInt(FP32_WIDTH.W))
    val ln1_out_zero_point = Input(SInt(INT8_WIDTH.W))
    val q_out_inv_scale = Input(UInt(FP32_WIDTH.W))
    val k_out_inv_scale = Input(UInt(FP32_WIDTH.W))
    val v_out_inv_scale = Input(UInt(FP32_WIDTH.W))
    val q_bias_scale = Input(UInt(FP32_WIDTH.W))
    val k_bias_scale = Input(UInt(FP32_WIDTH.W))
    val v_bias_scale = Input(UInt(FP32_WIDTH.W))
    val dm1_out_scale = Input(UInt(FP32_WIDTH.W))
    val dm2_ctx_inv_scale = Input(UInt(FP32_WIDTH.W))
    val dm2_ctx_zero_point = Input(UInt(UINT8_WIDTH.W))
    val dm2_out_inv_scale = Input(UInt(FP32_WIDTH.W))
    val out_out_scale = Input(UInt(FP32_WIDTH.W))
    val ln2_out_inv_scale = Input(UInt(FP32_WIDTH.W))
    val ln2_out_zero_point = Input(SInt(INT8_WIDTH.W))
    val ffnup_out_inv_scale = Input(UInt(FP32_WIDTH.W))
    val ffnup_bias_scale = Input(UInt(FP32_WIDTH.W))
    val ffndown_out_scale = Input(UInt(FP32_WIDTH.W))

    // QKVLinear 权重、bias输入/初始化
    val qkv_w_in = Input(UInt(QKVParam.WMEM_WIDTH.W))
    val qkv_w_addr = Output(UInt(log2Up(QKVParam.WMEM_DEPTH).W))
    val qkv_b_in = Input(UInt(QKVParam.MEM_WIDTH.W))
    val qkv_b_valid = Input(Bool())

    // Softmax 权重输入
    val sm_w_in = Input(UInt(SM_WMEM_WIDTH.W))
    val sm_w_addr = Output(UInt(log2Up(SM_WMEM_DEPTH).W))

    // OutLinear 权重输入/初始化
    val out_w_in = Input(UInt(OutParam.WMEM_WIDTH.W))
    val out_w_addr = Output(UInt(log2Up(OutParam.WMEM_DEPTH).W))
    val out_b_in = Input(UInt(FP32_PACK_WIDTH.W))
    val out_b_valid = Input(Bool())

    // LayerNorm2 权重输入
    val ln2_w_in = Input(UInt(FP32_PACK_WIDTH.W))
    val ln2_w_valid = Input(Bool())

    // FFNUp 权重输入/初始化
    val ffnup_w_in = Input(UInt(FFNUpParam.WMEM_WIDTH.W))
    val ffnup_w_addr = Output(UInt(log2Up(FFNUpParam.WMEM_DEPTH).W))
    val ffnup_b_in = Input(UInt(FFNUpParam.MEM_WIDTH.W))
    val ffnup_b_valid = Input(Bool())

    // FFNDown 权重输入/初始化
    val ffndown_w_in = Input(UInt(FFNDownParam.WMEM_WIDTH.W))
    val ffndown_w_addr = Output(UInt(log2Up(FFNDownParam.WMEM_DEPTH).W))
    val ffndown_b_in = Input(UInt(FP32_PACK_WIDTH.W))
    val ffndown_b_valid = Input(Bool())

    // 输出 (ResAdd2 输出格式: 96-bit)
    val res = Output(UInt(RES_DATA_WIDTH.W))
    val res_st = Output(Bool())
    val res_addr = Output(UInt(log2Up(RES_MEM_DEPTH).W))
    val res_valid = Output(Bool())
    val res_last = Output(Bool())
    val res_ready = Input(Bool())
  })

  // ========================================
  // 模块实例化 - 按流水级顺序
  // ========================================
  val ln_addr_gen = Module(new LNAddrGen)     // 地址生成
  val layernorm = Module(new LayerNormQ)       // S1: LayerNorm
  val qkvlinear = Module(new QKVLinear)       // S2: QKV 映射
  val atten = Module(new Atten)               // S3-6: Attention
  val outlinear = Module(new OutLinearFP32)       // S7: 输出映射
  val resadd = Module(new ResAddFP32)             // S7-Res: 残差连接 1
  val layernorm2 = Module(new LayerNormQ)      // S8: LayerNorm2
  val ffnup = Module(new FFNUp)               // S9: FFN Up (H→4H, ReLU fused)
  val ffndown = Module(new FFNDownFP32)           // S10: FFN Down (4H→H)
  val resadd2 = Module(new ResAdd2FP32)           // S12: 残差连接 2

  // ========================================
  // LNAddrGen 连接
  // ========================================
  val input_adapter_ready = Wire(Bool())
  ln_addr_gen.io.cfg_seqlen := io.cfg_seqlen
  ln_addr_gen.io.cfg_prefill := io.cfg_prefill
  ln_addr_gen.io.cfg_valid := io.cfg_valid
  ln_addr_gen.io.data_ready := io.data_in_ready
  ln_addr_gen.io.adapter_ready := input_adapter_ready
  io.data_in_addr := ln_addr_gen.io.mem_addr

  // ========================================
  // LayerNorm 连接 (S1)
  // ========================================
  layernorm.io.data_in := io.data_in
  layernorm.io.data_in_st := ln_addr_gen.io.data_st
  layernorm.io.data_addr := ln_addr_gen.io.data_addr
  layernorm.io.data_valid := ln_addr_gen.io.data_valid
  layernorm.io.data_last := ln_addr_gen.io.data_last

  layernorm.io.w_in := io.ln_w_in
  layernorm.io.w_valid := io.ln_w_valid
  layernorm.io.out_inv_scale := io.ln1_out_inv_scale
  layernorm.io.out_zero_point := io.ln1_out_zero_point
  layernorm.io.res_ready := qkvlinear.io.data_ready

  // ========================================
  // QKVLinear 连接 (S2)
  // ========================================
  qkvlinear.io.layer_st := io.layer_st
  qkvlinear.io.cfg_seqlen := io.cfg_seqlen
  qkvlinear.io.cfg_prefill := io.cfg_prefill
  qkvlinear.io.cfg_valid := io.cfg_valid
  qkvlinear.io.cfg_single_query := io.attn_cfg_single_query

  qkvlinear.io.data_in := layernorm.io.res
  qkvlinear.io.data_in_st := layernorm.io.res_st
  qkvlinear.io.data_addr := layernorm.io.res_addr
  qkvlinear.io.data_valid := layernorm.io.res_valid
  qkvlinear.io.data_last := layernorm.io.res_last

  // 权重初始化接口
  qkvlinear.io.weight_init_mode := io.weight_init_mode
  qkvlinear.io.weight_init_data := io.qkv_w_in
  qkvlinear.io.bias_init_data := io.qkv_b_in
  qkvlinear.io.bias_init_valid := io.qkv_b_valid
  qkvlinear.io.q_out_inv_scale := io.q_out_inv_scale
  qkvlinear.io.k_out_inv_scale := io.k_out_inv_scale
  qkvlinear.io.v_out_inv_scale := io.v_out_inv_scale
  qkvlinear.io.q_bias_scale := io.q_bias_scale
  qkvlinear.io.k_bias_scale := io.k_bias_scale
  qkvlinear.io.v_bias_scale := io.v_bias_scale
  qkvlinear.io.data_out_ready := atten.io.data_ready

  io.qkv_w_addr := qkvlinear.io.weight_init_addr

  // ========================================
  // Attention 连接 (S3-6)
  // ========================================
  atten.io.cfg_valid := io.attn_cfg_valid
  atten.io.cfg_prefill := io.attn_cfg_prefill
  atten.io.cfg_seqlen := io.attn_cfg_seqlen(log2Up(DM.Param.MAX_SEQLEN) - 1, 0)
  atten.io.cfg_single_query := io.attn_cfg_single_query
  atten.io.layer_st := io.layer_st
  atten.io.dm1_out_scale := io.dm1_out_scale
  atten.io.dm2_ctx_inv_scale := io.dm2_ctx_inv_scale
  atten.io.dm2_ctx_zero_point := io.dm2_ctx_zero_point
  atten.io.dm2_out_inv_scale := io.dm2_out_inv_scale
  atten.io.w_in := io.sm_w_in

  atten.io.data_in_st := qkvlinear.io.data_out_st
  atten.io.data_in := qkvlinear.io.data_out
  atten.io.data_addr := qkvlinear.io.data_out_addr
  atten.io.data_valid := qkvlinear.io.data_out_valid
  atten.io.data_last := qkvlinear.io.data_out_last
  atten.io.res_ready := outlinear.io.data_ready

  io.sm_w_addr := atten.io.w_addr

  // ========================================
  // OutLinear 连接 (S7)
  // ========================================
  outlinear.io.layer_st := io.layer_st
  outlinear.io.cfg_seqlen := io.cfg_seqlen
  outlinear.io.cfg_prefill := io.cfg_prefill
  outlinear.io.cfg_valid := io.cfg_valid

  outlinear.io.data_in := atten.io.res
  outlinear.io.data_in_st := atten.io.res_st
  outlinear.io.data_in_addr := atten.io.res_addr
  outlinear.io.data_in_valid := atten.io.res_valid
  outlinear.io.data_in_last := atten.io.res_last

  outlinear.io.weight_init_mode := io.weight_init_mode
  outlinear.io.weight_init_data := io.out_w_in
  outlinear.io.bias_init_data := io.out_b_in
  outlinear.io.bias_init_valid := io.out_b_valid
  outlinear.io.out_scale := io.out_out_scale
  io.out_w_addr := outlinear.io.weight_init_addr

  val outToResQ = Module(new Queue(new Bundle {
    val data = UInt(FP32_PACK_WIDTH.W)
    val st = Bool()
    val addr = UInt(log2Up(RES_MEM_DEPTH).W)
    val last = Bool()
  }, RES_MEM_DEPTH))

  outToResQ.io.enq.valid := outlinear.io.data_out_valid
  outToResQ.io.enq.bits.data := outlinear.io.data_out
  outToResQ.io.enq.bits.st := outlinear.io.data_out_st
  outToResQ.io.enq.bits.addr := outlinear.io.data_out_addr(log2Up(RES_MEM_DEPTH) - 1, 0)
  outToResQ.io.enq.bits.last := outlinear.io.data_out_last
  outlinear.io.data_out_ready := outToResQ.io.enq.ready

  // ========================================
  // ResAdd 连接 (S7-Res: 残差连接 1)
  // ========================================
  resadd.io.cfg_seqlen := io.cfg_seqlen
  resadd.io.cfg_prefill := io.cfg_prefill
  resadd.io.cfg_valid := io.cfg_valid

  resadd.io.orig_in := RegNext(io.data_in, 0.U(FP32_PACK_WIDTH.W))
  resadd.io.orig_in_st := RegNext(ln_addr_gen.io.data_st, false.B)
  resadd.io.orig_in_addr := RegNext(ln_addr_gen.io.mem_addr(log2Up(RES_MEM_DEPTH) - 1, 0), 0.U)
  resadd.io.orig_in_valid := RegNext(ln_addr_gen.io.data_valid, false.B)
  resadd.io.orig_in_last := RegNext(ln_addr_gen.io.data_last, false.B)

  resadd.io.dm2_in := outToResQ.io.deq.bits.data
  resadd.io.dm2_in_st := outToResQ.io.deq.bits.st
  resadd.io.dm2_in_addr := outToResQ.io.deq.bits.addr
  resadd.io.dm2_in_valid := outToResQ.io.deq.valid
  resadd.io.dm2_in_last := outToResQ.io.deq.bits.last
  outToResQ.io.deq.ready := resadd.io.dm2_ready
  // ResAdd 输出需要同时送给 LayerNorm2 和 ResAdd2，反压信号取 AND
  resadd.io.res_ready := layernorm2.io.data_ready && resadd2.io.s7_ready
  // 原始输入会同时送到 LayerNorm 和 ResAdd residual-cache，两个分叉必须共享同一个 ready。
  // 否则当 ResAdd cache 满时，这一路会静默丢 beat，后续读到未写槽位并在 LN2 处放大成 NaN。
  input_adapter_ready := layernorm.io.data_ready && resadd.io.orig_ready

  // ========================================
  // LayerNorm2 连接 (S8: 第二个层归一化)
  // ========================================
  layernorm2.io.data_in := resadd.io.res
  layernorm2.io.data_in_st := resadd.io.res_st
  layernorm2.io.data_addr := resadd.io.res_addr
  layernorm2.io.data_valid := resadd.io.res_valid
  layernorm2.io.data_last := resadd.io.res_last

  layernorm2.io.w_in := io.ln2_w_in
  layernorm2.io.w_valid := io.ln2_w_valid
  layernorm2.io.out_inv_scale := io.ln2_out_inv_scale
  layernorm2.io.out_zero_point := io.ln2_out_zero_point
  layernorm2.io.res_ready := ffnup.io.data_ready

  // ========================================
  // FFNUp 连接 (S9: H→4H 线性投影)
  // ========================================
  ffnup.io.layer_st := io.layer_st
  ffnup.io.cfg_seqlen := io.cfg_seqlen
  ffnup.io.cfg_prefill := io.cfg_prefill
  ffnup.io.cfg_valid := io.cfg_valid

  ffnup.io.data_in := layernorm2.io.res
  ffnup.io.data_in_st := layernorm2.io.res_st
  ffnup.io.data_addr := layernorm2.io.res_addr
  ffnup.io.data_valid := layernorm2.io.res_valid
  ffnup.io.data_last := layernorm2.io.res_last

  ffnup.io.weight_init_mode := io.weight_init_mode
  ffnup.io.weight_init_data := io.ffnup_w_in
  ffnup.io.bias_init_data := io.ffnup_b_in
  ffnup.io.bias_init_valid := io.ffnup_b_valid
  ffnup.io.out_inv_scale := io.ffnup_out_inv_scale
  ffnup.io.bias_scale := io.ffnup_bias_scale
  ffnup.io.data_out_ready := ffndown.io.data_ready

  io.ffnup_w_addr := ffnup.io.weight_init_addr

  // ========================================
  // FFNDown 连接 (ReLU 已融合进 FFNUp)
  // ========================================
  ffndown.io.layer_st := io.layer_st
  ffndown.io.cfg_seqlen := io.cfg_seqlen
  ffndown.io.cfg_prefill := io.cfg_prefill
  ffndown.io.cfg_valid := io.cfg_valid

  ffndown.io.data_in := ffnup.io.data_out
  ffndown.io.data_in_st := ffnup.io.data_out_st
  ffndown.io.data_addr := ffnup.io.data_out_addr
  ffndown.io.data_valid := ffnup.io.data_out_valid
  ffndown.io.data_last := ffnup.io.data_out_last

  ffndown.io.weight_init_mode := io.weight_init_mode
  ffndown.io.weight_init_data := io.ffndown_w_in
  ffndown.io.bias_init_data := io.ffndown_b_in
  ffndown.io.bias_init_valid := io.ffndown_b_valid
  ffndown.io.out_scale := io.ffndown_out_scale
  ffndown.io.data_out_ready := resadd2.io.ffn_ready

  io.ffndown_w_addr := ffndown.io.weight_init_addr

  // ========================================
  // ResAdd2 连接 (S12: 残差连接 2)
  // ========================================
  resadd2.io.cfg_seqlen := io.cfg_seqlen
  resadd2.io.cfg_prefill := io.cfg_prefill
  resadd2.io.cfg_valid := io.cfg_valid

  // S7-ResAdd 输出 -> ResAdd2 缓存
  resadd2.io.s7_in := resadd.io.res
  resadd2.io.s7_in_st := resadd.io.res_st
  resadd2.io.s7_in_addr := resadd.io.res_addr
  resadd2.io.s7_in_valid := resadd.io.res_valid
  resadd2.io.s7_in_last := resadd.io.res_last

  // FFNDown 输出 -> ResAdd2
  resadd2.io.ffn_in := ffndown.io.data_out
  resadd2.io.ffn_in_st := ffndown.io.data_out_st
  resadd2.io.ffn_in_addr := ffndown.io.data_out_addr
  resadd2.io.ffn_in_valid := ffndown.io.data_out_valid
  resadd2.io.ffn_in_last := ffndown.io.data_out_last

  resadd2.io.res_ready := io.res_ready

  // ========================================
  // 输出连接（从 ResAdd2）
  // ========================================
  io.res := resadd2.io.res
  io.res_st := resadd2.io.res_st
  io.res_addr := resadd2.io.res_addr
  io.res_valid := resadd2.io.res_valid
  io.res_last := resadd2.io.res_last
}

object AttenTopGen extends App {
  emitVerilog(new Top, Array("--target-dir", "generated"))
}
