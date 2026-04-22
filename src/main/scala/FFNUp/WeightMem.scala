package FFNUp

import QuantCommon.XilinxUramCompatMem
import chisel3._
import chisel3.util._
import FFNUp.Param._

// FFNUp 权重存储器，扩展为 active/shadow 双 bank。
class WeightMem extends Module {
  val io = IO(new Bundle {
    val init_mode = Input(Bool())
    val init_addr = Input(UInt(log2Up(WMEM_DEPTH).W))
    val init_data = Input(UInt(WMEM_WIDTH.W))
    val init_wen = Input(Bool())

    val preload_valid = Input(Bool())
    val preload_bank = Input(Bool())
    val preload_addr = Input(UInt(log2Up(WMEM_DEPTH).W))
    val preload_data = Input(UInt(WMEM_WIDTH.W))

    val read_en = Input(Bool())
    val read_bank = Input(Bool())
    val read_addr = Input(UInt(log2Up(ROWBLOCK * COLBLOCK).W))
    val read_data = Output(Vec(ROW, UInt(WMEM_WIDTH.W)))
  })

  val TILE_DEPTH = ROWBLOCK * COLBLOCK
  val BANK_DEPTH = TILE_DEPTH
  val NUM_SLICES = 4
  val SLICE_WIDTH = 72

  val weight_banks = Seq.fill(2)(
    Seq.fill(ROW)(
      Seq.fill(NUM_SLICES)(Module(new XilinxUramCompatMem(BANK_DEPTH, SLICE_WIDTH)))
    )
  )

  val legacyWrite = io.init_mode && io.init_wen
  val preloadWrite = io.preload_valid
  assert(!(legacyWrite && preloadWrite), "FFNUp WeightMem legacy init and preload collided")

  val writeBank = Mux(preloadWrite, io.preload_bank, false.B)
  val writeAddr = Mux(preloadWrite, io.preload_addr, io.init_addr)
  val writeData = Mux(preloadWrite, io.preload_data, io.init_data)
  val writeFire = legacyWrite || preloadWrite
  val writeColBlock = writeAddr % COLBLOCK.U
  val writeRowLinear = writeAddr / COLBLOCK.U
  val writeRowSel = writeRowLinear % ROW.U
  val writeRowBlock = writeRowLinear / ROW.U
  val writeTileAddr = writeColBlock * ROWBLOCK.U + writeRowBlock

  for (copy <- 0 until 2) {
    for (b <- 0 until ROW) {
      for (s <- 0 until NUM_SLICES) {
        weight_banks(copy)(b)(s).io.write_en := writeFire && writeBank === (copy == 1).B && writeRowSel === b.U
        weight_banks(copy)(b)(s).io.write_addr := writeTileAddr
        weight_banks(copy)(b)(s).io.write_data := writeData((s + 1) * SLICE_WIDTH - 1, s * SLICE_WIDTH)
        weight_banks(copy)(b)(s).io.read_en := io.read_en
        weight_banks(copy)(b)(s).io.read_addr := io.read_addr
      }
    }
  }

  val readDatas = for (copy <- 0 until 2) yield {
    for (b <- 0 until ROW) yield {
      val slices = for (s <- 0 until NUM_SLICES) yield {
        weight_banks(copy)(b)(s).io.read_data
      }
      Cat(slices.reverse)
    }
  }

  val selectedCopy = RegNext(io.read_bank, false.B)
  for (i <- 0 until ROW) {
    io.read_data(i) := Mux(selectedCopy, readDatas(1)(i), readDatas(0)(i))
  }
}
