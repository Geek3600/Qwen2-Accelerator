package QuantCommon

import QuantCommon.Precision._
import chisel3._
import hardfloat._

class Fp32Add extends Module {
  val io = IO(new Bundle() {
    val a = Input(UInt(FP32_WIDTH.W))
    val b = Input(UInt(FP32_WIDTH.W))
    val out = Output(UInt(FP32_WIDTH.W))
    val exceptionFlags = Output(UInt(5.W))
  })

  val add = Module(new AddRecFN(FP32_EXP_WIDTH, FP32_SIG_WIDTH))
  add.io.subOp := false.B
  add.io.a := recFNFromFN(FP32_EXP_WIDTH, FP32_SIG_WIDTH, io.a)
  add.io.b := recFNFromFN(FP32_EXP_WIDTH, FP32_SIG_WIDTH, io.b)
  add.io.roundingMode := ROUNDING_MODE
  add.io.detectTininess := DETECT_TININESS.asBool

  io.out := fNFromRecFN(FP32_EXP_WIDTH, FP32_SIG_WIDTH, add.io.out)
  io.exceptionFlags := add.io.exceptionFlags
}

class Fp32Sub extends Module {
  val io = IO(new Bundle() {
    val a = Input(UInt(FP32_WIDTH.W))
    val b = Input(UInt(FP32_WIDTH.W))
    val out = Output(UInt(FP32_WIDTH.W))
    val exceptionFlags = Output(UInt(5.W))
  })

  val sub = Module(new AddRecFN(FP32_EXP_WIDTH, FP32_SIG_WIDTH))
  sub.io.subOp := true.B
  sub.io.a := recFNFromFN(FP32_EXP_WIDTH, FP32_SIG_WIDTH, io.a)
  sub.io.b := recFNFromFN(FP32_EXP_WIDTH, FP32_SIG_WIDTH, io.b)
  sub.io.roundingMode := ROUNDING_MODE
  sub.io.detectTininess := DETECT_TININESS.asBool

  io.out := fNFromRecFN(FP32_EXP_WIDTH, FP32_SIG_WIDTH, sub.io.out)
  io.exceptionFlags := sub.io.exceptionFlags
}

class Fp32Mul extends Module {
  val io = IO(new Bundle() {
    val a = Input(UInt(FP32_WIDTH.W))
    val b = Input(UInt(FP32_WIDTH.W))
    val out = Output(UInt(FP32_WIDTH.W))
    val exceptionFlags = Output(UInt(5.W))
  })

  val mul = Module(new MulRecFN(FP32_EXP_WIDTH, FP32_SIG_WIDTH))
  mul.io.a := recFNFromFN(FP32_EXP_WIDTH, FP32_SIG_WIDTH, io.a)
  mul.io.b := recFNFromFN(FP32_EXP_WIDTH, FP32_SIG_WIDTH, io.b)
  mul.io.roundingMode := ROUNDING_MODE
  mul.io.detectTininess := DETECT_TININESS.asBool

  io.out := fNFromRecFN(FP32_EXP_WIDTH, FP32_SIG_WIDTH, mul.io.out)
  io.exceptionFlags := mul.io.exceptionFlags
}

class Fp32MulAdd extends Module {
  val io = IO(new Bundle() {
    val a = Input(UInt(FP32_WIDTH.W))
    val b = Input(UInt(FP32_WIDTH.W))
    val c = Input(UInt(FP32_WIDTH.W))
    val out = Output(UInt(FP32_WIDTH.W))
    val exceptionFlags = Output(UInt(5.W))
  })

  val mulAdd = Module(new MulAddRecFN(FP32_EXP_WIDTH, FP32_SIG_WIDTH))
  mulAdd.io.op := 0.U
  mulAdd.io.a := recFNFromFN(FP32_EXP_WIDTH, FP32_SIG_WIDTH, io.a)
  mulAdd.io.b := recFNFromFN(FP32_EXP_WIDTH, FP32_SIG_WIDTH, io.b)
  mulAdd.io.c := recFNFromFN(FP32_EXP_WIDTH, FP32_SIG_WIDTH, io.c)
  mulAdd.io.roundingMode := ROUNDING_MODE
  mulAdd.io.detectTininess := DETECT_TININESS

  io.out := fNFromRecFN(FP32_EXP_WIDTH, FP32_SIG_WIDTH, mulAdd.io.out)
  io.exceptionFlags := mulAdd.io.exceptionFlags
}

class Fp32Compare extends Module {
  val io = IO(new Bundle() {
    val a = Input(UInt(FP32_WIDTH.W))
    val b = Input(UInt(FP32_WIDTH.W))
    val lt = Output(Bool())
    val eq = Output(Bool())
    val gt = Output(Bool())
    val exceptionFlags = Output(UInt(5.W))
  })

  val cmp = Module(new CompareRecFN(FP32_EXP_WIDTH, FP32_SIG_WIDTH))
  cmp.io.a := recFNFromFN(FP32_EXP_WIDTH, FP32_SIG_WIDTH, io.a)
  cmp.io.b := recFNFromFN(FP32_EXP_WIDTH, FP32_SIG_WIDTH, io.b)
  cmp.io.signaling := false.B

