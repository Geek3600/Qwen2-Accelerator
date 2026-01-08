package GeLU

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class GELUSpec extends AnyFreeSpec with Matchers {
 "GELU should calculate proper greatest common denominator" in {
    simulate(new GELU) { dut =>
      dut.io.in(0).poke(2.S)
      // dut.io.in(1).poke(2.S)
      dut.clock.step()
      val out1 = dut.io.out(0).peek().litValue
      // val out2 = dut.io.out(1).peek().litValue
      // println(s"[TEST] out1 = ${out1.toString(2)}")
      // println(s"[TEST] out2 = ${out2.toString(2)}")
    }
  }
}



