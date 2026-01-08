error id: file://<WORKSPACE>/src/test/scala/SiLU/SiLUSpec.scala:`<none>`.
file://<WORKSPACE>/src/test/scala/SiLU/SiLUSpec.scala
empty definition using pc, found symbol in pc: `<none>`.
empty definition using semanticdb
empty definition using fallback
non-local guesses:
	 -Common.Param.PipLine#
	 -SiLU.PipLine#
	 -chisel3/PipLine#
	 -chisel3/util/PipLine#
	 -chisel3/experimental/PipLine#
	 -chisel3/simulator/EphemeralSimulator.PipLine#
	 -PipLine#
	 -scala/Predef.PipLine#
offset: 384
uri: file://<WORKSPACE>/src/test/scala/SiLU/SiLUSpec.scala
text:
```scala
package SiLU

import Common.Param._
import SiLU._
import chisel3._
import chisel3.util._
import chisel3.experimental._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class SiLUSpec extends AnyFreeSpec with Matchers {
 "Top should calculate proper greatest common denominator" in {
    simulate(new @@PipLine) { dut =>
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




```


#### Short summary: 

empty definition using pc, found symbol in pc: `<none>`.