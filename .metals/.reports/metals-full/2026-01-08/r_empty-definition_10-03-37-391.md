error id: file://<WORKSPACE>/src/test/scala/GeLU/CUSpec.scala:`<none>`.
file://<WORKSPACE>/src/test/scala/GeLU/CUSpec.scala
empty definition using pc, found symbol in pc: `<none>`.
empty definition using semanticdb
empty definition using fallback
non-local guesses:
	 -chisel3/CU#
	 -chisel3/experimental/BundleLiterals.CU#
	 -chisel3/simulator/EphemeralSimulator.CU#
	 -CU#
	 -scala/Predef.CU#
offset: 336
uri: file://<WORKSPACE>/src/test/scala/GeLU/CUSpec.scala
text:
```scala
package CU

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class CUSpec extends AnyFreeSpec with Matchers {
 "CU should calculate proper greatest common denominator" in {
    simulate(new @@CU) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.clock.step() 
      
      dut.io.data_in.poke("b0000000100000010".U(16.W).asUInt)
      dut.io.data_in_valid.poke(true.B)
      dut.clock.step()

    }
  }
}




```


#### Short summary: 

empty definition using pc, found symbol in pc: `<none>`.