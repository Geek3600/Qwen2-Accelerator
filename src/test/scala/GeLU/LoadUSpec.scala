package GeLU

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class LoadUSpec extends AnyFreeSpec with Matchers {
 "LoadU should calculate proper greatest common denominator" in {
    simulate(new LoadU) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.clock.step() 
      dut.io.data_in_ready.poke(true.B)
      dut.io.data_out_ready.poke(true.B)
      dut.clock.step()
      for (i <- 0 until 100) {
        dut.clock.step()
      }

    }
  }
}



