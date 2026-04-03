package DM

import DM.Param._
import chisel3._
import chisel3.util._

class DM extends Module {

  val io = IO(new Bundle() {
    val cfg_seqlen = Input(UInt(log2Up(MAX_SEQLEN).W)) //prefill的时候表示的是prefill的长度，decode的时候表示的是当前序列的长度
    //    val cfg_prelen = Input(UInt(log2Up(MAX_PREFILL).W)) //prefill 长度减去1
    val cfg_prefill = Input(Bool())
    val cfg_valid = Input(Bool())

    val data_in = Input(UInt((LOAD_VECNUM * DATAW * 2).W)) //一个q一个k
    val data_in_valid = Input(Bool())

    val data_out = Output(UInt((MAX_SEQLEN * DATAW * 4).W))
    val data_out_valid = Output(Bool())
  })
  val is_prefill = RegEnable(io.cfg_prefill, io.cfg_valid)
  val seqlen = RegEnable(io.cfg_seqlen, io.cfg_valid)


  val headvec_num = HEAD_VECNUM / LOAD_VECNUM
  val headvec_cnt = Wire(UInt(log2Up(headvec_num).W))
  val headvec_last = headvec_cnt === (headvec_num - 1).U
  headvec_cnt := RegEnable(
    Mux(
      headvec_last,
      0.U,
      headvec_cnt + 1.U
    ),
    0.U,
    io.data_in_valid
  )

  val in_q = io.data_in(LOAD_VECNUM * DATAW - 1, 0)
  val in_k = io.data_in(LOAD_VECNUM * DATAW * 2 - 1, LOAD_VECNUM * DATAW)

  val QVec = Wire(Vec(HEAD_VECNUM / LOAD_VECNUM, UInt((LOAD_VECNUM * DATAW).W)))
  val KVec = Wire(Vec(HEAD_VECNUM / LOAD_VECNUM, UInt((LOAD_VECNUM * DATAW).W)))

  for (i <- 0 until HEAD_VECNUM / LOAD_VECNUM) {
    QVec(i) := RegEnable(in_q, io.data_in_valid && headvec_cnt === i.U)
    KVec(i) := RegEnable(in_k, io.data_in_valid && headvec_cnt === i.U)
  }

  val QKVec_valid = RegNext(headvec_last && io.data_in_valid, false.B)


  val prefill_cnt = Wire(UInt(MAX_PREFILL.W))
  val prefill_last = prefill_cnt === seqlen
  prefill_cnt := RegEnable(
    Mux(
      prefill_last,
      0.U,
      prefill_cnt + 1.U
    ),
    QKVec_valid && is_prefill
  )

  val batch_cnt = Wire(UInt(log2Up(BATCHSIZE).W))
  val batch_last = batch_cnt === (BATCHSIZE -1 ).U
  batch_cnt := RegEnable(
    Mux(
      batch_last,
      0.U,
      batch_cnt + 1.U
    ),
    QKVec_valid && (!is_prefill || prefill_last)
  )

  val lbatch_cnt = Wire(UInt(log2Up(LBATCHSIZE).W))
  val lbatch_last = lbatch_cnt === (LBATCHSIZE - 1).U
  lbatch_cnt := RegEnable(
    Mux(
      lbatch_last,
      0.U,
      lbatch_cnt + 1.U
    ),
    0.U,
    QKVec_valid && (!is_prefill || prefill_last)
  )




  val QCache = Module(new R1W1Mem(MAX_PREFILL , HEAD_VECNUM * DATAW)) //對於當前的情況，這個是可以勝率
  val KCache = Module(new R1W1Mem(LBATCHSIZE * MAX_SEQLEN, HEAD_VECNUM * DATAW))


  QCache.io.wen := QKVec_valid
  QCache.io.wdata := QVec.asUInt
  QCache.io.waddr := Mux(is_prefill, prefill_cnt, 0.U)

  KCache.io.wen := QKVec_valid
  KCache.io.wdata := KVec.asUInt
  KCache.io.waddr := Mux(is_prefill, lbatch_cnt * MAX_SEQLEN.U + prefill_cnt, lbatch_cnt * MAX_SEQLEN.U + seqlen)


  val st_load :: st_compute :: Nil = Enum(2)
  val compute_over = Wire(Bool()) //todo
  val state = RegInit(st_load)
  val is_comp = state === st_compute
  val load_mux = Mux(
    QKVec_valid && (!is_prefill || prefill_last),
    st_compute,
    st_load
  )
  state := Mux(
    state === st_load,
    load_mux,
    Mux(
      compute_over,
      load_mux,
      st_compute
    )
  )

