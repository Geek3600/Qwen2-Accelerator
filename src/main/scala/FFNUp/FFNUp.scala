package FFNUp

import FFNUp._
import QuantCommon.{Fp32Add, Fp32Mul, Fp32ToSInt, Int32ToFp32, Int8ToFp32}
import QuantCommon.Precision._
import chisel3._
import chisel3.util._
import FFNUp.Param._

// FFNUp: 前��网络上投影模块
// 功能: 将 768 维向量映射为 3072 维向量 (H → 4H)
// 输入: 96-bit/cycle (12 elements) from LayerNorm2
// 输出: 96-bit/cycle (12 elements) to FFNDown
//
// 矩阵乘法: X(768) × W(768×3072) = Y(3072)
class FFNUp extends Module {
  val io = IO(new Bundle() {
    val layer_st = Input(Bool())

    // 来自 LayerNorm2 的输入
    val data_in_st = Input(Bool())
    val data_in = Input(UInt(MEM_WIDTH.W))
    val data_addr = Input(UInt(log2Up(MEM_DEPTH).W))
    val data_valid = Input(Bool())
    val data_last = Input(Bool())
    val data_ready = Output(Bool())

    // 权重初始化接口
    val weight_init_mode = Input(Bool())
    val weight_init_data = Input(UInt(WMEM_WIDTH.W))
    val weight_init_addr = Output(UInt(log2Up(WMEM_DEPTH).W))
    val bias_init_data = Input(UInt(MEM_WIDTH.W))
    val bias_init_valid = Input(Bool())
    val out_inv_scale = Input(UInt(FP32_WIDTH.W))
    val bias_scale = Input(UInt(FP32_WIDTH.W))

    // 配置信号
    val cfg_prefill = Input(Bool())
    val cfg_seqlen = Input(UInt(log2Up(MAX_SEQLEN).W))
    val cfg_valid = Input(Bool())

    // 输出到 FFNDown (ReLU 已融合)
    val data_out = Output(UInt(MEM_WIDTH.W))
    val data_out_st = Output(Bool())
    val data_out_addr = Output(UInt(log2Up(MEM_DEPTH).W))
    val data_out_valid = Output(Bool())
    val data_out_last = Output(Bool())
    val data_out_ready = Input(Bool())
  })

  // ========================================
  // Linear 计算核心
  // ========================================
  val mem_inst = Module(new DataMem(MEM_DEPTH, MEM_WIDTH))
  val lu_inst = Module(new LoadUnit)
  val cu_inst = Module(new CUQuant)
  val su_inst = Module(new StoreUnit)
  val lw_inst = Module(new LoadWeight)
  val bias_mem = RegInit(VecInit(Seq.fill(COL_W / ROW)(0.U(MEM_WIDTH.W))))
  val bias_init_cnt = RegInit(0.U(log2Up(COL_W / ROW).W))

  when(io.bias_init_valid) {
    bias_mem(bias_init_cnt) := io.bias_init_data
    bias_init_cnt := Mux(bias_init_cnt === (COL_W / ROW - 1).U, 0.U, bias_init_cnt + 1.U)
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

  val su_vec = su_inst.io.data_out.asTypeOf(Vec(ROW, SInt(INT32_WIDTH.W)))
  val bias_vec = bias_mem(su_inst.io.data_out_addr(log2Up(COL_W / ROW) - 1, 0)).asTypeOf(Vec(ROW, SInt(DATAW.W)))
  val relu_vec = Wire(Vec(ROW, UInt(DATAW.W)))
  for (i <- 0 until ROW) {
    val accToFp = Module(new Int32ToFp32)
    val accMul = Module(new Fp32Mul)
    val biasToFp = Module(new Int8ToFp32)
    val biasMul = Module(new Fp32Mul)
    val add = Module(new Fp32Add)
    val toInt = Module(new Fp32ToSInt(INT32_WIDTH))

    accToFp.io.in := su_vec(i)
    accMul.io.a := accToFp.io.out
    accMul.io.b := io.out_inv_scale
    biasToFp.io.in := bias_vec(i)
    biasMul.io.a := biasToFp.io.out
    biasMul.io.b := io.bias_scale
    add.io.a := accMul.io.out
    add.io.b := biasMul.io.out
    toInt.io.in := add.io.out

    val sum = toInt.io.out.asSInt
    val maxVal = ((1 << (DATAW - 1)) - 1).S
    val minVal = (-(1 << (DATAW - 1))).S
    val sat = Mux(sum > maxVal, maxVal, Mux(sum < minVal, minVal, sum(DATAW - 1, 0).asSInt))
    relu_vec(i) := Mux(sat < 0.S, 0.U, sat.asUInt)
  }

  // ========================================
  // 输出信号
  // ========================================
  io.data_out := relu_vec.asUInt
  io.data_out_valid := su_inst.io.data_out_valid
  io.data_out_addr := su_inst.io.data_out_addr
  io.data_out_st := su_inst.io.data_out_st
  io.data_out_last := su_inst.io.data_out_last
}

object FFNUpGen extends App {
  emitVerilog(new FFNUp, Array("--target-dir", "generated"))
}
