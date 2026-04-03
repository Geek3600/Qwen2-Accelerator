package OutLinear

import OutLinear.Param._
import QuantCommon.Fp32VecAdd
import QuantCommon.Precision._
import chisel3._
import chisel3.util._

class OutLinearFP32 extends Module {
  val io = IO(new Bundle() {
    val layer_st = Input(Bool())

    val data_in_st = Input(Bool())
    val data_in = Input(UInt(HEAD_WIDTH.W))
    val data_in_addr = Input(UInt(log2Up(MEM_DEPTH).W))
    val data_in_valid = Input(Bool())
    val data_in_last = Input(Bool())
    val data_ready = Output(Bool())

    val weight_init_mode = Input(Bool())
    val weight_init_data = Input(UInt(WMEM_WIDTH.W))
    val weight_init_addr = Output(UInt(log2Up(WMEM_DEPTH).W))
    val bias_init_data = Input(UInt(FP32_PACK_WIDTH.W))
    val bias_init_valid = Input(Bool())

    val out_scale = Input(UInt(FP32_WIDTH.W))

    val cfg_prefill = Input(Bool())
    val cfg_seqlen = Input(UInt(log2Up(MAX_SEQLEN).W))
    val cfg_valid = Input(Bool())

    val data_out = Output(UInt(FP32_PACK_WIDTH.W))
    val data_out_st = Output(Bool())
    val data_out_addr = Output(UInt(log2Up(MEM_DEPTH).W))
    val data_out_valid = Output(Bool())
    val data_out_last = Output(Bool())
    val data_out_ready = Input(Bool())
  })

  val is_prefill = RegEnable(io.cfg_prefill, io.cfg_valid)
  val seqlen = RegEnable(io.cfg_seqlen, io.cfg_valid)
  val actual_batchsize = Mux(is_prefill, seqlen, (BATCHSIZE - 1).U)

  val head_buffer = Reg(Vec(BATCHSIZE, Vec(HEAD_NUM, UInt(HEAD_WIDTH.W))))
  val head_cnt = RegInit(0.U(log2Up(HEAD_NUM).W))
  val head_last = head_cnt === (HEAD_NUM - 1).U
  val feed_token_cnt = RegInit(0.U(log2Up(BATCHSIZE).W))
  val feed_chunk_cnt = RegInit(0.U(log2Up(ROWBLOCK).W))
  val feed_token_last = feed_token_cnt === actual_batchsize
  val feed_chunk_last = feed_chunk_cnt === (ROWBLOCK - 1).U

  val idle :: collecting :: feeding :: Nil = Enum(3)
  val state = RegInit(idle)
  val is_feeding = state === feeding

  val mem_inst = Module(new DataMem(MEM_DEPTH, MEM_WIDTH))
  val lu_inst = Module(new LoadUnit)
  val cu_inst = Module(new CUFP32)
  val su_inst = Module(new StoreUnitFP32)
  val lw_inst = Module(new LoadWeight)
  val bias_mem = RegInit(VecInit(Seq.fill(COL_W / ROW)(0.U(FP32_PACK_WIDTH.W))))
  val bias_init_cnt = RegInit(0.U(log2Up(COL_W / ROW).W))
  val token_addr = io.data_in_addr(log2Up(BATCHSIZE) - 1, 0)

  when(io.bias_init_valid) {
    bias_mem(bias_init_cnt) := io.bias_init_data
    bias_init_cnt := Mux(bias_init_cnt === (COL_W / ROW - 1).U, 0.U, bias_init_cnt + 1.U)
  }

  when(io.data_in_valid && !is_feeding) {
    head_buffer(token_addr)(head_cnt) := io.data_in
  }

  switch(state) {
    is(idle) {
      when(io.data_in_valid) {
        state := Mux(io.data_in_last && head_last, feeding, collecting)
        when(io.data_in_last && head_last) {
          head_cnt := 0.U
          feed_token_cnt := 0.U
          feed_chunk_cnt := 0.U
        }.elsewhen(io.data_in_last) {
          head_cnt := head_cnt + 1.U
        }
      }
    }
    is(collecting) {
      when(io.data_in_valid && io.data_in_last) {
        when(head_last) {
          state := feeding
          head_cnt := 0.U
          feed_token_cnt := 0.U
          feed_chunk_cnt := 0.U
        }.otherwise {
          head_cnt := head_cnt + 1.U
        }
      }
    }
    is(feeding) {
      when(mem_inst.io.w_ready) {
        when(feed_chunk_last) {
          feed_chunk_cnt := 0.U
          when(feed_token_last) {
            state := idle
            feed_token_cnt := 0.U
          }.otherwise {
            feed_token_cnt := feed_token_cnt + 1.U
          }
        }.otherwise {
          feed_chunk_cnt := feed_chunk_cnt + 1.U
        }
      }
    }
  }

