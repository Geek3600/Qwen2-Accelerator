package GeLU

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class Log2Spec extends AnyFreeSpec with Matchers {
 "Log2 should calculate proper greatest common denominator" in {
    simulate(new Log2(1)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.clock.step()
      dut.io.in(0).poke("b11001101110".U(16.W).asSInt)
      dut.clock.step()
      dut.clock.step()
      val out = dut.io.out(0).peek().litValue
      println(s"[TEST] out = ${out.toString(2)}")
    }
  }
}



