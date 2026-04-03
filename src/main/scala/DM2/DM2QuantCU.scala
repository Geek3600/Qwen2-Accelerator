package DM2

import DM2.Param._
import QuantCommon._
import QuantCommon.Precision._
import chisel3._
import chisel3.util._

class UnsignedSignedMultiplierInt8 extends Module {
  val io = IO(new Bundle() {
    val in0 = Input(UInt(UINT8_WIDTH.W))
    val in1 = Input(SInt(INT8_WIDTH.W))
    val out = Output(SInt(16.W))
  })

  val in0Reg = RegNext(io.in0)
  val in1Reg = RegNext(io.in1)
  io.out := RegNext((in0Reg.zext * in1Reg).asSInt)
}

class SignedAddTreeInt32(val num: Int) extends Module {
  val io = IO(new Bundle() {
    val ins = Input(Vec(num, SInt(16.W)))
    val out = Output(SInt(INT32_WIDTH.W))
  })

  def recTree(vals: Seq[SInt]): SInt = {
    if (vals.length == 1) vals.head
    else {
      val next = vals.grouped(2).map {
        case Seq(a, b) => RegNext((a +& b).asSInt)
        case Seq(a) => RegNext(a)
      }.toSeq
      recTree(next)
    }
  }

  io.out := recTree(io.ins)
}

class DM2CUQuant extends Module {
  import QuantParam._

  val io = IO(new Bundle() {
    val cfg_seqlen = Input(UInt(log2Up(MAX_SEQLEN).W))
    val cfg_prefill = Input(Bool())
    val cfg_valid = Input(Bool())
    val cfg_single_query = Input(Bool())

    val out_inv_scale = Input(UInt(FP32_WIDTH.W))

    val data_in_v = Input(UInt((HEAD_VECNUM * DATAW).W))
    val data_in_v_valid = Input(Bool())

    val data_in_ctx = Input(UInt(CTX_UINT8_WIDTH.W))
    val data_in_ctx_valid = Input(Bool())

    val data_out = Output(UInt((HEAD_VECNUM * DATAW).W))
    val data_out_valid = Output(Bool())
  })

  val prefill = RegEnable(io.cfg_prefill, io.cfg_valid)
  val singleQuery = RegEnable(io.cfg_single_query, false.B, io.cfg_valid)
  val seqlen = RegEnable(io.cfg_seqlen, io.cfg_valid)

  val vCache = Module(new R1W1Mem(LBATCHSIZE * MAX_SEQLEN, HEAD_VECNUM * DATAW))
  val vBuf = RegInit(VecInit(Seq.fill(MAX_SEQLEN)(0.U((HEAD_VECNUM * DATAW).W))))

  val prefillLoadCnt = RegInit(0.U(log2Up(MAX_PREFILL).W))
  val prefillLoadLast = prefillLoadCnt === seqlen
  when(prefill && io.data_in_v_valid) {
    prefillLoadCnt := Mux(prefillLoadLast, 0.U, prefillLoadCnt + 1.U)
  }

  val lbatchCnt = RegInit(0.U(log2Up(LBATCHSIZE).W))
  // Single-query decode only delivers one V batch per token through VCache/LoadU.
  // Keep DM2's internal lbatch rotation aligned with that 1-batch contract; the old
  // 12-batch rotation can leave stale slots resident until the very end of a 912-token run.
  val lbatchLast = lbatchCnt === Mux(
    singleQuery,
    0.U(lbatchCnt.getWidth.W),
    (LBATCHSIZE - 1).U(lbatchCnt.getWidth.W)
  )
  when(prefill && io.data_in_v_valid && prefillLoadLast || !prefill && io.data_in_v_valid) {
    lbatchCnt := Mux(lbatchLast, 0.U, lbatchCnt + 1.U)
  }

  vCache.io.wen := io.data_in_v_valid
  vCache.io.waddr := Mux(
    prefill,
    lbatchCnt * MAX_SEQLEN.U + prefillLoadCnt,
    lbatchCnt * MAX_SEQLEN.U + seqlen
  )
  vCache.io.wdata := io.data_in_v

  val stcVcache :: stcGetv :: stcWaitctx :: stcC :: Nil = Enum(4)
  val state = RegInit(stcVcache)
  val isGetv = state === stcGetv
  val isWaitctx = state === stcWaitctx
  val isC = state === stcC

  val gevCnt = RegInit(0.U(log2Up(MAX_SEQLEN).W))
  val gevLast = gevCnt === seqlen
  when(isGetv) {
    gevCnt := Mux(gevLast, 0.U, gevCnt + 1.U)
  }

