package DM2

import DM2.Param._
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



class DM2CU extends Module {
//  val BATCHSIZE = 1
//  val LBANCHNUM = 1
//  val LBATCHSIZE = BATCHSIZE * LBANCHNUM
//  val HEAD_VECNUM = 2
//  val MAX_SEQLEN = 2
//  val MAX_PREFILL = 1
//  val MULNUM = 32 //要求
//  //  val SEND_VECNUM = 26
//  val DATAW = 8
//  //
//  val MEM_DEPTH = 512
  val io =IO(new Bundle() {
    val cfg_seqlen =Input(UInt(log2Up(MAX_SEQLEN).W))
//    val cfg_prelen = Input(UInt(log2Up(MAX_PREFILL).W)) //prefill 长度减去1
    val cfg_prefill = Input(Bool())
    val cfg_valid = Input(Bool())

    val data_in_v = Input(UInt((HEAD_VECNUM * DATAW ).W)) //
    val data_in_v_valid = Input(Bool())

    val data_in_ctx = Input(UInt((MAX_SEQLEN * DATAW ).W))
    val data_in_ctx_valid = Input(Bool())

    val data_out = Output(UInt((HEAD_VECNUM * DATAW ).W))
    val data_out_valid = Output(Bool())

  })
  val prefill = RegEnable(io.cfg_prefill,io.cfg_valid)
  val seqlen = RegEnable(io.cfg_seqlen,io.cfg_valid)


  val VCache = Module(new R1W1Mem(LBATCHSIZE*MAX_SEQLEN,HEAD_VECNUM*DATAW))
  val v_buf = Wire(Vec(MAX_SEQLEN,UInt((HEAD_VECNUM*DATAW).W)))





  val prefill_load_cnt = Wire(UInt(log2Up(MAX_PREFILL).W))
  val prefill_load_last = prefill_load_cnt === seqlen
  prefill_load_cnt := RegEnable(
    Mux(
      prefill_load_last,
      0.U,
      prefill_load_cnt + 1.U
    ),
    0.U,
    prefill && io.data_in_v_valid
  )

  val lbatch_cnt = Wire(UInt(log2Up(LBATCHSIZE).W))
  val lbatch_last = lbatch_cnt === (LBATCHSIZE - 1).U
  lbatch_cnt := RegEnable(
    Mux(
      lbatch_last,
      0.U,
      lbatch_cnt +1.U
    ),
    0.U,
    prefill && io.data_in_v_valid && prefill_load_last || !prefill && io.data_in_v_valid
  )


  VCache.io.wen := io.data_in_v_valid
  VCache.io.waddr := Mux(prefill,lbatch_cnt * MAX_SEQLEN.U + prefill_load_cnt, lbatch_cnt * MAX_SEQLEN.U + seqlen)
  VCache.io.wdata := io.data_in_v






  val  stc_vcache::stc_getv :: stc_waitctx  :: stc_c ::Nil = Enum(4)
  val state = RegInit(stc_vcache)

  val is_getv = state === stc_getv
  val is_waitctx = state === stc_waitctx
  val is_c= state === stc_c



  val gev_cnt = Wire(UInt(log2Up(MAX_SEQLEN).W))
  val gev_last = gev_cnt === seqlen
  gev_cnt := RegEnable(
    Mux(
      gev_last,
      0.U,
      gev_cnt +1.U
    ),
    0.U,
    is_getv
  )


  val waitctx_cnt = Wire(UInt(log2Up(MAX_PREFILL).W))
  val waitctx_last = waitctx_cnt === seqlen
  waitctx_cnt := RegEnable(
    Mux(
      waitctx_last,
      0.U,
      waitctx_cnt + 1.U
    ),
    0.U,
    prefill && io.data_in_ctx_valid
  )


  val mul_cnt = Wire(UInt(log2Up(HEAD_VECNUM).W))
  val mul_last = mul_cnt === (HEAD_VECNUM - 1).U
  mul_cnt := RegEnable(
    Mux(
      mul_last,
      0.U,
      mul_cnt + 1.U
    ),
    0.U,
    is_c
  )