  io.lt := cmp.io.lt
  io.eq := cmp.io.eq
  io.gt := cmp.io.gt
  io.exceptionFlags := cmp.io.exceptionFlags
}

class SIntToFp32(val intWidth: Int) extends Module {
  val io = IO(new Bundle() {
    val in = Input(SInt(intWidth.W))
    val out = Output(UInt(FP32_WIDTH.W))
    val exceptionFlags = Output(UInt(5.W))
  })

  val cvt = Module(new INToRecFN(intWidth, FP32_EXP_WIDTH, FP32_SIG_WIDTH))
  cvt.io.signedIn := true.B
  cvt.io.in := io.in.asUInt
  cvt.io.roundingMode := ROUNDING_MODE
  cvt.io.detectTininess := DETECT_TININESS

  io.out := fNFromRecFN(FP32_EXP_WIDTH, FP32_SIG_WIDTH, cvt.io.out)
  io.exceptionFlags := cvt.io.exceptionFlags
}

class UIntToFp32(val intWidth: Int) extends Module {
  val io = IO(new Bundle() {
    val in = Input(UInt(intWidth.W))
    val out = Output(UInt(FP32_WIDTH.W))
    val exceptionFlags = Output(UInt(5.W))
  })

  val cvt = Module(new INToRecFN(intWidth, FP32_EXP_WIDTH, FP32_SIG_WIDTH))
  cvt.io.signedIn := false.B
  cvt.io.in := io.in
  cvt.io.roundingMode := ROUNDING_MODE
  cvt.io.detectTininess := DETECT_TININESS

  io.out := fNFromRecFN(FP32_EXP_WIDTH, FP32_SIG_WIDTH, cvt.io.out)
  io.exceptionFlags := cvt.io.exceptionFlags
}

class Fp32ToSInt(val intWidth: Int) extends Module {
  val io = IO(new Bundle() {
    val in = Input(UInt(FP32_WIDTH.W))
    val out = Output(UInt(intWidth.W))
    val intExceptionFlags = Output(UInt(3.W))
  })

  val cvt = Module(new RecFNToIN(FP32_EXP_WIDTH, FP32_SIG_WIDTH, intWidth))
  cvt.io.in := recFNFromFN(FP32_EXP_WIDTH, FP32_SIG_WIDTH, io.in)
  cvt.io.roundingMode := ROUNDING_MODE
  cvt.io.signedOut := true.B

  io.out := cvt.io.out
  io.intExceptionFlags := cvt.io.intExceptionFlags
}

class Fp32ToUInt(val intWidth: Int) extends Module {
  val io = IO(new Bundle() {
    val in = Input(UInt(FP32_WIDTH.W))
    val out = Output(UInt(intWidth.W))
    val intExceptionFlags = Output(UInt(3.W))
  })

  val cvt = Module(new RecFNToIN(FP32_EXP_WIDTH, FP32_SIG_WIDTH, intWidth))
  cvt.io.in := recFNFromFN(FP32_EXP_WIDTH, FP32_SIG_WIDTH, io.in)
  cvt.io.roundingMode := ROUNDING_MODE
  cvt.io.signedOut := false.B

  io.out := cvt.io.out
  io.intExceptionFlags := cvt.io.intExceptionFlags
}

class Fp32DivSqrt extends Module {
  val io = IO(new Bundle() {
    val inValid = Input(Bool())
    val sqrtOp = Input(Bool())
    val a = Input(UInt(FP32_WIDTH.W))
    val b = Input(UInt(FP32_WIDTH.W))
    val inReady = Output(Bool())
    val outValidDiv = Output(Bool())
    val outValidSqrt = Output(Bool())
    val out = Output(UInt(FP32_WIDTH.W))
    val exceptionFlags = Output(UInt(5.W))
  })

  val divSqrt = Module(new DivSqrtRecFN_small(FP32_EXP_WIDTH, FP32_SIG_WIDTH, 0))
  divSqrt.io.inValid := io.inValid
  divSqrt.io.sqrtOp := io.sqrtOp
  divSqrt.io.a := recFNFromFN(FP32_EXP_WIDTH, FP32_SIG_WIDTH, io.a)
  divSqrt.io.b := recFNFromFN(FP32_EXP_WIDTH, FP32_SIG_WIDTH, io.b)
  divSqrt.io.roundingMode := ROUNDING_MODE
  divSqrt.io.detectTininess := DETECT_TININESS

  io.inReady := divSqrt.io.inReady
  io.outValidDiv := divSqrt.io.outValid_div
  io.outValidSqrt := divSqrt.io.outValid_sqrt
  io.out := fNFromRecFN(FP32_EXP_WIDTH, FP32_SIG_WIDTH, divSqrt.io.out)
  io.exceptionFlags := divSqrt.io.exceptionFlags
}

class Int8ToFp32 extends SIntToFp32(INT8_WIDTH)
class UInt8ToFp32 extends UIntToFp32(UINT8_WIDTH)
class Int32ToFp32 extends SIntToFp32(INT32_WIDTH)
class Fp32ToInt8 extends Fp32ToSInt(INT8_WIDTH)
class Fp32ToUInt8 extends Fp32ToUInt(UINT8_WIDTH)
