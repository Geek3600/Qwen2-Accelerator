error id: file://<WORKSPACE>/src/main/scala/SiLU/CU.scala:`<none>`.
file://<WORKSPACE>/src/main/scala/SiLU/CU.scala
empty definition using pc, found symbol in pc: `<none>`.
empty definition using semanticdb
empty definition using fallback
non-local guesses:
	 -Common.UInt.
	 -Common.UInt#
	 -Common.UInt().
	 -chisel3/UInt.
	 -chisel3/UInt#
	 -chisel3/UInt().
	 -chisel3/util/UInt.
	 -chisel3/util/UInt#
	 -chisel3/util/UInt().
	 -Common.Param.UInt.
	 -Common.Param.UInt#
	 -Common.Param.UInt().
	 -UInt.
	 -UInt#
	 -UInt().
	 -scala/Predef.UInt.
	 -scala/Predef.UInt#
	 -scala/Predef.UInt().
offset: 171
uri: file://<WORKSPACE>/src/main/scala/SiLU/CU.scala
text:
```scala
package SiLU
import Common._
import chisel3._
import chisel3.util._
import Common.Param._

class CU extends Module {
  val io = IO(new Bundle() {
    val data_in = Input(U@@Int((MEM_WIDTH).W))
    val data_in_valid = Input(Bool())
    val data_out = Output(UInt((MEM_WIDTH).W))
    val data_out_valid = Output(Bool())
  })
    val gelu = Module(new GELU)

    val in_vec = io.data_in.asTypeOf(Vec(V, SInt(DATAW.W)))
    gelu.io.in := Mux(io.data_in_valid, in_vec, VecInit(Seq.fill(V)(0.S(DATAW.W))))

    val out_vec = Wire(Vec(V, UInt(DATAW.W)))
    out_vec := gelu.io.out.map(_.asUInt)

    io.data_out := Cat(out_vec.reverse)   
    io.data_out_valid := io.data_in_valid
    // printf(p"[CU] data_in_valid = ${io.data_in_valid}, data_out_valid = ${io.data_out_valid}, data_in: ${Binary(io.data_in)}, data_out: ${Binary(io.data_out)}\n")
}

object CU extends App {
  emitVerilog(new CU, Array("--target-dir", "generated"))
}

```


#### Short summary: 

empty definition using pc, found symbol in pc: `<none>`.