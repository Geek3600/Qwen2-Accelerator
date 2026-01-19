package Softmax

import Param._
import Softmax._
import chisel3._
import chisel3.util._
import chisel3.experimental._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class SoftmaxSpec extends AnyFreeSpec with Matchers {
 "Top should calculate proper greatest common denominator" in {
    simulate(new Sum(V)) { dut =>
      // 复位
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.clock.step() 
      
      val inputData = Seq(1, 2, 3, 4)
      dut.io.in.zip(inputData).foreach {case(in, data) => in.poke(data.S)}
      dut.clock.step()
      dut.clock.step()
      dut.clock.step()
      dut.clock.step()

    }
  }
}



