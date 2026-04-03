package DM

import DM.FP32Param._
import QuantCommon.Int32VecToFp32
import QuantCommon.Precision._
import chisel3._
import chisel3.util._

class SignedMultiplierInt8 extends Module {
  val io = IO(new Bundle() {
    val in0 = Input(SInt(DATAW.W))
    val in1 = Input(SInt(DATAW.W))
    val out = Output(SInt(16.W))
  })
  io.out := RegNext(RegNext(io.in0 * io.in1))
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

class StoreUnitFP32 extends Module {
  val io = IO(new Bundle() {
    val cfg_seqlen = Input(UInt(log2Up(MAX_PREFILL).W))
    val cfg_prefill = Input(Bool())
    val cfg_valid = Input(Bool())
    val cfg_single_query = Input(Bool())

    val data_in = Input(UInt(OUT_WIDTH.W))
    val data_in_valid = Input(Bool())

    val data_out = Output(UInt(OUT_WIDTH.W))
    val data_out_addr = Output(UInt(log2Up(BATCHSIZE).W))
    val data_out_valid = Output(Bool())
    val data_out_last = Output(Bool())
  })

  val seqlen = RegEnable(io.cfg_seqlen, io.cfg_valid)
  val is_prefill = RegEnable(io.cfg_prefill, io.cfg_valid)
  val is_single_query = RegEnable(io.cfg_single_query, false.B, io.cfg_valid)

  val batch_cnt = RegInit(0.U(log2Up(math.max(BATCHSIZE, MAX_PREFILL)).W))
  val decode_batch_last = Mux(is_single_query, 0.U(batch_cnt.getWidth.W), (BATCHSIZE - 1).U(batch_cnt.getWidth.W))
  val batch_last = batch_cnt === Mux(is_prefill, seqlen, decode_batch_last)

  when(io.data_in_valid) {
    batch_cnt := Mux(batch_last, 0.U, batch_cnt + 1.U)
  }

  io.data_out := io.data_in
  io.data_out_addr := batch_cnt
  io.data_out_valid := io.data_in_valid
  io.data_out_last := batch_last && io.data_in_valid
}

// DM1 的 FP32 版本
// 输入仍为 INT8 Q/K，输出改为 FP32 logits
class DMCUPlusFP32 extends Module {
  val io = IO(new Bundle() {
    val cfg_seqlen = Input(UInt(log2Up(MAX_SEQLEN).W))
    val cfg_prefill = Input(Bool())
    val cfg_valid = Input(Bool())
    val cfg_single_query = Input(Bool())

    val out_scale = Input(UInt(FP32_WIDTH.W))

    val data_in = Input(UInt((LOAD_VECNUM * DATAW * 2).W))
    val data_in_valid = Input(Bool())

    val data_out = Output(UInt(OUT_WIDTH.W))
    val data_out_valid = Output(Bool())
  })

  val is_prefill = RegEnable(io.cfg_prefill, io.cfg_valid)
  val is_single_query = RegEnable(io.cfg_single_query, false.B, io.cfg_valid)
  val seqlen = RegEnable(io.cfg_seqlen, io.cfg_valid)

  val headvec_num = HEAD_VECNUM / LOAD_VECNUM
  val headvec_cnt = RegInit(0.U(log2Up(headvec_num).W))
  val headvec_last = headvec_cnt === (headvec_num - 1).U

  when(io.data_in_valid) {
    headvec_cnt := Mux(headvec_last, 0.U, headvec_cnt + 1.U)
  }

  val in_q = io.data_in(LOAD_VECNUM * DATAW - 1, 0)
  val in_k = io.data_in(LOAD_VECNUM * DATAW * 2 - 1, LOAD_VECNUM * DATAW)

  val qVec = Reg(Vec(headvec_num, UInt((LOAD_VECNUM * DATAW).W)))
  val kVec = Reg(Vec(headvec_num, UInt((LOAD_VECNUM * DATAW).W)))
  when(io.data_in_valid) {
    qVec(headvec_cnt) := in_q
    kVec(headvec_cnt) := in_k
  }

  val qkVecValid = RegNext(headvec_last && io.data_in_valid, false.B)

  val prefill_cnt = RegInit(0.U(log2Up(MAX_PREFILL).W))
  when(qkVecValid && is_prefill) {
    prefill_cnt := Mux(prefill_cnt === seqlen, 0.U, prefill_cnt + 1.U)
  }

  val lbatch_cnt = RegInit(0.U(log2Up(BATCHSIZE).W))
  val lbatch_last = lbatch_cnt === Mux(
    is_single_query,
    (SINGLE_QUERY_BATCH - 1).U(lbatch_cnt.getWidth.W),
    (BATCHSIZE - 1).U(lbatch_cnt.getWidth.W)
  )
  when(qkVecValid && (!is_prefill || prefill_cnt === seqlen)) {
    lbatch_cnt := Mux(lbatch_last, 0.U, lbatch_cnt + 1.U)
  }
  val lbatch_cnt_r = RegEnable(lbatch_cnt, qkVecValid && (!is_prefill || prefill_cnt === seqlen))

