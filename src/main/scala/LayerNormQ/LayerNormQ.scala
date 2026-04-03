package LayerNormQ

import LayerNormQ.Param._
import QuantCommon._
import QuantCommon.Pack._
import QuantCommon.Precision._
import chisel3._
import chisel3.util._

class Fp32LaneSum extends Module {
  val io = IO(new Bundle() {
    val in = Input(UInt(FP_PACK_WIDTH.W))
    val out = Output(UInt(FP32_WIDTH.W))
  })

  val inVec = asFp32Vec(io.in)

  val add01 = Module(new Fp32Add)
  val add23 = Module(new Fp32Add)
  val add45 = Module(new Fp32Add)
  val add67 = Module(new Fp32Add)
  val add89 = Module(new Fp32Add)
  val add1011 = Module(new Fp32Add)

  add01.io.a := inVec(0)
  add01.io.b := inVec(1)
  add23.io.a := inVec(2)
  add23.io.b := inVec(3)
  add45.io.a := inVec(4)
  add45.io.b := inVec(5)
  add67.io.a := inVec(6)
  add67.io.b := inVec(7)
  add89.io.a := inVec(8)
  add89.io.b := inVec(9)
  add1011.io.a := inVec(10)
  add1011.io.b := inVec(11)

  val add0123 = Module(new Fp32Add)
  val add4567 = Module(new Fp32Add)
  val add891011 = Module(new Fp32Add)

  add0123.io.a := add01.io.out
  add0123.io.b := add23.io.out
  add4567.io.a := add45.io.out
  add4567.io.b := add67.io.out
  add891011.io.a := add89.io.out
  add891011.io.b := add1011.io.out

  val add0to7 = Module(new Fp32Add)
  val addAll = Module(new Fp32Add)

  add0to7.io.a := add0123.io.out
  add0to7.io.b := add4567.io.out
  addAll.io.a := add0to7.io.out
  addAll.io.b := add891011.io.out

  io.out := addAll.io.out
}

class Fp32LaneSquareSum extends Module {
  val io = IO(new Bundle() {
    val in = Input(UInt(FP_PACK_WIDTH.W))
    val out = Output(UInt(FP32_WIDTH.W))
  })

  val inVec = asFp32Vec(io.in)
  val sqVec = Wire(Vec(LANE_NUM, UInt(FP32_WIDTH.W)))

  for (i <- 0 until LANE_NUM) {
    val mul = Module(new Fp32Mul)
    mul.io.a := inVec(i)
    mul.io.b := inVec(i)
    sqVec(i) := mul.io.out
  }

  val sum = Module(new Fp32LaneSum)
  sum.io.in := sqVec.asUInt
  io.out := sum.io.out
}

class Fp32LayerNormApply extends Module {
  val io = IO(new Bundle() {
    val in = Input(UInt(FP_PACK_WIDTH.W))
    val mean = Input(UInt(FP32_WIDTH.W))
    val invStd = Input(UInt(FP32_WIDTH.W))
    val gamma = Input(UInt(FP_PACK_WIDTH.W))
    val beta = Input(UInt(FP_PACK_WIDTH.W))
    val out = Output(UInt(FP_PACK_WIDTH.W))
  })

  val inVec = asFp32Vec(io.in)
  val gammaVec = asFp32Vec(io.gamma)
  val betaVec = asFp32Vec(io.beta)
  val outVec = Wire(Vec(LANE_NUM, UInt(FP32_WIDTH.W)))

  for (i <- 0 until LANE_NUM) {
    val sub = Module(new Fp32Sub)
    val normMul = Module(new Fp32Mul)
    val scaleMul = Module(new Fp32Mul)
    val add = Module(new Fp32Add)

    sub.io.a := inVec(i)
    sub.io.b := io.mean
    normMul.io.a := sub.io.out
    normMul.io.b := io.invStd
    scaleMul.io.a := normMul.io.out
    scaleMul.io.b := gammaVec(i)
    add.io.a := scaleMul.io.out
    add.io.b := betaVec(i)

    outVec(i) := add.io.out
  }

  io.out := outVec.asUInt
}

// LayerNormQ: FP32 输入、FP32 gamma/beta、INT8 输出
// 最小实现：逐 token 顺序处理，优先保证功能闭环
class LayerNormQ extends Module {
  private def fp32Const(value: Float): UInt =
    java.lang.Float.floatToRawIntBits(value).U(FP32_WIDTH.W)

