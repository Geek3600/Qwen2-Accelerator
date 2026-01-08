error id: file://<WORKSPACE>/src/main/scala/SiLU/SiLU.scala:`<none>`.
file://<WORKSPACE>/src/main/scala/SiLU/SiLU.scala
empty definition using pc, found symbol in pc: `<none>`.
empty definition using semanticdb
empty definition using fallback
non-local guesses:
	 -Common.Param.Vec.
	 -Common.Param.Vec#
	 -Common.Param.Vec().
	 -chisel3/Vec.
	 -chisel3/Vec#
	 -chisel3/Vec().
	 -chisel3/util/Vec.
	 -chisel3/util/Vec#
	 -chisel3/util/Vec().
	 -Vec.
	 -Vec#
	 -Vec().
	 -scala/Predef.Vec.
	 -scala/Predef.Vec#
	 -scala/Predef.Vec().
offset: 190
uri: file://<WORKSPACE>/src/main/scala/SiLU/SiLU.scala
text:
```scala
package SiLU

import Common.Param._
import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

class SiLU extends Module {
  val io = IO(new Bundle {
    val in  = Input(V@@ec(V, SInt(DATAW.W)))  
    val out = Output(Vec(V, SInt((DATAW).W))) 
  })
     
    val FRAC_WIDTH = 8 // 小数部分位宽
    val INT_WIDTH = DATAW // 整数部分位宽
    val FIXED_WIDTH = INT_WIDTH + FRAC_WIDTH // 定点数位宽 

    // 常量
    val beta_plus = "b0000000001000001".U(FIXED_WIDTH.W) // 0.254 = 0.25390625
    val beta_sub = 0.S(FIXED_WIDTH.W) // 0
    val alpha_log2e = "b0000001010010010".U(FIXED_WIDTH.W) // 1.782*1.4427 = 2.57031250

    val log2 = Module(new Log2(V, INT_WIDTH, FRAC_WIDTH))
    val exp2_1 = Module(new Exp2(V, INT_WIDTH, FRAC_WIDTH))
    val exp2_2 = Module(new Exp2(V, INT_WIDTH, FRAC_WIDTH))

    val fixInput = Wire(Vec(V, SInt(FIXED_WIDTH.W)))
    val isPositive = Wire(Vec(V, Bool()))
    val xg = Wire(Vec(V, SInt(FIXED_WIDTH.W)))
    val xg_mul_const_fixwidth = Wire(Vec(V, SInt((FIXED_WIDTH).W)))
    val xg_mul_const_fullwidth = Wire(Vec(V, SInt((2*FIXED_WIDTH).W)))
    val res_mul = Wire(Vec(V, SInt((2*FIXED_WIDTH).W)))
    val res = Wire(Vec(V, SInt((FIXED_WIDTH).W)))

    for (i <- 0 until V) {
        isPositive(i) := io.in(i) > 0.S
        // 先转成定点数
        fixInput(i) := Cat(io.in(i).asUInt, 0.U(FRAC_WIDTH.W)).asSInt
    }

    for (i <- 0 until V) {
        // 判断是否大于0
        xg(i) := Mux(isPositive(i), 
                     fixInput(i) - beta_plus.asSInt,
                     fixInput(i) + beta_sub.asSInt)
        xg_mul_const_fullwidth(i) := (-1).S * xg(i) * alpha_log2e
        xg_mul_const_fixwidth(i) := xg_mul_const_fullwidth(i)(FRAC_WIDTH+FIXED_WIDTH-1, FRAC_WIDTH).asSInt    
    }

    exp2_1.io.in := xg_mul_const_fixwidth
    val exg = Wire(Vec(V, SInt(FIXED_WIDTH.W)))
    exg := exp2_1.io.out

    val axg = Wire(Vec(V, SInt(FIXED_WIDTH.W)))
    for (i <- 0 until V) {
        axg(i) := exg(i) + (1 << FRAC_WIDTH).S
    }

    log2.io.in := axg
    val lxg = Wire(Vec(V, SInt(FIXED_WIDTH.W)))
    lxg := log2.io.out

    val xg_hat = Wire(Vec(V, SInt(FIXED_WIDTH.W)))
    for (i <- 0 until V) {
        xg_hat(i) := 0.S - lxg(i)
    }

    val sigma_opt = Wire(Vec(V, SInt(FIXED_WIDTH.W)))
    exp2_2.io.in := xg_hat
    sigma_opt := exp2_2.io.out
    for (i <- 0 until V) {
        res_mul(i) := sigma_opt(i) * fixInput(i)
        res(i) := res_mul(i)(FRAC_WIDTH+FIXED_WIDTH-1, FRAC_WIDTH).asSInt
    }

    // 反量化
    val maxInt8 = 127.S
    val minInt8 = (-128).S
    for (i <- 0 until V) {
        val y_round = res(i) + (1 << (FRAC_WIDTH - 1)).S // +0.5
        val y_int_tmp = y_round >> FRAC_WIDTH   // 舍入
        val y_int8 = Mux( // 饱和截断
             y_int_tmp > maxInt8, maxInt8,
             Mux(y_int_tmp < minInt8, minInt8, y_int_tmp)
        )
        io.out(i) := y_int8.asSInt
    }
    // printf(p"""
    // [GELU]
    // in           = ${Binary(io.in(0))}
    // fixInput     = ${Binary(fixInput(0))}
    // isPositive   = ${isPositive(0)}
    // beta_plus    = ${Binary(beta_plus.asSInt)}
    // xg           = ${Binary(xg(0))}
    // xg_mul_const_fixwidth = ${Binary(xg_mul_const_fixwidth(0))}
    // exg          = ${Binary(exg(0))}
    // axg          = ${Binary(axg(0))}
    // lxg          = ${Binary(lxg(0))}
    // xg_hat       = ${Binary(xg_hat(0))}
    // sigma_opt    = ${Binary(sigma_opt(0))}
    // out          = ${Binary(io.out(0))}
    // """)
}

object GELU extends App {
    ChiselStage.emitSystemVerilogFile(
        new GELU,
        firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
    )
}
```


#### Short summary: 

empty definition using pc, found symbol in pc: `<none>`.