  val qCache = Module(new R1W1Mem(MAX_PREFILL, HEAD_VECNUM * DATAW))
  val kCache = Module(new R1W1Mem(BATCHSIZE * MAX_SEQLEN, HEAD_VECNUM * DATAW))

  qCache.io.wen := qkVecValid
  qCache.io.wdata := qVec.asUInt
  qCache.io.waddr := Mux(is_prefill, prefill_cnt, 0.U)

  kCache.io.wen := qkVecValid
  kCache.io.wdata := kVec.asUInt
  kCache.io.waddr := Mux(
    is_prefill,
    lbatch_cnt * MAX_SEQLEN.U + prefill_cnt,
    lbatch_cnt * MAX_SEQLEN.U + seqlen
  )

  val st_load :: st_compute :: Nil = Enum(2)
  val state = RegInit(st_load)
  val is_comp = state === st_compute

  when(state === st_load && qkVecValid && (!is_prefill || prefill_cnt === seqlen)) {
    state := st_compute
  }

  val comp_headv_cnt = RegInit(0.U(log2Up(HEAD_VECNUM / MULNUM).W))
  val comp_key_cnt = RegInit(0.U(log2Up(MAX_SEQLEN).W))
  val comp_prefill_q_cnt = RegInit(0.U(log2Up(MAX_PREFILL).W))

  val comp_headv_last = comp_headv_cnt === (HEAD_VECNUM / MULNUM - 1).U
  val comp_key_last = comp_key_cnt === seqlen
  val comp_prefill_q_last = comp_prefill_q_cnt === seqlen

  when(is_comp) {
    when(comp_headv_last) {
      comp_headv_cnt := 0.U
      when(comp_key_last) {
        comp_key_cnt := 0.U
        when(is_prefill) {
          when(comp_prefill_q_last) {
            comp_prefill_q_cnt := 0.U
            state := st_load
          }.otherwise {
            comp_prefill_q_cnt := comp_prefill_q_cnt + 1.U
          }
        }.otherwise {
          state := st_load
        }
      }.otherwise {
        comp_key_cnt := comp_key_cnt + 1.U
      }
    }.otherwise {
      comp_headv_cnt := comp_headv_cnt + 1.U
    }
  }

  qCache.io.ren := is_comp
  qCache.io.raddr := Mux(is_prefill, comp_prefill_q_cnt, 0.U)
  kCache.io.ren := is_comp
  kCache.io.raddr := lbatch_cnt_r * MAX_SEQLEN.U + comp_key_cnt

  val qSel = MuxLookup(
    RegNext(comp_headv_cnt),
    0.U
  )(
    (0 until HEAD_VECNUM / MULNUM).map { i =>
      i.U -> qCache.io.rdata((i + 1) * MULNUM * DATAW - 1, i * MULNUM * DATAW)
    }
  )
  val kSel = MuxLookup(
    RegNext(comp_headv_cnt),
    0.U
  )(
    (0 until HEAD_VECNUM / MULNUM).map { i =>
      i.U -> kCache.io.rdata((i + 1) * MULNUM * DATAW - 1, i * MULNUM * DATAW)
    }
  )

  val mullist = List.fill(MULNUM)(Module(new SignedMultiplierInt8))
  for (i <- 0 until MULNUM) {
    val qElem = qSel((i + 1) * DATAW - 1, i * DATAW).asSInt
    val kElem = kSel((i + 1) * DATAW - 1, i * DATAW).asSInt
    mullist(i).io.in0 := qElem
    mullist(i).io.in1 := kElem
  }

  val addtree = Module(new SignedAddTree32(MULNUM))
  for (i <- 0 until MULNUM) {
    addtree.io.ins(i) := mullist(i).io.out
  }

  val acc_first = comp_headv_cnt === 0.U
  val acc_first_pipreg = Module(new PipReg(1 + 2 + log2Up(MULNUM), 1))
  acc_first_pipreg.io.in := acc_first.asUInt

  val acc_update = is_comp
  val acc_update_pipreg = Module(new PipReg(1 + 2 + log2Up(MULNUM), 1))
  acc_update_pipreg.io.in := acc_update.asUInt

  val acc = RegInit(0.S(INT32_WIDTH.W))
  when(acc_update_pipreg.io.out.asBool) {
    acc := Mux(
      acc_first_pipreg.io.out.asBool,
      addtree.io.out,
      acc + addtree.io.out
    )
  }

  val acc_valid = comp_headv_last && is_comp
  val acc_valid_pipreg = Module(new PipReg(1 + 2 + log2Up(MULNUM) + 1, 1))
  acc_valid_pipreg.io.in := acc_valid.asUInt

