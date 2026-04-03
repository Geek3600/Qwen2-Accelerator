//
//
//import DM.Param._
//import chisel3._
//import chiseltest._
//import org.scalatest.flatspec.AnyFlatSpec
//
//class SimpleLoadTest extends AnyFlatSpec with ChiselScalatestTester {
//
//  "TOP" should "easy test " in {
//    test(new AttenTop).withAnnotations(Seq(WriteVcdAnnotation)) { c =>
//      c.clock.setTimeout(0)
//      val prefilllen = 8
//      c.io.cfg_prefill.poke(1.U)
//      c.io.cfg_seqlen.poke((prefilllen - 1).U)
//      c.io.cfg_valid.poke(1.U)
//      c.io.layer_st.poke(0.U)
//
//      c.io.data_in_ready.poke(0.U)
//      c.io.res_ready.poke(0.U)
//
//      c.clock.step(1)
//
//
//
//      c.io.cfg_valid.poke(0.U)
//      c.io.data_in_ready.poke(1.U)
//      c.io.res_ready.poke(0.U)
//
//
//
//      c.clock.step(2000)
//
//
//
//
//
//
//    }
//  }}