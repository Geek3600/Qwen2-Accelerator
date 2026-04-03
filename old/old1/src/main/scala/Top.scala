import DM.Param._
import LayerNorm.LayerNorm
import Softmax.Param.{WMEM_DEPTH => SM_WMEM_DEPTH, WMEM_WIDTH => SM_WMEM_WIDTH}
import QKVLinear.{QKVLinear, Param => QKVParam}
import OutLinear.{OutLinear, Param => OutParam}
import ResAdd.ResAdd
import ResAdd.Param.{DATA_WIDTH => RES_DATA_WIDTH, MEM_DEPTH => RES_MEM_DEPTH}
import FFNUp.{FFNUp, Param => FFNUpParam}
import GeLU.GELU
import FFNDown.{FFNDown, Param => FFNDownParam}
import ResAdd2.ResAdd2
import TempAdapter.LNAddrGen
import chisel3._
import chisel3.util._

// 顶层模块：整合完整的 Transformer Block 流水线
// 数据流:
// 输入 -> LayerNorm -> QKVLinear -> Atten -> OutLinear -> ResAdd (S7)
//          └────────────────────────────────────────────────────────┘ 残差1
//                                                                    ↓
//                                              LayerNorm2 (S8) -> FFNUp (S9) -> GELU (S10) -> FFNDown (S11) -> ResAdd2 (S12) -> 输出
//                                                                                                                  ↑
//                                                                                                            S7 输出
class Top extends Module{
  val io = IO(new Bundle() {
    val layer_st = Input(Bool())
    val cfg_seqlen = Input(UInt(log2Up(MAX_SEQLEN).W))
    val cfg_prefill = Input(Bool())
    val cfg_valid = Input(Bool())

    // 权重初始化模式控制
    val weight_init_mode = Input(Bool())  // true: 初始化模式, false: 计算模式

    // 输入: 96-bit
    val data_in = Input(UInt(96.W))
    val data_in_ready = Input(Bool())
    val data_in_addr = Output(UInt(32.W))

    // LayerNorm 权重输入
    val ln_w_in = Input(UInt(96.W))
    val ln_w_valid = Input(Bool())

    // QKVLinear 权重输入/初始化
    val qkv_w_in = Input(UInt(QKVParam.WMEM_WIDTH.W))
    val qkv_w_addr = Output(UInt(log2Up(QKVParam.WMEM_DEPTH).W))

    // Softmax 权重输入
    val sm_w_in = Input(UInt(SM_WMEM_WIDTH.W))
    val sm_w_addr = Output(UInt(log2Up(SM_WMEM_DEPTH).W))

    // OutLinear 权重输入/初始化
    val out_w_in = Input(UInt(OutParam.WMEM_WIDTH.W))
    val out_w_addr = Output(UInt(log2Up(OutParam.WMEM_DEPTH).W))

    // LayerNorm2 权重输入
    val ln2_w_in = Input(UInt(96.W))
    val ln2_w_valid = Input(Bool())

    // FFNUp 权重输入/初始化
    val ffnup_w_in = Input(UInt(FFNUpParam.WMEM_WIDTH.W))
    val ffnup_w_addr = Output(UInt(log2Up(FFNUpParam.WMEM_DEPTH).W))

    // FFNDown 权重输入/初始化
    val ffndown_w_in = Input(UInt(FFNDownParam.WMEM_WIDTH.W))
    val ffndown_w_addr = Output(UInt(log2Up(FFNDownParam.WMEM_DEPTH).W))

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
  val layernorm = Module(new LayerNorm)       // S1: LayerNorm
  val qkvlinear = Module(new QKVLinear)       // S2: QKV 映射
  val atten = Module(new Atten)               // S3-6: Attention
  val outlinear = Module(new OutLinear)       // S7: 输出映射
  val resadd = Module(new ResAdd)             // S7-Res: 残差连接 1
  val layernorm2 = Module(new LayerNorm)      // S8: LayerNorm2
  val ffnup = Module(new FFNUp)               // S9: FFN Up (H→4H)
  val gelu = Module(new GELU)                 // S10: GELU 激活
  val ffndown = Module(new FFNDown)           // S11: FFN Down (4H→H)
  val resadd2 = Module(new ResAdd2)           // S12: 残差连接 2

  // ========================================
  // LNAddrGen 连接
  // ========================================
  ln_addr_gen.io.cfg_seqlen := io.cfg_seqlen
  ln_addr_gen.io.cfg_prefill := io.cfg_prefill
  ln_addr_gen.io.cfg_valid := io.cfg_valid
  ln_addr_gen.io.data_ready := io.data_in_ready
  ln_addr_gen.io.adapter_ready := qkvlinear.io.data_ready
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
  layernorm.io.res_ready := qkvlinear.io.data_ready

  // ========================================
  // QKVLinear 连接 (S2)
  // ========================================
  qkvlinear.io.layer_st := io.layer_st
  qkvlinear.io.cfg_seqlen := io.cfg_seqlen
  qkvlinear.io.cfg_prefill := io.cfg_prefill
  qkvlinear.io.cfg_valid := io.cfg_valid

  qkvlinear.io.data_in := layernorm.io.res
  qkvlinear.io.data_in_st := layernorm.io.res_st
  qkvlinear.io.data_addr := layernorm.io.res_addr
  qkvlinear.io.data_valid := layernorm.io.res_valid
  qkvlinear.io.data_last := layernorm.io.res_last

  // 权重初始化接口
  qkvlinear.io.weight_init_mode := io.weight_init_mode
  qkvlinear.io.weight_init_data := io.qkv_w_in
  qkvlinear.io.data_out_ready := atten.io.data_ready

  io.qkv_w_addr := qkvlinear.io.weight_init_addr

  // ========================================
  // Attention 连接 (S3-6)
  // ========================================
  atten.io.cfg_valid := io.cfg_valid
  atten.io.cfg_prefill := io.cfg_prefill
  atten.io.cfg_seqlen := io.cfg_seqlen
  atten.io.layer_st := io.layer_st
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
  io.out_w_addr := outlinear.io.weight_init_addr

  // ========================================
  // Head 累加器 (OutLinear -> ResAdd 之间)
  // ========================================
  val HEAD_NUM = OutParam.HEAD_NUM
  val WMEM_DEPTH_PER_HEAD = OutParam.WMEM_DEPTH_PER_HEAD
  val ACC_DEPTH = RES_MEM_DEPTH

  val head_cnt = RegInit(0.U(log2Up(HEAD_NUM).W))
  val head_last = head_cnt === (HEAD_NUM - 1).U

  when(outlinear.io.data_out_valid && outlinear.io.data_out_last) {
    when(head_last) {
      head_cnt := 0.U
    }.otherwise {
      head_cnt := head_cnt + 1.U
    }
  }

  io.out_w_addr := outlinear.io.weight_init_addr + head_cnt * WMEM_DEPTH_PER_HEAD.U

  val ACC_WIDTH = 16
  val ACC_ELEM = 12
  val ACC_TOTAL_WIDTH = ACC_WIDTH * ACC_ELEM
  val acc_mem = SyncReadMem(ACC_DEPTH, UInt(ACC_TOTAL_WIDTH.W))

  val ol_valid = outlinear.io.data_out_valid
  val ol_data = outlinear.io.data_out
  val ol_addr = outlinear.io.data_out_addr
  val ol_st = outlinear.io.data_out_st
  val ol_last = outlinear.io.data_out_last
  val ol_head_cnt = head_cnt
  val ol_head_last = head_last

  val acc_rd = acc_mem.read(ol_addr, ol_valid)

  val s1_valid = RegNext(ol_valid, false.B)
  val s1_data = RegNext(ol_data)
  val s1_addr = RegNext(ol_addr)
  val s1_st = RegNext(ol_st && ol_valid, false.B)
  val s1_last = RegNext(ol_last && ol_valid, false.B)
  val s1_head_cnt = RegNext(ol_head_cnt)
  val s1_head_last = RegNext(ol_head_last && ol_valid, false.B)

  val ol_elems = s1_data.asTypeOf(Vec(ACC_ELEM, SInt(8.W)))
  val acc_elems = acc_rd.asTypeOf(Vec(ACC_ELEM, SInt(ACC_WIDTH.W)))

  val new_acc = Wire(Vec(ACC_ELEM, SInt(ACC_WIDTH.W)))
  for (i <- 0 until ACC_ELEM) {
    when(s1_head_cnt === 0.U) {
      new_acc(i) := ol_elems(i)
    }.otherwise {
      new_acc(i) := acc_elems(i) + ol_elems(i)
    }
  }

  when(s1_valid) {
    acc_mem.write(s1_addr, new_acc.asUInt)
  }

  val sat_elems = Wire(Vec(ACC_ELEM, SInt(8.W)))
  for (i <- 0 until ACC_ELEM) {
    val v = new_acc(i)
    sat_elems(i) := Mux(v > 127.S, 127.S, Mux(v < (-128).S, (-128).S, v(7, 0).asSInt))
  }

  outlinear.io.data_out_ready := Mux(head_last, resadd.io.dm2_ready, true.B)

  // ========================================
  // ResAdd 连接 (S7-Res: 残差连接 1)
  // ========================================
  resadd.io.cfg_seqlen := io.cfg_seqlen
  resadd.io.cfg_prefill := io.cfg_prefill
  resadd.io.cfg_valid := io.cfg_valid

  resadd.io.orig_in := io.data_in
  resadd.io.orig_in_st := ln_addr_gen.io.data_st
  resadd.io.orig_in_addr := RegNext(ln_addr_gen.io.mem_addr(log2Up(RES_MEM_DEPTH) - 1, 0))
  resadd.io.orig_in_valid := ln_addr_gen.io.data_valid
  resadd.io.orig_in_last := ln_addr_gen.io.data_last

  resadd.io.dm2_in := sat_elems.asUInt
  resadd.io.dm2_in_st := s1_st && s1_head_last
  resadd.io.dm2_in_addr := s1_addr
  resadd.io.dm2_in_valid := s1_valid && s1_head_last
  resadd.io.dm2_in_last := s1_last && s1_head_last
  // ResAdd 输出需要同时送给 LayerNorm2 和 ResAdd2，反压信号取 AND
  resadd.io.res_ready := layernorm2.io.data_ready && resadd2.io.s7_ready

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
  ffnup.io.data_out_ready := gelu.io.data_ready

  io.ffnup_w_addr := ffnup.io.weight_init_addr

  // ========================================
  // GELU 连接 (S10: GELU 激活函数)
  // ========================================
  gelu.io.cfg_seqlen := io.cfg_seqlen
  gelu.io.cfg_prefill := io.cfg_prefill
  gelu.io.cfg_valid := io.cfg_valid

  gelu.io.data_in := ffnup.io.data_out
  gelu.io.data_in_st := ffnup.io.data_out_st
  gelu.io.data_in_addr := ffnup.io.data_out_addr
  gelu.io.data_in_valid := ffnup.io.data_out_valid
  gelu.io.data_in_last := ffnup.io.data_out_last

  gelu.io.data_out_ready := ffndown.io.data_ready

  // ========================================
  // FFNDown 连接 (S11: 4H→H 线性投影)
  // ========================================
  ffndown.io.layer_st := io.layer_st
  ffndown.io.cfg_seqlen := io.cfg_seqlen
  ffndown.io.cfg_prefill := io.cfg_prefill
  ffndown.io.cfg_valid := io.cfg_valid

  ffndown.io.data_in := gelu.io.data_out
  ffndown.io.data_in_st := gelu.io.data_out_st
  ffndown.io.data_addr := gelu.io.data_out_addr
  ffndown.io.data_valid := gelu.io.data_out_valid
  ffndown.io.data_last := gelu.io.data_out_last

  ffndown.io.weight_init_mode := io.weight_init_mode
  ffndown.io.weight_init_data := io.ffndown_w_in
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