  val stc_vcache_mux = Mux(
    prefill,
    Mux(
      prefill_load_last && io.data_in_v_valid,
      stc_waitctx,
      stc_vcache
    ),
    Mux(
      io.data_in_v_valid,
      stc_getv,
      stc_vcache
    )
  )

  val stc_getv_mux = Mux(
    gev_last,
    stc_waitctx,
    stc_getv
  )

  val stc_waitctx_mux = Mux(
    io.data_in_ctx_valid,
    stc_c,
    stc_waitctx
  )

  val stc_c_mux = Mux(
    mul_last,
    Mux(
      waitctx_cnt === 0.U,
      stc_vcache_mux,
      stc_waitctx_mux
    ),
    stc_c
  )


  state := MuxLookup(
    state,
    state,
    List(
      stc_vcache -> stc_vcache_mux,
      stc_getv -> stc_getv_mux,
      stc_waitctx -> stc_waitctx_mux,
      stc_c -> stc_c_mux
    )
  )

  val lbatch_cnt_r = RegEnable(lbatch_cnt,prefill && io.data_in_v_valid && prefill_load_last || !prefill && io.data_in_v_valid)
  VCache.io.ren := is_getv
  VCache.io.raddr := gev_cnt + lbatch_cnt_r* MAX_SEQLEN.U


  for(i <- 0 until MAX_SEQLEN){
    val valid_decode = RegNext(is_getv && gev_cnt === i.U)
    val data_decode = VCache.io.rdata

    val valid_prefill = prefill && io.data_in_v_valid && prefill_load_cnt === i.U
    val data_prefill = io.data_in_v

    v_buf(i):= RegEnable(
      Mux(
        prefill,
        data_prefill,
        data_decode
      ),
      valid_prefill || valid_decode
    )
  }








  val v_list = (0 until MAX_SEQLEN).map{
    i => MuxLookup(
      mul_cnt,
      0.U,
      (0 until HEAD_VECNUM).map{j =>
        j.U -> v_buf(i)(DATAW*(j+ 1)- 1, DATAW*j)
      }
    )
  }


  val ctx = RegEnable(io.data_in_ctx, io.data_in_ctx_valid)
  val ctx_list = (0 until MAX_SEQLEN).map { i => Mux(seqlen >= i.U, ctx((i + 1) * DATAW - 1, i * DATAW), 0.U) }





  val mul_list = List.fill(MAX_SEQLEN)(Module(new Muler(DATAW)))
  val addrtree = Module(new AddTree(MAX_SEQLEN,DATAW*2))

  for( i <- 0 until MAX_SEQLEN){
    mul_list(i).io.in0 := ctx_list(i)
    mul_list(i).io.in1 := v_list(i)
    addrtree.io.ins(i) := mul_list(i).io.out
  }

  val delay_mulcnt_to_addtres = 2+log2Up(MAX_SEQLEN)
  val addresvalid_pipreg = Module(new PipReg(delay_mulcnt_to_addtres,1))
  addresvalid_pipreg.io.in := state === stc_c
  val addres_valid = addresvalid_pipreg.io.out.asBool

  val addrescnt_pipreg = Module(new PipReg(delay_mulcnt_to_addtres,log2Up(HEAD_VECNUM)))
  addrescnt_pipreg.io.in := mul_cnt
  val addrescnt = addrescnt_pipreg.io.out

  val res = Wire(Vec(HEAD_VECNUM,UInt(DATAW.W)))
  for( i <- 0 until HEAD_VECNUM){
    res(i) := RegEnable(addrtree.io.out , addres_valid && addrescnt === i.U)
  }
  io.data_out := res.asTypeOf(UInt((DATAW*HEAD_VECNUM).W))
  io.data_out_valid := RegNext(addres_valid && (addrescnt === (HEAD_VECNUM - 1).U) ,false.B)


}