  val waitctxCnt = RegInit(0.U(log2Up(MAX_PREFILL).W))
  val waitctxLast = waitctxCnt === seqlen
  when(prefill && io.data_in_ctx_valid) {
    waitctxCnt := Mux(waitctxLast, 0.U, waitctxCnt + 1.U)
  }

  val mulCnt = RegInit(0.U(log2Up(HEAD_VECNUM).W))
  val mulLast = mulCnt === (HEAD_VECNUM - 1).U
  when(isC) {
    mulCnt := Mux(mulLast, 0.U, mulCnt + 1.U)
  }

  val stcVcacheMux = Mux(
    prefill,
    Mux(prefillLoadLast && io.data_in_v_valid, stcWaitctx, stcVcache),
    Mux(io.data_in_v_valid, stcGetv, stcVcache)
  )
  val stcGetvMux = Mux(gevLast, stcWaitctx, stcGetv)
  val stcWaitctxMux = Mux(io.data_in_ctx_valid, stcC, stcWaitctx)
  val stcCMux = Mux(
    mulLast,
    Mux(
      waitctxCnt === 0.U,
      // Reset to the explicit V-cache boundary after each completed decode
      // batch. Re-entering through stcVcacheMux lets the next token skip the
      // V-load phase and misaligns the internal V rotation.
      stcVcache,
      stcWaitctxMux
    ),
    stcC
  )

  state := MuxLookup(
    state,
    state
  )(
    List(
      stcVcache -> stcVcacheMux,
      stcGetv -> stcGetvMux,
      stcWaitctx -> stcWaitctxMux,
      stcC -> stcCMux
    )
  )

  val lbatchCntReg = RegEnable(
    lbatchCnt,
    prefill && io.data_in_v_valid && prefillLoadLast || !prefill && io.data_in_v_valid
  )
  vCache.io.ren := isGetv
  vCache.io.raddr := gevCnt + lbatchCntReg * MAX_SEQLEN.U

  for (i <- 0 until MAX_SEQLEN) {
    val validDecode = RegNext(isGetv && gevCnt === i.U, false.B)
    val validPrefill = prefill && io.data_in_v_valid && prefillLoadCnt === i.U
    when(validPrefill || validDecode) {
      vBuf(i) := Mux(prefill, io.data_in_v, vCache.io.rdata)
    }
  }

  val vList = (0 until MAX_SEQLEN).map { i =>
    MuxLookup(
      mulCnt,
      0.U(DATAW.W)
    )(
      (0 until HEAD_VECNUM).map { j =>
        j.U -> vBuf(i)(DATAW * (j + 1) - 1, DATAW * j)
      }
    ).asSInt
  }

  val ctx = RegInit(0.U(CTX_UINT8_WIDTH.W))
  when(io.data_in_ctx_valid) {
    ctx := io.data_in_ctx
  }
  val ctxList = (0 until MAX_SEQLEN).map { i =>
    Mux(seqlen >= i.U, ctx((i + 1) * UINT8_WIDTH - 1, i * UINT8_WIDTH), 0.U(UINT8_WIDTH.W))
  }

  val mulList = List.fill(MAX_SEQLEN)(Module(new UnsignedSignedMultiplierInt8))
  val addTree = Module(new SignedAddTreeInt32(MAX_SEQLEN))
  for (i <- 0 until MAX_SEQLEN) {
    mulList(i).io.in0 := ctxList(i)
    mulList(i).io.in1 := vList(i)
    addTree.io.ins(i) := mulList(i).io.out
  }

  val delayMulCntToAddRes = 2 + log2Up(MAX_SEQLEN)
  val addresValidPipe = Module(new PipReg(delayMulCntToAddRes, 1))
  addresValidPipe.io.in := (state === stcC).asUInt
  val addresValid = addresValidPipe.io.out.asBool

  val addresCntPipe = Module(new PipReg(delayMulCntToAddRes, log2Up(HEAD_VECNUM)))
  addresCntPipe.io.in := mulCnt
  val addresCnt = addresCntPipe.io.out

  val toFp = Module(new Int32ToFp32)
  val mulScale = Module(new Fp32Mul)
  val toInt8 = Module(new Fp32ToInt8)
  toFp.io.in := addTree.io.out
  mulScale.io.a := toFp.io.out
  mulScale.io.b := io.out_inv_scale
  toInt8.io.in := mulScale.io.out

  val res = RegInit(VecInit(Seq.fill(HEAD_VECNUM)(0.U(DATAW.W))))
  for (i <- 0 until HEAD_VECNUM) {
    when(addresValid && addresCnt === i.U) {
      res(i) := toInt8.io.out
    }
  }

  io.data_out := res.asUInt
  io.data_out_valid := RegNext(addresValid && addresCnt === (HEAD_VECNUM - 1).U, false.B)
}