  val lbatch_cnt_r = RegEnable(lbatch_cnt, QKVec_valid && (!is_prefill || prefill_last))

  val comp_headv_cnt = Wire(UInt(log2Up(HEAD_VECNUM / MULNUM).W))
  val comp_headv_last = comp_headv_cnt === (HEAD_VECNUM / MULNUM - 1).U
  comp_headv_cnt := RegEnable(
    Mux(
      comp_headv_last,
      0.U,
      comp_headv_cnt + 1.U
    ),
    0.U,
    is_comp
  )

  val comp_key_cnt = Wire(UInt(log2Up(MAX_SEQLEN).W))
  val comp_key_last = comp_key_cnt === seqlen
  comp_key_cnt := RegEnable(
    Mux(
      comp_key_last,
      0.U,
      comp_key_cnt + 1.U
    ),
    0.U,
    is_comp && comp_headv_last
  )

  val comp_prefill_q_cnt = Wire(UInt(log2Up(MAX_PREFILL).W))
  val comp_prefill_q_last = comp_prefill_q_cnt === seqlen
  comp_prefill_q_cnt := RegEnable(
    Mux(
      comp_prefill_q_last,
      0.U,
      comp_prefill_q_cnt + 1.U
    ),
    0.U,
    is_comp && comp_headv_last && comp_key_last && is_prefill
  )


  compute_over := comp_headv_last && comp_key_last && (comp_prefill_q_last || !is_prefill)

  QCache.io.ren := is_comp
  QCache.io.raddr := Mux(is_prefill,  comp_prefill_q_cnt, 0.U)

  KCache.io.ren := is_comp
  KCache.io.raddr := lbatch_cnt_r * MAX_SEQLEN.U + comp_key_cnt

  //读出数据，打一排
  val Q_Sel = MuxLookup(
    RegNext(comp_headv_cnt),
    0.U,
    (0 until HEAD_VECNUM / MULNUM).map { i => i.U -> QCache.io.rdata((i + 1) * MULNUM * DATAW - 1, i * MULNUM * DATAW) }
  )

  val K_Sel = MuxLookup(
    RegNext(comp_headv_cnt),
    0.U,
    (0 until HEAD_VECNUM / MULNUM).map { i => i.U -> KCache.io.rdata((i + 1) * MULNUM * DATAW - 1, i * MULNUM * DATAW) }
  )

  val mullist = List.fill(MULNUM)(Module(new Muler(DATAW)))
  for (i <- 0 until MULNUM) {
    mullist(i).io.in0 := Q_Sel((i + 1) * DATAW - 1, i * DATAW)
    mullist(i).io.in1 := K_Sel((i + 1) * DATAW - 1, i * DATAW)
  }

  //乘法器，打两拍

  val addtree = Module(new AddTree(MULNUM, DATAW * 2))
  for (i <- 0 until MULNUM) {
    addtree.io.ins(i) := mullist(i).io.out
  }
  //加法树，打log拍
  val acc_first = comp_headv_cnt === 0.U
  val acc_first_pipreg = Module(new PipReg(1 + 2 + log2Up(MULNUM), 1))
  acc_first_pipreg.io.in := acc_first.asUInt

  val acc_update = is_comp
  val acc_update_pipreg = Module(new PipReg(1 + 2 + log2Up(MULNUM), 1))
  acc_update_pipreg.io.in := acc_update.asUInt

  val acc_reg = Wire(UInt(DATAW.W))
  acc_reg := RegEnable(
    Mux(
      acc_first_pipreg.io.out.asBool,
      addtree.io.out,
      addtree.io.out + acc_reg
    ),
    0.U,
    acc_update_pipreg.io.out.asBool
  )
  //又打了一排
  val res_cache = Wire(Vec(MAX_SEQLEN, UInt(DATAW.W)))
  val acc_valid = comp_headv_last && is_comp
  val acc_valid_pipreg = Module(new PipReg(1 + 2 + log2Up(MULNUM) + 1, 1))
  acc_valid_pipreg.io.in := acc_valid

  val res_ksel = comp_key_cnt
  val res_ksel_pipreg = Module(new PipReg(1 + 2 + log2Up(MULNUM) + 1, log2Up(MAX_SEQLEN)))
  res_ksel_pipreg.io.in := res_ksel

  for (i <- 0 until MAX_SEQLEN) {
    res_cache(i) := RegEnable(acc_reg, acc_valid_pipreg.io.out.asBool && res_ksel_pipreg.io.out === i.U)
  }

  val res_valid = RegNext(res_ksel_pipreg.io.out === seqlen && acc_valid_pipreg.io.out.asBool, false.B)


  io.data_out := res_cache.asUInt
  io.data_out_valid := res_valid


}