  val res_ksel = comp_key_cnt
  val res_ksel_pipreg = Module(new PipReg(1 + 2 + log2Up(MULNUM) + 1, log2Up(MAX_SEQLEN)))
  res_ksel_pipreg.io.in := res_ksel

  val res_cache = Reg(Vec(MAX_SEQLEN, SInt(INT32_WIDTH.W)))
  for (i <- 0 until MAX_SEQLEN) {
    when(acc_valid_pipreg.io.out.asBool && res_ksel_pipreg.io.out === i.U) {
      res_cache(i) := acc
    }
  }

  val resIntValid = RegNext(res_ksel_pipreg.io.out === seqlen && acc_valid_pipreg.io.out.asBool, false.B)
  val resInt = Wire(Vec(MAX_SEQLEN, SInt(INT32_WIDTH.W)))
  for (i <- 0 until MAX_SEQLEN) {
    resInt(i) := Mux(i.U <= seqlen, res_cache(i), 0.S)
  }

  val toFp = Module(new Int32VecToFp32(MAX_SEQLEN))
  toFp.io.in := resInt.asUInt
  toFp.io.scale := io.out_scale

  io.data_out := toFp.io.out
  io.data_out_valid := resIntValid
}

class DM1FP32 extends Module {
  val io = IO(new Bundle() {
    val cfg_seqlen = Input(UInt(log2Up(MAX_SEQLEN).W))
    val cfg_prefill = Input(Bool())
    val cfg_valid = Input(Bool())
    val cfg_single_query = Input(Bool())

    val out_scale = Input(UInt(FP32_WIDTH.W))

    val data_in_st = Input(Bool())
    val data_in = Input(UInt((2 * LOAD_VECNUM * DATAW).W))
    val data_addr = Input(UInt(log2Up(HEAD_VECNUM / LOAD_VECNUM * BATCHSIZE).W))
    val data_valid = Input(Bool())
    val data_last = Input(Bool())
    val data_ready = Output(Bool())

    val res = Output(UInt(OUT_WIDTH.W))
    val res_st = Output(Bool())
    val res_addr = Output(UInt(log2Up(BATCHSIZE).W))
    val res_valid = Output(Bool())
    val res_last = Output(Bool())
    val res_ready = Input(Bool())
  })

  val lu_inst = Module(new LoadUnit)
  val cu_inst = Module(new DMCUPlusFP32)
  val su_inst = Module(new StoreUnitFP32)
  val mem_inst = Module(new DataMem(HEAD_VECNUM / LOAD_VECNUM * BATCHSIZE, 2 * LOAD_VECNUM * DATAW))

  mem_inst.io.w_st := io.data_in_st
  mem_inst.io.w_last := io.data_last
  mem_inst.io.w_data := io.data_in
  mem_inst.io.w_addr := io.data_addr
  mem_inst.io.w_valid := io.data_valid
  mem_inst.io.r_last := lu_inst.io.data_in_last
  mem_inst.io.r_addr := lu_inst.io.data_in_addr

  lu_inst.io.cfg_prelen := io.cfg_seqlen
  lu_inst.io.cfg_prefill := io.cfg_prefill
  lu_inst.io.cfg_valid := io.cfg_valid
  lu_inst.io.cfg_single_query := io.cfg_single_query
  lu_inst.io.data_in := mem_inst.io.r_data
  lu_inst.io.data_in_ready := mem_inst.io.r_ready
  lu_inst.io.data_out_ready := io.res_ready

  cu_inst.io.cfg_prefill := io.cfg_prefill
  cu_inst.io.cfg_valid := io.cfg_valid
  cu_inst.io.cfg_seqlen := io.cfg_seqlen
  cu_inst.io.cfg_single_query := io.cfg_single_query
  cu_inst.io.out_scale := io.out_scale
  cu_inst.io.data_in := lu_inst.io.data_out
  cu_inst.io.data_in_valid := lu_inst.io.data_out_valid

  su_inst.io.cfg_seqlen := io.cfg_seqlen
  su_inst.io.cfg_prefill := io.cfg_prefill
  su_inst.io.cfg_valid := io.cfg_valid
  su_inst.io.cfg_single_query := io.cfg_single_query
  su_inst.io.data_in := cu_inst.io.data_out
  su_inst.io.data_in_valid := cu_inst.io.data_out_valid

  io.data_ready := mem_inst.io.w_ready
  io.res := su_inst.io.data_out
  io.res_st := lu_inst.io.data_out_start
  io.res_addr := su_inst.io.data_out_addr
  io.res_valid := su_inst.io.data_out_valid
  io.res_last := su_inst.io.data_out_last
}

object DM1FP32Gen extends App {
  emitVerilog(new DM1FP32, Array("--target-dir", "generated"))
}