  val io = IO(new Bundle() {
    val data_in_st = Input(Bool())
    val data_in = Input(UInt(FP_PACK_WIDTH.W))
    val data_addr = Input(UInt(log2Up(MEM_DEPTH).W))
    val data_valid = Input(Bool())
    val data_last = Input(Bool())
    val data_ready = Output(Bool())

    // 权重流：前 64 拍 beta，后 64 拍 gamma
    val w_in = Input(UInt(FP_PACK_WIDTH.W))
    val w_valid = Input(Bool())

    // 输出量化参数（per-tensor）
    val out_inv_scale = Input(UInt(FP32_WIDTH.W))
    val out_zero_point = Input(SInt(INT8_WIDTH.W))

    val res = Output(UInt(INT_PACK_WIDTH.W))
    val res_st = Output(Bool())
    val res_addr = Output(UInt(log2Up(MEM_DEPTH).W))
    val res_valid = Output(Bool())
    val res_last = Output(Bool())
    val res_ready = Input(Bool())
  })

  val inputMem = SyncReadMem(MEM_DEPTH, UInt(FP_PACK_WIDTH.W))
  val betaVec = Reg(Vec(VECTOR_BEATS, UInt(FP_PACK_WIDTH.W)))
  val gammaVec = Reg(Vec(VECTOR_BEATS, UInt(FP_PACK_WIDTH.W)))

  val weightsLoaded = RegInit(false.B)
  val weightCnt = RegInit(0.U(log2Up(VECTOR_BEATS * 2).W))
  val betaIdx = weightCnt(log2Up(VECTOR_BEATS) - 1, 0)
  val gammaIdx = (weightCnt - VECTOR_BEATS.U)(log2Up(VECTOR_BEATS) - 1, 0)
  when(io.w_valid) {
    when(weightCnt < VECTOR_BEATS.U) {
      betaVec(betaIdx) := io.w_in
    }.otherwise {
      gammaVec(gammaIdx) := io.w_in
    }
    when(weightCnt === (VECTOR_BEATS * 2 - 1).U) {
      weightsLoaded := true.B
      weightCnt := 0.U
    }.otherwise {
      weightCnt := weightCnt + 1.U
    }
  }

  val inputLoaded = RegInit(false.B)
  val tokenCount = RegInit(0.U(log2Up(BATCHSIZE + 1).W))
  when(io.data_valid) {
    inputMem.write(io.data_addr, io.data_in)
    when(io.data_last) {
      inputLoaded := true.B
      tokenCount := io.data_addr(log2Up(MEM_DEPTH) - 1, log2Up(VECTOR_BEATS)) + 1.U
    }
  }

  val idle :: statRead :: statDrain :: meanVar :: sqrtReq :: sqrtWait :: divReq :: divWait :: outRead :: outDrain :: Nil = Enum(10)
  val state = RegInit(idle)

  val tokenIdx = RegInit(0.U(log2Up(BATCHSIZE).W))
  val vecIdx = RegInit(0.U(log2Up(VECTOR_BEATS).W))

  val sumAcc = RegInit(0.U(FP32_WIDTH.W))
  val sqSumAcc = RegInit(0.U(FP32_WIDTH.W))
  val meanReg = RegInit(0.U(FP32_WIDTH.W))
  val varReg = RegInit(0.U(FP32_WIDTH.W))
  val sqrtReg = RegInit(0.U(FP32_WIDTH.W))
  val invStdReg = RegInit(0.U(FP32_WIDTH.W))

  val statAddr = tokenIdx * VECTOR_BEATS.U + vecIdx
  val statReadEn = state === statRead
  val statData = inputMem.read(statAddr, statReadEn)
  val statValid = RegNext(statReadEn, false.B)
  val statFirst = RegNext(state === statRead && vecIdx === 0.U, false.B)
  val statLast = RegNext(state === statRead && vecIdx === (VECTOR_BEATS - 1).U, false.B)

  val laneSum = Module(new Fp32LaneSum)
  laneSum.io.in := statData
  val laneSqSum = Module(new Fp32LaneSquareSum)
  laneSqSum.io.in := statData

  val sumAdd = Module(new Fp32Add)
  sumAdd.io.a := sumAcc
  sumAdd.io.b := laneSum.io.out
  val sqAdd = Module(new Fp32Add)
  sqAdd.io.a := sqSumAcc
  sqAdd.io.b := laneSqSum.io.out

  when(state === idle && inputLoaded && weightsLoaded) {
    tokenIdx := 0.U
    vecIdx := 0.U
    sumAcc := 0.U
    sqSumAcc := 0.U
    state := statRead
  }

