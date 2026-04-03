package DM

import DM.Param._
import chisel3._
import chisel3.util._
class LoadU extends Module {
  val io = IO(new Bundle() {
    val cfg_prelen = Input(UInt(log2Up(MAX_PREFILL).W))
    val cfg_prefill = Input(Bool())
    val cfg_valid = Input(Bool())

    val data_in = Input(UInt((2*LOAD_VECNUM*DATAW).W))
    val data_in_addr = Output(UInt(log2Up(BATCHSIZE*HEAD_VECNUM/LOAD_VECNUM).W))
    val data_in_last = Output(Bool())
    val data_in_ready = Input(Bool())

    val data_out = Output(UInt((2*LOAD_VECNUM*DATAW).W))
    val data_out_valid = Output(Bool())
    val data_out_ready = Input(Bool())
    val data_out_start = Output(Bool())
  })
  val is_prefill = RegEnable(io.cfg_prefill , io.cfg_valid)
  val prelen = RegEnable(io.cfg_prelen , io.cfg_valid)


  val idle :: buzy :: Nil = Enum(2)
  val state = RegInit(idle)
  val load_over = Wire(Bool())
  val is_idle = state === idle
  val is_buzy = state === buzy
  val idle_mux = Mux(
    io.data_in_ready && io.data_out_ready,
    buzy,
    idle
  )
  val buzy_mux = Mux(
    load_over,
    idle,
    buzy
  )
  state := Mux(is_idle, idle_mux, buzy_mux)

//  val vector_num = HEAD_VECNUM/LOAD_VECNUM
//  val vector_decode_plus = 0
//  val vector_cnt = Wire(UInt(log2Up(vector_num + vector_decode_plus).W))
//  val vector_last =vector_cnt ===  Mux(is_prefill,(vector_num - 1).U ,(vector_num + vector_decode_plus - 1).U)


  val vector_num = HEAD_VECNUM/LOAD_VECNUM
  val vector_decode_plus = math.max(vector_num, MAX_SEQLEN*HEAD_VECNUM/MULNUM)
  val vector_cnt = Wire(UInt(log2Up(vector_decode_plus).W))
  val vector_last =vector_cnt ===  Mux(is_prefill,(vector_num - 1).U ,(vector_decode_plus - 1).U)



  vector_cnt := RegEnable(
    Mux(
      vector_last,
      0.U,
      vector_cnt + 1.U
    ),
    0.U,
    is_buzy
  )


//  val batch_cnt = Wire(UInt(log2Up(math.max(BATCHSIZE,MAX_PREFILL)).W))
//  val batch_prefill_plus = 0
//  val batch_cnt_last = batch_cnt === Mux(is_prefill,prelen + batch_prefill_plus.U , (BATCHSIZE - 1).U)
  val batch_prefill_plus = math.max(MAX_PREFILL,MAX_PREFILL*MAX_PREFILL*LOAD_VECNUM/MULNUM)
  val batch_cnt = Wire(UInt(log2Up(math.max(BATCHSIZE,batch_prefill_plus)).W))
  val batch_cnt_last = batch_cnt === Mux(is_prefill,(batch_prefill_plus -1).U , (BATCHSIZE - 1).U)
  batch_cnt := RegEnable(
    Mux(
      batch_cnt_last,
      0.U,
      batch_cnt + 1.U
    ),
    is_buzy && vector_last
  )

  load_over := is_buzy && vector_last && batch_cnt_last

  io.data_in_addr := batch_cnt * vector_num.U + vector_cnt
  io.data_in_last := is_buzy && vector_last && batch_cnt_last


  io.data_out := io.data_in
  io.data_out_valid :=RegNext (is_buzy && (is_prefill && batch_cnt <= prelen || !is_prefill && vector_cnt <(vector_num ).U ) ,false.B)
  io.data_out_start := is_buzy && vector_cnt === 0.U && batch_cnt === 0.U




}
