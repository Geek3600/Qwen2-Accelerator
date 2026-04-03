import DM.Param._
import DM.DM1
import DM2.DM2
import DM2.Param.{HEAD_VECNUM => DM2_HEAD_VECNUM}
import ResMEM.VCache
import Softmax.Param.{WMEM_DEPTH, WMEM_WIDTH}
import Softmax.SoftmaxPip
import chisel3._
import chisel3.util._

// Attention 模块：整合 DM1 -> Softmax -> VCache -> DM2 流水线
class Atten extends Module {
val io = IO(new Bundle() {
  val layer_st = Input(Bool())
  val cfg_seqlen = Input(UInt(log2Up( MAX_SEQLEN).W))
  val cfg_prefill = Input(Bool())
  val cfg_valid = Input(Bool())

  val w_in = Input(UInt(WMEM_WIDTH.W))
  val w_addr = Output(UInt(log2Up(WMEM_DEPTH).W))

  val data_in_st = Input(Bool())
  val data_in = Input(UInt((3 * LOAD_VECNUM * DATAW).W))
  val data_addr = Input(UInt(log2Up(HEAD_VECNUM / LOAD_VECNUM * BATCHSIZE).W))
  val data_valid = Input(Bool())
  val data_last = Input(Bool())
  val data_ready = Output(Bool())

  // DM2 输出: 512-bit (64 elements * 8-bit)
  val res = Output(UInt((DM2_HEAD_VECNUM * DATAW).W))
  val res_st = Output(Bool())
  val res_addr = Output(UInt(log2Up(MEM_DEPTH).W))
  val res_valid = Output(Bool())
  val res_last = Output(Bool())
  val res_ready = Input(Bool())
})

  // ========================================
  // 模块实例化 - 按流水级顺序
  // ========================================
  val dm1 = Module(new DM1)           // 流水级3: Q·K 点积
  val softmax = Module(new SoftmaxPip)   // 流水级4: Softmax归一化
  val vcache = Module(new VCache)     // 流水级5: V向量缓存
  val dm2 = Module(new DM2)           // 流水级6: Ctx·V 点积

  // ========================================
  // DM1 连接 (Q·K 点积)
  // ========================================
  dm1.io.cfg_seqlen := io.cfg_seqlen
  dm1.io.cfg_valid := io.cfg_valid
  dm1.io.cfg_prefill := io.cfg_prefill

  dm1.io.data_in_st := io.data_in_st
  dm1.io.data_in := io.data_in(2*LOAD_VECNUM * DATAW - 1 , 0) // QK数据
  dm1.io.data_addr := io.data_addr
  dm1.io.data_valid := io.data_valid
  dm1.io.data_last := io.data_last
  dm1.io.res_ready := softmax.io.data_ready

  // ========================================
  // Softmax 连接
  // ========================================
  softmax.io.cfg_seqlen := io.cfg_seqlen
  softmax.io.cfg_valid := io.cfg_valid
  softmax.io.cfg_prefill := io.cfg_prefill

  softmax.io.layer_st := io.layer_st
  softmax.io.data_in_st := dm1.io.res_st
  softmax.io.data_in := dm1.io.res
  softmax.io.data_addr := dm1.io.res_addr
  softmax.io.data_valid := dm1.io.res_valid
  softmax.io.data_last := dm1.io.res_last
  softmax.io.res_ready := dm2.io.data_in_ctx_ready

  softmax.io.w_in := io.w_in
  io.w_addr := softmax.io.w_addr

  // ========================================
  // VCache 连接 (V向量缓存)
  // ========================================
  vcache.io.cfg_valid := io.cfg_valid
  vcache.io.cfg_seqlen := io.cfg_seqlen
  vcache.io.cfg_prefill := io.cfg_prefill

  vcache.io.data_in_st := io.data_in_st
  vcache.io.data_in := io.data_in(3 * LOAD_VECNUM * DATAW - 1, 2 * LOAD_VECNUM * DATAW) // V数据
  vcache.io.data_in_addr := io.data_addr
  vcache.io.data_in_valid := io.data_valid
  vcache.io.data_in_last := io.data_last
  vcache.io.res_ready := dm2.io.data_in_v_ready

  // ========================================
  // DM2 连接 (Ctx·V 点积)
  // ========================================
  dm2.io.cfg_valid := io.cfg_valid
  dm2.io.cfg_seqlen := io.cfg_seqlen
  dm2.io.cfg_prefill := io.cfg_prefill

  // Softmax 结果 -> DM2 ctx 输入
  dm2.io.data_in_ctx_st := softmax.io.res_st
  dm2.io.data_in_ctx := softmax.io.res
  dm2.io.data_in_ctx_addr := softmax.io.res_addr
  dm2.io.data_in_ctx_valid := softmax.io.res_valid
  dm2.io.data_in_ctx_last := softmax.io.res_last

  // VCache 结果 -> DM2 v 输入
  dm2.io.data_in_v_st := vcache.io.res_st
  dm2.io.data_in_v := vcache.io.res
  dm2.io.data_in_v_addr := vcache.io.res_addr
  dm2.io.data_in_v_valid := vcache.io.res_valid
  dm2.io.data_in_v_last := vcache.io.res_last
  dm2.io.res_ready := io.res_ready

  // ========================================
  // 输出连接
  // ========================================
  io.data_ready := vcache.io.data_in_ready && dm1.io.data_ready

  io.res := dm2.io.res
  io.res_st := dm2.io.res_st
  io.res_addr := dm2.io.res_addr
  io.res_valid := dm2.io.res_valid
  io.res_last := dm2.io.res_last
}
