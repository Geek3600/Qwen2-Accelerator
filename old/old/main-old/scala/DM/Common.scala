package DM

import chisel3._
import chisel3.util._

class R1W1Mem(val depth: Int, val width: Int) extends Module{
  val addrw = log2Up(depth)
  val io = IO(new  Bundle() {
    val wen = Input(Bool())
    val waddr = Input(UInt(addrw.W))
    val wdata = Input(UInt(width.W))
    val ren = Input(Bool())
    val raddr = Input(UInt(addrw.W))
    val rdata = Output(UInt(width.W))
  })
  val mem = SyncReadMem(depth, UInt(width.W))
  io.rdata := mem.read(io.raddr, io.ren)
  when(io.wen ) {
    mem.write(io.waddr, io.wdata)
  }
  // printf(p"[DM.R1W1Mem] wen = ${io.wen}, waddr = ${io.waddr}, ren = ${io.ren}, raddr = ${io.raddr}\n")
}

class PipReg(val depth: Int, val width: Int) extends Module {
  val io = IO(new Bundle() {
    val in = Input(UInt(width.W))
    val out = Output(UInt(width.W))
  })
  // 使用 ShiftRegister 简化代码，逻辑与原本一致
  if (depth > 0) {
    io.out := ShiftRegister(io.in, depth)
  } else {
    io.out := io.in
  }
}

class Muler(val width: Int) extends Module {
  val io = IO(new Bundle() {
    val in0 = Input(UInt(width.W))
    val in1 = Input(UInt(width.W))
    val out = Output(UInt((width * 2).W))
  })
  // 2级流水线
  io.out := RegNext(RegNext(io.in0) * RegNext(io.in1))
}

class AddTree(val num: Int, val width: Int) extends Module {
  val io = IO(new Bundle() {
    val ins = Input(Vec(num, UInt(width.W)))
    val out = Output(UInt(width.W))
  })

  def recFNAddTree(vals: Seq[UInt]): UInt = {
    if (vals.length == 1) vals.head
    else {
      val next = vals.grouped(2).map {
        case Seq(a, b) => RegNext(a + b)
        case Seq(a) => RegNext(a)
      }.toSeq
      recFNAddTree(next)
    }
  }

  io.out := recFNAddTree(io.ins)
}
