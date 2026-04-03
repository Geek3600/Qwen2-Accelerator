package QKVLinear

import QKVLinear.Param._
import QuantCommon.Precision._
import chisel3._
import chisel3.util._

class SignedMultiplierInt8 extends Module {
  val io = IO(new Bundle() {
    val in0 = Input(SInt(DATAW.W))
    val in1 = Input(SInt(DATAW.W))
    val out = Output(SInt(16.W))
  })

  io.out := RegNext(io.in0 * io.in1)
}

class SignedAddTree32(val num: Int) extends Module {
  val io = IO(new Bundle() {
    val ins = Input(Vec(num, SInt(16.W)))
    val out = Output(SInt(INT32_WIDTH.W))
  })

  def recTree(vals: Seq[SInt]): SInt = {
    if (vals.length == 1) vals.head
    else {
      val next = vals.grouped(2).map {
        case Seq(a, b) => RegNext((a +& b).asSInt)
        case Seq(a) => RegNext(a)
      }.toSeq
      recTree(next)
    }
  }

  io.out := recTree(io.ins)
}

class CUQuant extends Module {
  val io = IO(new Bundle() {
    val data_in = Input(UInt(MEM_WIDTH.W))
    val data_in_valid = Input(Bool())
    val data_out = Output(UInt((ROW * INT32_WIDTH).W))
    val data_out_valid = Output(Bool())

    val w_update = Output(Bool())
    val w_data = Input(UInt(WMEM_WIDTH.W))
    val w_valid = Input(Bool())

    val cfg_prefill = Input(Bool())
    val cfg_seqlen = Input(UInt(log2Up(MAX_SEQLEN).W))
    val cfg_valid = Input(Bool())
    val cfg_single_query = Input(Bool())
  })

  val is_prefill = RegEnable(io.cfg_prefill, false.B, io.cfg_valid)
  val seqlen = RegEnable(io.cfg_seqlen, 0.U, io.cfg_valid)
  val is_single_query = RegEnable(io.cfg_single_query, false.B, io.cfg_valid)
  val decode_batch_last = Mux(is_single_query, 0.U(log2Up(BATCHSIZE).W), (BATCHSIZE - 1).U)
  val actual_batchsize = Mux(is_prefill, seqlen, decode_batch_last)

  val weight_w_sel = RegInit(false.B)
  val weight_w_cnt = RegInit(0.U(log2Up(ROW).W))
  val weight_w_last = weight_w_cnt === (ROW - 1).U
  when(io.w_valid) {
    weight_w_cnt := Mux(weight_w_last, 0.U, weight_w_cnt + 1.U)
    when(weight_w_last) {
      weight_w_sel := ~weight_w_sel
    }
  }

  val w0 = Wire(Vec(ROW, UInt(WMEM_WIDTH.W)))
  val w1 = Wire(Vec(ROW, UInt(WMEM_WIDTH.W)))
  w0(0) := RegEnable(io.w_data, io.w_valid && !weight_w_sel)
  w1(0) := RegEnable(io.w_data, io.w_valid && weight_w_sel)

  for (i <- 1 until ROW) {
    w0(i) := RegEnable(w0(i - 1), io.w_valid && !weight_w_sel)
    w1(i) := RegEnable(w1(i - 1), io.w_valid && weight_w_sel)
  }

  val weight_r_sel = RegInit(false.B)
  val weight = Wire(Vec(ROW, Vec(COL, SInt(DATAW.W))))
  for (i <- 0 until ROW) {
    val srcIdx = (ROW - 1) - i
    for (j <- 0 until COL) {
      weight(i)(j) := Mux(weight_r_sel, w1(srcIdx)((j + 1) * DATAW - 1, j * DATAW), w0(srcIdx)((j + 1) * DATAW - 1, j * DATAW)).asSInt
    }
  }

  val batchsize_cnt = Wire(UInt(log2Up(BATCHSIZE).W))
  val batchsize_last = batchsize_cnt === actual_batchsize
  batchsize_cnt := RegEnable(Mux(batchsize_last, 0.U, batchsize_cnt + 1.U), 0.U, io.data_in_valid)

  val block_cnt = Wire(UInt(log2Up(ROWBLOCK).W))
  val block_last = block_cnt === (ROWBLOCK - 1).U
  block_cnt := RegEnable(Mux(block_last, 0.U, block_cnt + 1.U), 0.U, batchsize_last && io.data_in_valid)

  val input_delay = 2
  val indata_pipreg = Module(new PipReg(input_delay, MEM_WIDTH))
  indata_pipreg.io.in := io.data_in

  val batchsizelast_pipreg = Module(new PipReg(input_delay, 1))
  batchsizelast_pipreg.io.in := (batchsize_last && io.data_in_valid).asUInt

  when(batchsizelast_pipreg.io.out.asBool) {
    weight_r_sel := ~weight_r_sel
  }
  io.w_update := batchsizelast_pipreg.io.out.asBool

  val mul_list = List.fill(ROW)(List.fill(COL)(Module(new SignedMultiplierInt8)))
  for (i <- 0 until ROW) {
    for (j <- 0 until COL) {
      mul_list(i)(j).io.in0 := indata_pipreg.io.out(DATAW * (i + 1) - 1, DATAW * i).asSInt
      mul_list(i)(j).io.in1 := weight(i)(j)
    }
  }
  val mul_delay = 1

