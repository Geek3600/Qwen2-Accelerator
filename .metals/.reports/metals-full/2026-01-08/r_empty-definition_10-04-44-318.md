error id: file://<WORKSPACE>/src/test/scala/GeLU/StoreUSpec.scala:`<none>`.
file://<WORKSPACE>/src/test/scala/GeLU/StoreUSpec.scala
empty definition using pc, found symbol in pc: `<none>`.
empty definition using semanticdb
empty definition using fallback
non-local guesses:
	 -chisel3/Matchers.
	 -chisel3/Matchers#
	 -chisel3/Matchers().
	 -chisel3/experimental/BundleLiterals.Matchers.
	 -chisel3/experimental/BundleLiterals.Matchers#
	 -chisel3/experimental/BundleLiterals.Matchers().
	 -chisel3/simulator/EphemeralSimulator.Matchers.
	 -chisel3/simulator/EphemeralSimulator.Matchers#
	 -chisel3/simulator/EphemeralSimulator.Matchers().
	 -org/scalatest/matchers/must/Matchers.
	 -org/scalatest/matchers/must/Matchers#
	 -org/scalatest/matchers/must/Matchers().
	 -Matchers.
	 -Matchers#
	 -Matchers().
	 -scala/Predef.Matchers.
	 -scala/Predef.Matchers#
	 -scala/Predef.Matchers().
offset: 199
uri: file://<WORKSPACE>/src/test/scala/GeLU/StoreUSpec.scala
text:
```scala
package GeLU

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.@@Matchers

class StoreUSpec extends AnyFreeSpec with Matchers {
 "StoreU should calculate proper greatest common denominator" in {
    simulate(new StoreU) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.clock.step() 
      dut.io.data_in_valid.poke(true.B)
      dut.clock.step()
      for (i <- 0 until 100) {
        dut.clock.step()
      }
    }
  }
}




```


#### Short summary: 

empty definition using pc, found symbol in pc: `<none>`.