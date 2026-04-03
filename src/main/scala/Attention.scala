import DM.Param._
import DM.DM1FP32
import DM2.DM2Quant
import DM2.Param.{HEAD_VECNUM => DM2_HEAD_VECNUM}
import QuantCommon.Precision.FP32_WIDTH
import QuantCommon.Precision.UINT8_WIDTH
import ResMEM.VCache
import Softmax.Param.{WMEM_DEPTH, WMEM_WIDTH}
import Softmax.SoftmaxPipFP32
import chisel3._
import chisel3.util._

// Attention 模块：整合 DM1 -> Softmax -> VCache -> DM2 流水线
class Atten extends Module {
  class Dm2Beat extends Bundle {
    val data = UInt((DM2_HEAD_VECNUM * DATAW).W)
    val st = Bool()
    val addr = UInt(log2Up(MEM_DEPTH).W)
    val last = Bool()
  }

  class Dm2CtxBeat extends Bundle {
    val data = UInt((MAX_SEQLEN * FP32_WIDTH).W)
    val st = Bool()
    val addr = UInt(log2Up(MEM_DEPTH).W)
    val last = Bool()
  }

  val io = IO(new Bundle() {
    val layer_st = Input(Bool())
    val cfg_seqlen = Input(UInt(log2Up( MAX_SEQLEN).W))
    val cfg_prefill = Input(Bool())
    val cfg_valid = Input(Bool())
    val cfg_single_query = Input(Bool())

  val dm1_out_scale = Input(UInt(FP32_WIDTH.W))
  val dm2_ctx_inv_scale = Input(UInt(FP32_WIDTH.W))
  val dm2_ctx_zero_point = Input(UInt(UINT8_WIDTH.W))
  val dm2_out_inv_scale = Input(UInt(FP32_WIDTH.W))

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
  val dm1 = Module(new DM1FP32)           // 流水级3: Q·K 点积
  val softmax = Module(new SoftmaxPipFP32)   // 流水级4: Softmax归一化
  val vcache = Module(new VCache)     // 流水级5: V向量缓存
  val dm2 = Module(new DM2Quant)           // 流水级6: Ctx·V 点积
  // Single-query decode can burst a full batch window of V vectors before DM2
  // drains them. Keep enough slack to avoid dropping late lbatch entries.
  val ctxToDm2Q = Module(new Queue(new Dm2CtxBeat, 16))
  val vToDm2Q = Module(new Queue(new Dm2Beat, 16))
  val input_ready = Wire(Bool())
  val input_fire = io.data_valid && input_ready

  // ========================================
  // DM1 连接 (Q·K 点积)
  // ========================================
  dm1.io.cfg_seqlen := io.cfg_seqlen
  dm1.io.cfg_valid := io.cfg_valid
  dm1.io.cfg_prefill := io.cfg_prefill
  dm1.io.cfg_single_query := io.cfg_single_query
  dm1.io.out_scale := io.dm1_out_scale

  dm1.io.data_in_st := input_fire && io.data_in_st
  dm1.io.data_in := io.data_in(2*LOAD_VECNUM * DATAW - 1 , 0) // QK数据
  dm1.io.data_addr := io.data_addr
  dm1.io.data_valid := input_fire
  dm1.io.data_last := input_fire && io.data_last
  dm1.io.res_ready := softmax.io.data_ready

  // DM1 的 res_st 来自其内部 LoadUnit 起拍，远早于真实输出写到 Softmax 的时刻。
  // Softmax 的输入写起拍必须跟 DM1 的真实输出首拍对齐，而 mask/load 的启动应在
  // 整个 DM1 输出块写完后再开始，才能与 standalone softmax harness 语义一致。
  val dm1OutStart = dm1.io.res_valid && dm1.io.res_addr === 0.U
  val dm1OutDone = dm1.io.res_valid && dm1.io.res_last
  val dm1OutDoneD1 = RegNext(dm1OutDone, false.B)
  val softmaxNegInf = "hff7fff8b".U(FP32_WIDTH.W)
  val dm1SoftmaxIn = Wire(Vec(MAX_SEQLEN, UInt(FP32_WIDTH.W)))
  private val dm1ResVec = dm1.io.res.asTypeOf(Vec(MAX_SEQLEN, UInt(FP32_WIDTH.W)))
  for (i <- 0 until MAX_SEQLEN) {
    val maskFuturePrefill = io.cfg_prefill && (i.U > dm1.io.res_addr)
    val maskFutureDecode = io.cfg_single_query && (i.U > io.cfg_seqlen)
    dm1SoftmaxIn(i) := Mux(maskFuturePrefill || maskFutureDecode, softmaxNegInf, dm1ResVec(i))
  }

  // ========================================
  // Softmax 连接
  // ========================================
  softmax.io.cfg_seqlen := io.cfg_seqlen
  softmax.io.cfg_valid := io.cfg_valid
  softmax.io.cfg_prefill := io.cfg_prefill
  softmax.io.cfg_single_query := io.cfg_single_query
  // standalone softmax harness 是“最后一拍数据写完后的下一拍”再启动 mask 流。
  // 这里保持同样语义，避免首个输出窗口时 mask 行号超前 1 拍。
  softmax.io.layer_st := dm1OutDoneD1
  // DataMem 的 w_st 必须对应 DM1 真实输出首拍，而不是 DM1 内部 LoadUnit 的起拍。
  softmax.io.data_in_st := dm1OutStart
  // 验证数据里的 softmax.input 已经把未来不可见位置写成了负无穷近似值。
  // top 集成这里也保持同样输入语义，避免把 DM1 未覆盖的 0 当成可见 logits。
  softmax.io.data_in := dm1SoftmaxIn.asUInt
  softmax.io.data_addr := dm1.io.res_addr
  softmax.io.data_valid := dm1.io.res_valid
  softmax.io.data_last := dm1.io.res_last
  softmax.io.res_ready := ctxToDm2Q.io.enq.ready

  ctxToDm2Q.io.enq.valid := softmax.io.res_valid
  ctxToDm2Q.io.enq.bits.data := softmax.io.res
  ctxToDm2Q.io.enq.bits.st := softmax.io.res_st
  ctxToDm2Q.io.enq.bits.addr := softmax.io.res_addr
  ctxToDm2Q.io.enq.bits.last := softmax.io.res_last

  softmax.io.w_in := io.w_in
  io.w_addr := softmax.io.w_addr

  // ========================================
  // VCache 连接 (V向量缓存)
  // ========================================
  vcache.io.cfg_valid := io.cfg_valid
  vcache.io.cfg_seqlen := io.cfg_seqlen
  vcache.io.cfg_prefill := io.cfg_prefill
  vcache.io.cfg_single_query := io.cfg_single_query
  vcache.io.data_in_st := input_fire && io.data_in_st
  vcache.io.data_in := io.data_in(3 * LOAD_VECNUM * DATAW - 1, 2 * LOAD_VECNUM * DATAW) // V数据
  vcache.io.data_in_addr := io.data_addr
  vcache.io.data_in_valid := input_fire
  vcache.io.data_in_last := input_fire && io.data_last
  vcache.io.res_ready := vToDm2Q.io.enq.ready

  vToDm2Q.io.enq.valid := vcache.io.res_valid
  vToDm2Q.io.enq.bits.data := vcache.io.res
  vToDm2Q.io.enq.bits.st := vcache.io.res_st
  vToDm2Q.io.enq.bits.addr := vcache.io.res_addr
  vToDm2Q.io.enq.bits.last := vcache.io.res_last

  // ========================================
  // DM2 连接 (Ctx·V 点积)
  // ========================================
  dm2.io.cfg_valid := io.cfg_valid
  dm2.io.cfg_seqlen := io.cfg_seqlen
  dm2.io.cfg_prefill := io.cfg_prefill
  dm2.io.cfg_single_query := io.cfg_single_query
  dm2.io.ctx_inv_scale := io.dm2_ctx_inv_scale
  dm2.io.ctx_zero_point := io.dm2_ctx_zero_point
  dm2.io.out_inv_scale := io.dm2_out_inv_scale

  // Softmax 结果 -> DM2 ctx 输入
  dm2.io.data_in_ctx_st := ctxToDm2Q.io.deq.valid && ctxToDm2Q.io.deq.bits.st
  dm2.io.data_in_ctx := ctxToDm2Q.io.deq.bits.data
  dm2.io.data_in_ctx_addr := ctxToDm2Q.io.deq.bits.addr
  dm2.io.data_in_ctx_valid := ctxToDm2Q.io.deq.valid
  dm2.io.data_in_ctx_last := ctxToDm2Q.io.deq.valid && ctxToDm2Q.io.deq.bits.last
  ctxToDm2Q.io.deq.ready := dm2.io.data_in_ctx_ready

  // VCache 结果 -> DM2 v 输入
  dm2.io.data_in_v_st := vToDm2Q.io.deq.valid && vToDm2Q.io.deq.bits.st
  dm2.io.data_in_v := vToDm2Q.io.deq.bits.data
  dm2.io.data_in_v_addr := vToDm2Q.io.deq.bits.addr
  dm2.io.data_in_v_valid := vToDm2Q.io.deq.valid
  dm2.io.data_in_v_last := vToDm2Q.io.deq.valid && vToDm2Q.io.deq.bits.last
  vToDm2Q.io.deq.ready := dm2.io.data_in_v_ready
  dm2.io.res_ready := io.res_ready

  // ========================================
  // 输出连接
  // ========================================
  input_ready := vcache.io.data_in_ready && dm1.io.data_ready
  io.data_ready := input_ready

  io.res := dm2.io.res
  io.res_st := dm2.io.res_st
  io.res_addr := dm2.io.res_addr
  io.res_valid := dm2.io.res_valid
  io.res_last := dm2.io.res_last
}
