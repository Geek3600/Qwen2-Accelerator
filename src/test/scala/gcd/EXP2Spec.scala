package exp2

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class EXP2Spec extends AnyFreeSpec with Matchers {
 "Exp should calculate proper greatest common denominator" in {
    simulate(new Exp2(2, 8, 8)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.clock.step()
      dut.io.in(0).poke("b100000000".U(16.W).asSInt)
      dut.io.in(1).poke("b1000000000".U(16.W).asSInt)
      dut.clock.step()
      dut.clock.step()
      val out1 = dut.io.out(0).peek().litValue
      val out2 = dut.io.out(1).peek().litValue
      println(s"[TEST] out1 = ${out1.toString(2)}")
      println(s"[TEST] out2 = ${out2.toString(2)}")
    }
  }
}