  val token_heads = head_buffer(feed_token_cnt)
  val full_token_vec = Wire(Vec(ROW_W, UInt(DATAW.W)))
  for (h <- 0 until HEAD_NUM) {
    val head_vec = token_heads(h).asTypeOf(Vec(HEAD_DIM, UInt(DATAW.W)))
    for (i <- 0 until HEAD_DIM) {
      full_token_vec(h * HEAD_DIM + i) := head_vec(i)
    }
  }

  val token_chunks = Wire(Vec(ROWBLOCK, UInt(MEM_WIDTH.W)))
  for (c <- 0 until ROWBLOCK) {
    token_chunks(c) := Cat((0 until ROW).reverse.map(i => full_token_vec(c * ROW + i)))
  }

  val mem_write_valid = is_feeding && mem_inst.io.w_ready
  val mem_write_addr = feed_token_cnt * ROWBLOCK.U + feed_chunk_cnt
  val mem_write_last = feed_chunk_last && feed_token_last
  val mem_write_st = feed_token_cnt === 0.U && feed_chunk_cnt === 0.U

  mem_inst.io.w_st := mem_write_valid && mem_write_st
  mem_inst.io.w_last := mem_write_last
  mem_inst.io.w_data := token_chunks(feed_chunk_cnt)
  mem_inst.io.w_addr := mem_write_addr
  mem_inst.io.w_valid := mem_write_valid

  mem_inst.io.r_last := lu_inst.io.data_in_last
  mem_inst.io.r_addr := lu_inst.io.data_in_addr
  mem_inst.io.r_en := lu_inst.io.read_en

  lu_inst.io.data_in := mem_inst.io.r_data
  lu_inst.io.data_in_ready := mem_inst.io.r_ready
  lu_inst.io.data_out_ready := io.data_out_ready
  lu_inst.io.cfg_prefill := io.cfg_prefill
  lu_inst.io.cfg_seqlen := io.cfg_seqlen
  lu_inst.io.cfg_valid := io.cfg_valid

  cu_inst.io.data_in := lu_inst.io.data_out
  cu_inst.io.data_in_valid := lu_inst.io.data_out_valid
  cu_inst.io.w_data := lw_inst.io.data_out
  cu_inst.io.w_valid := lw_inst.io.data_out_valid
  cu_inst.io.out_scale := io.out_scale
  cu_inst.io.cfg_prefill := io.cfg_prefill
  cu_inst.io.cfg_seqlen := io.cfg_seqlen
  cu_inst.io.cfg_valid := io.cfg_valid

  su_inst.io.data_in := cu_inst.io.data_out
  su_inst.io.data_in_valid := cu_inst.io.data_out_valid
  su_inst.io.cfg_prefill := io.cfg_prefill
  su_inst.io.cfg_seqlen := io.cfg_seqlen
  su_inst.io.cfg_valid := io.cfg_valid

  lw_inst.io.update := cu_inst.io.w_update
  lw_inst.io.st := io.layer_st
  lw_inst.io.init_mode := io.weight_init_mode
  lw_inst.io.init_data := io.weight_init_data

  io.data_ready := !is_feeding
  io.weight_init_addr := lw_inst.io.init_addr

  val bias_add = Module(new Fp32VecAdd)
  bias_add.io.a := su_inst.io.data_out
  bias_add.io.b := bias_mem(su_inst.io.data_out_addr(log2Up(COL_W / ROW) - 1, 0))

  io.data_out := bias_add.io.out
  io.data_out_valid := su_inst.io.data_out_valid
  io.data_out_addr := su_inst.io.data_out_addr
  io.data_out_st := su_inst.io.data_out_st
  io.data_out_last := su_inst.io.data_out_last
}

object OutLinearFP32Gen extends App {
  emitVerilog(new OutLinearFP32, Array("--target-dir", "generated"))
}