  val addTreeList = List.fill(COL)(Module(new SignedAddTree32(ROW)))
  for (j <- 0 until COL) {
    for (i <- 0 until ROW) {
      addTreeList(j).io.ins(i) := mul_list(i)(j).io.out
    }
  }
  val add_delay = log2Up(ROW)

  val psums0 = RegInit(VecInit(Seq.fill(BATCHSIZE)(VecInit(Seq.fill(COL)(0.S(INT32_WIDTH.W))))))
  val psums1 = RegInit(VecInit(Seq.fill(BATCHSIZE)(VecInit(Seq.fill(COL)(0.S(INT32_WIDTH.W))))))

  val block_first = block_cnt === 0.U
  val psum_last = block_last && batchsize_last
  val block_first_pipreg = Module(new PipReg(input_delay + mul_delay + add_delay, 1))
  val cnt_batchsize_pipreg = Module(new PipReg(input_delay + mul_delay + add_delay, batchsize_cnt.getWidth))
  val psum_valid_pipreg = Module(new PipReg(input_delay + mul_delay + add_delay, 1))
  val psum_last_pipreg = Module(new PipReg(input_delay + mul_delay + add_delay, 1))
  block_first_pipreg.io.in := block_first
  cnt_batchsize_pipreg.io.in := batchsize_cnt
  psum_valid_pipreg.io.in := io.data_in_valid
  psum_last_pipreg.io.in := (psum_last && io.data_in_valid).asUInt

  val block_first_pipout = block_first_pipreg.io.out.asBool
  val cnt_batchsize_pipout = cnt_batchsize_pipreg.io.out
  val psum_valid_pipout = psum_valid_pipreg.io.out.asBool
  val psum_last_pipout = psum_last_pipreg.io.out.asBool
  val psum_sel = Wire(Bool())
  psum_sel := RegEnable(~psum_sel, false.B, psum_valid_pipout && psum_last_pipout)

  for (i <- 0 until BATCHSIZE) {
    for (j <- 0 until COL) {
      when(cnt_batchsize_pipout === i.U && psum_valid_pipout && !psum_sel) {
        psums0(i)(j) := Mux(block_first_pipout, addTreeList(j).io.out, psums0(i)(j) + addTreeList(j).io.out)
      }
      when(cnt_batchsize_pipout === i.U && psum_valid_pipout && psum_sel) {
        psums1(i)(j) := Mux(block_first_pipout, addTreeList(j).io.out, psums1(i)(j) + addTreeList(j).io.out)
      }
    }
  }

  val send_st = psum_last_pipout
  val out_bank_sel = RegInit(false.B)
  when(send_st) {
    out_bank_sel := psum_sel
  }
  val res_vector_num = (COL + ROW - 1) / ROW
  val res_vector_cnt = Wire(UInt(log2Up(res_vector_num).W))
  val res_vector_last = res_vector_cnt === (res_vector_num - 1).U
  val res_batchsize_cnt = Wire(UInt(log2Up(BATCHSIZE).W))
  val res_batchsize_last = res_batchsize_cnt === actual_batchsize

  val idle :: buzy :: Nil = Enum(2)
  val state = RegInit(idle)
  val is_idle = state === idle
  val is_buzy = state === buzy
  val idle_mux = Mux(send_st, buzy, idle)
  val res_done = res_batchsize_last && res_vector_last
  val buzy_mux = Mux(res_done, Mux(send_st, buzy, idle), buzy)
  state := Mux(is_idle, idle_mux, buzy_mux)

  res_batchsize_cnt := RegEnable(Mux(res_batchsize_last, 0.U, res_batchsize_cnt + 1.U), 0.U, is_buzy)
  res_vector_cnt := RegEnable(Mux(res_vector_last, 0.U, res_vector_cnt + 1.U), 0.U, is_buzy && res_batchsize_last)

  val psum0_token = MuxLookup(res_batchsize_cnt, 0.U((COL * INT32_WIDTH).W))(
    (0 until BATCHSIZE).map { i => i.U -> Cat(psums0(i).reverse.map(_.asUInt)) }
  )
  val psum1_token = MuxLookup(res_batchsize_cnt, 0.U((COL * INT32_WIDTH).W))(
    (0 until BATCHSIZE).map { i => i.U -> Cat(psums1(i).reverse.map(_.asUInt)) }
  )

  val psum_batch = RegNext(Mux(out_bank_sel, psum1_token, psum0_token), 0.U((COL * INT32_WIDTH).W))
  val psum_vector_sel_d1 = RegNext(res_vector_cnt, 0.U(res_vector_cnt.getWidth.W))
  val psum_vector_sel = RegNext(psum_vector_sel_d1, 0.U(res_vector_cnt.getWidth.W))
  val psum_res_int = MuxLookup(psum_vector_sel, psum_batch(ROW * INT32_WIDTH - 1, 0))(
    (0 until res_vector_num).map { i =>
      i.U -> psum_batch((i + 1) * ROW * INT32_WIDTH - 1, i * ROW * INT32_WIDTH)
    }
  )

  io.data_out := psum_res_int
  io.data_out_valid := RegNext(RegNext(is_buzy, false.B), false.B)
}
