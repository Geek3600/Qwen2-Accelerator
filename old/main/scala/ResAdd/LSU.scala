package ResMEM

import ResMEM.Param._
import chisel3._
import chisel3.util._


//ResMem的作用：将Load0每周期输入的2个V元素，收集拼接成完整的64维向量后输出给DM2

// LSU的作用：负责生成地址，从DataMem读取数据，并输出
// 本质上是一个LoadU
class LSU extends Module {
  val io = IO(new Bundle() {
    val cfg_seqlen = Input(UInt(log2Up(MAX_PRELEN).W))
    val cfg_prefill = Input(Bool())
    val cfg_valid = Input(Bool())

    val data_in = Input(UInt((DATAINW).W))
    val data_in_addr = Output(UInt(log2Up(BATCHSIZE * DATAOUTNUM/DATAINNUM).W))
    val data_in_last = Output(Bool())
    val data_in_ready = Input(Bool())

    val data_out = Output(UInt((DATAOUTW).W))
    val data_out_valid = Output(Bool())
    val data_out_ready = Input(Bool()) // 下一级传回的数据接收信号，表示是否准备好接收数据
    val data_out_start = Output(Bool())

    val data_out_last = Output(Bool())
    val data_out_addr = Output(UInt(log2Up(BATCHSIZE).W))
  })

  val prefill = RegEnable(io.cfg_prefill,io.cfg_valid)
  val seqlen = RegEnable(io.cfg_seqlen,io.cfg_valid)

  // 状态机：
  // idle → buzy (当 data_in_ready && data_out_ready)
  // buzy → idle (当 load_over)
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

  if(DATAOUTNUM > DATAINNUM) {
    val CATNUM = DATAOUTNUM/DATAINNUM // = 64/2 = 32，收集32次两个V元素，拼接成64维向量
    val v_cnt = Wire(UInt(log2Up(CATNUM).W))
    val v_last =( CATNUM-1).U === v_cnt
    // 内层循环
    v_cnt := RegEnable(
      Mux(
        v_last,
        0.U,
        v_cnt + 1.U
      ),
      0.U,
      is_buzy
    )

    // prefill阶段外层循环
    val prefill_cnt = Wire(UInt(log2Up(MAX_PRELEN).W))
    val prefill_last = prefill_cnt === seqlen
    prefill_cnt := RegEnable( // 
      Mux(
        prefill_last,
        0.U,
        prefill_cnt + 1.U
      ),
      0.U,
      is_buzy && v_last && prefill
    )

    //decode阶段的外层循环
    val batch_cnt = Wire(UInt(log2Up(BATCHSIZE).W))
    val batch_last = batch_cnt === (BATCHSIZE -1 ).U
    batch_cnt := RegEnable(
      Mux(
        batch_last,
        0.U,
        batch_cnt + 1.U
      ),
      0.U,
      is_buzy && v_last && !prefill
    )

    // 所有数据都已经加载完
    load_over := is_buzy && v_last && (prefill && prefill_last || batch_last && !prefill)

    // 生成地址
    io.data_in_addr := v_cnt + Mux(prefill, prefill_cnt, batch_cnt) *(CATNUM).U
    io.data_in_last := load_over // 加载完的信号作为最后数据信号

    // rescache负责收集从DataMem读取的数据
    val rescache = Wire(Vec(CATNUM, UInt(DATAINW.W)))
    for ( i<- 0 until CATNUM){
      rescache(i) := RegEnable(
        io.data_in,
        RegNext(v_cnt === i.U && is_buzy, false.B)
      )
    }
    io.data_out := rescache.asUInt

    io.data_out_valid :=  RegNext(RegNext(v_last && is_buzy, false.B), false.B)
    io.data_out_start :=  is_idle && io.data_in_ready && io.data_out_ready
    io.data_out_last := RegNext(RegNext(load_over, false.B), false.B)
    io.data_out_addr := RegNext(RegNext(Mux(prefill, prefill_cnt,batch_cnt)))
  } else{
    val SLICENUM = DATAINNUM / DATAOUTNUM
  }
}
