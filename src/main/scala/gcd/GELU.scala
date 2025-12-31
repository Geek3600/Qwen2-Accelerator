package gelu

import chisel3._
import chisel3.util._
import exp2._
import log2._
import _root_.circt.stage.ChiselStage

class GELU(val V: Int, val BW: Int, val DW: Int) extends Module {
  val io = IO(new Bundle {
    val in  = Input(Vec(V, SInt(DW.W)))  
    val out = Output(Vec(V, SInt(DW.W))) 
  })
    val fracWidth = 8 // 小数部分位宽
    val intWidth = DW
    val fixWidth = intWidth + fracWidth

    val beta_plus = "b0000000001000001".U(fixWidth.W) // 0.254 = 0.25390625
    val beta_sub = 0.S(fixWidth.W) // 0
    val alpha_log2e = "b0000001010010010".U(fixWidth.W) // 1.782*1.4427 = 2.57031250

    val fixInput = Wire(Vec(V, SInt(fixWidth.W)))
    val log2 = Module(new Log2(V, intWidth, fracWidth))
    val exp2_1 = Module(new Exp2(V, intWidth, fracWidth))
    val exp2_2 = Module(new Exp2(V, intWidth, fracWidth))
    val isPositive = Wire(Vec(V, Bool()))
    val xg = Wire(Vec(V, SInt(fixWidth.W)))
    val xg_mul_const = Wire(Vec(V, SInt((fixWidth).W)))
    val xg_mul_const1 = Wire(Vec(V, SInt((2*fixWidth).W)))

    for (i <- 0 until V) {
        isPositive(i) := io.in(i) > 0.S
        // 先转成定点数
        fixInput(i) := Cat(io.in(i).asUInt, 0.U(fracWidth.W)).asSInt
    }

    for (i <- 0 until V) {
        // 判断是否大于0
        xg(i) := Mux(isPositive(i), 
                     fixInput(i) - beta_plus.asSInt,
                     fixInput(i) + beta_sub.asSInt)
        xg_mul_const1(i) := xg(i) * alpha_log2e
        xg_mul_const(i) := xg_mul_const1(i)(fracWidth+fixWidth-1, fracWidth).asSInt    
    }

    exp2_1.io.in := xg_mul_const
    val exg = Wire(Vec(V, SInt(fixWidth.W)))
    exg := exp2_1.io.out

    val axg = Wire(Vec(V, SInt(fixWidth.W)))
    for (i <- 0 until V) {
        axg(i) := exg(i) + (1 << fracWidth).S
    }

    log2.io.in := axg
    val lxg = Wire(Vec(V, SInt(fixWidth.W)))
    lxg := log2.io.out

    val xg_hat = Wire(Vec(V, SInt(fixWidth.W)))
    for (i <- 0 until V) {
        xg_hat(i) := 0.S - lxg(i)
    }

    val sigma_opt = Wire(Vec(V, SInt(fixWidth.W)))
    exp2_2.io.in := xg_hat
    sigma_opt := exp2_2.io.out
    for (i <- 0 until V) {
        io.out(i) := sigma_opt(i) * fixInput(i)
    }
    printf(p"[GELU] in=${Binary(io.in(0))} fixInput=${Binary(fixInput(0))} isPositive=${isPositive(0)} beta_plus=${Binary(beta_plus.asSInt)} xg=${Binary(xg(0))} xg_mul_const=${Binary(xg_mul_const(0))} out=${Binary(io.out(0))}\n")
}

object GELU extends App {
    ChiselStage.emitSystemVerilogFile(
        new GELU(1, 8, 8),
        firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
    )
}