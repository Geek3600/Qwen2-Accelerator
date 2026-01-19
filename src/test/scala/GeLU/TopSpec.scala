package GeLU

import Param._
import chisel3._
import GeLU._
import chisel3.util._
import chisel3.experimental._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class TopSpec extends AnyFreeSpec with Matchers {
 "Top should calculate proper greatest common denominator" in {
    simulate(new PipLine) { dut =>
      // 复位
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.clock.step() 

      dut.io.data_in_st.poke(true.B)
      dut.clock.step()
      dut.io.data_in_st.poke(false.B)
      dut.clock.step()
      for (i <- 0 until 32) {
        val x: BigInt = (0 until V).foldLeft(BigInt(0)) { (acc, i) =>(acc << 8) | BigInt(i + 1)}
        dut.io.data_in.poke(x)
        dut.io.data_valid.poke(true.B)
        dut.io.data_addr.poke((i*64).U)
        dut.clock.step()
      }
      dut.io.data_last.poke(true.B)
      dut.clock.step()
      dut.io.data_last.poke(false.B)
      dut.clock.step()
      for (i <- 0 until 34) {
        dut.io.res_ready.poke(true.B)
        dut.clock.step()
      }
    }
  }
}