  when(state === statRead) {
    when(vecIdx === (VECTOR_BEATS - 1).U) {
      state := statDrain
    }.otherwise {
      vecIdx := vecIdx + 1.U
    }
  }

  when(statValid) {
    sumAcc := Mux(statFirst, laneSum.io.out, sumAdd.io.out)
    sqSumAcc := Mux(statFirst, laneSqSum.io.out, sqAdd.io.out)
  }

  val meanMul = Module(new Fp32Mul)
  meanMul.io.a := sumAcc
  meanMul.io.b := fp32Const(1.0f / VECTOR.toFloat)

  val meanSqMul = Module(new Fp32Mul)
  meanSqMul.io.a := sqSumAcc
  meanSqMul.io.b := fp32Const(1.0f / VECTOR.toFloat)

  val meanPow2 = Module(new Fp32Mul)
  meanPow2.io.a := meanMul.io.out
  meanPow2.io.b := meanMul.io.out

  val varSub = Module(new Fp32Sub)
  varSub.io.a := meanSqMul.io.out
  varSub.io.b := meanPow2.io.out

  val varAddEps = Module(new Fp32Add)
  varAddEps.io.a := varSub.io.out
  varAddEps.io.b := fp32Const(1.0e-5f)

  val sqrt = Module(new Fp32DivSqrt)
  sqrt.io.sqrtOp := true.B
  sqrt.io.a := varReg
  sqrt.io.b := 0.U
  sqrt.io.inValid := state === sqrtReq

  val div = Module(new Fp32DivSqrt)
  div.io.sqrtOp := false.B
  div.io.a := fp32Const(1.0f)
  div.io.b := sqrtReg
  div.io.inValid := state === divReq

  switch(state) {
    is(statDrain) {
      when(statLast) {
        state := meanVar
      }
    }
    is(meanVar) {
      meanReg := meanMul.io.out
      varReg := varAddEps.io.out
      state := sqrtReq
    }
    is(sqrtReq) {
      when(sqrt.io.inReady) {
        state := sqrtWait
      }
    }
    is(sqrtWait) {
      when(sqrt.io.outValidSqrt) {
        sqrtReg := sqrt.io.out
        state := divReq
      }
    }
    is(divReq) {
      when(div.io.inReady) {
        state := divWait
      }
    }
    is(divWait) {
      when(div.io.outValidDiv) {
        invStdReg := div.io.out
        vecIdx := 0.U
        state := outRead
      }
    }
  }

  val outAddr = tokenIdx * VECTOR_BEATS.U + vecIdx
  val outReadEn = state === outRead
  val outData = inputMem.read(outAddr, outReadEn)
  val outValid = RegNext(outReadEn, false.B)
  val outFirst = RegNext(state === outRead && vecIdx === 0.U, false.B)
  val outLast = RegNext(state === outRead && vecIdx === (VECTOR_BEATS - 1).U, false.B)
  val outAddrReg = RegNext(outAddr)
  val gammaReg = RegEnable(gammaVec(vecIdx), outReadEn)
  val betaReg = RegEnable(betaVec(vecIdx), outReadEn)

  val applyNorm = Module(new Fp32LayerNormApply)
  applyNorm.io.in := outData
  applyNorm.io.mean := meanReg
  applyNorm.io.invStd := invStdReg
  applyNorm.io.gamma := gammaReg
  applyNorm.io.beta := betaReg

  val quant = Module(new Fp32QuantizeToInt8Pack)
  quant.io.in := applyNorm.io.out
  quant.io.invScale := io.out_inv_scale
  quant.io.zeroPoint := io.out_zero_point

  when(state === outRead) {
    when(vecIdx === (VECTOR_BEATS - 1).U) {
      state := outDrain
    }.otherwise {
      vecIdx := vecIdx + 1.U
    }
  }

  when(state === outDrain && outLast) {
    when(tokenIdx === tokenCount - 1.U) {
      inputLoaded := false.B
      state := idle
    }.otherwise {
      tokenIdx := tokenIdx + 1.U
      vecIdx := 0.U
      sumAcc := 0.U
      sqSumAcc := 0.U
      state := statRead
    }
  }

  io.data_ready := state === idle
  io.res := quant.io.out
  io.res_valid := outValid
  io.res_st := outValid && outFirst
  io.res_last := outValid && outLast && tokenIdx === (tokenCount - 1.U)
  io.res_addr := outAddrReg
}

object LayerNormQGen extends App {
  emitVerilog(new LayerNormQ, Array("--target-dir", "generated"))
}
