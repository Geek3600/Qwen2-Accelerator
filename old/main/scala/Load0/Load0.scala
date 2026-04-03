package Load0

import Load0.Param._
import chisel3._
import chisel3.util._

//                     DATAW = 8 bits
//                            │
//            ┌───────────────┼───────────────┐
//            │               │               │
//            ▼               ▼               ▼
//       Q向量元素        K向量元素        V向量元素
//       (8 bits)        (8 bits)        (8 bits)
//            │               │               │
//            └───────┬───────┴───────┬───────┘
//                    │               │
//                    ▼               ▼
//              LOAD_VECNUM = 2 (每周期加载2个)
//                    │
//                    ▼
//           ┌────────────────────────────────────┐
//           │  MEMW = 2 × 3 × 8 = 48 bits        │
//           │  [Q0,Q1] [K0,K1] [V0,V1]           │
//           └────────────────────────────────────┘
//                    │
//                    │ 重复 VECNUM = 32 次
//                    ▼
//           ┌────────────────────────────────────┐
//           │  完整向量: HEAD_VECNUM = 64 维       │
//           │  Q[63:0], K[63:0], V[63:0]         │
//           └────────────────────────────────────┘
//                    │
//                    │ 重复 MAX_PREFILL 或 BATCHSIZE 次
//                    ▼
//           ┌────────────────────────────────────┐
//           │  Prefill: 最多8个token的序列         │
//           │  Decode:  32个序列各1个新token       │
//           └────────────────────────────────────┘

// load0本质是一个地址生成器，它不存储数据，只是根据输入的地址生成器，生成输出的地址
class Load0 extends Module {

  val io = IO(new Bundle() {
    val cfg_seqlen = Input(UInt(log2Up(MAX_PREFILL).W))
    val cfg_prefill = Input(Bool())
    val cfg_valid = Input(Bool())

    val data_in = Input(UInt(MEMW.W)) // 从外部内存读进来的数据，每次同时读进QKV三个矩阵的对应元素
    val data_in_ready = Input(Bool())
    val data_in_addr = Output(UInt(32.W)) // 从外部内存读数据的地址


    val data_out_st = Output(Bool())
    val data_out_last = Output(Bool())
    val data_out_ready = Input(Bool())
    val data_out = Output(UInt(MEMW.W)) // 输出到下游模块的数据，与data_in是直连的，从外部内存读进来后马上就输出
    val data_out_valid = Output(Bool())
    val data_out_addr = Output(UInt(32.W)) // 将数据输出到下游模块存储器的地址


  })

  val is_prefill = RegEnable(io.cfg_prefill, io.cfg_valid)
  val seqlen = RegEnable(io.cfg_seqlen, io.cfg_valid)

  //  状态转换逻辑:
  //  1. idle → buzy: 当 data_in_ready && data_out_ready 同时为真（握手成功）
  //  2. buzy → idle: 当完成所有向量加载（根据prefill/decode模式不同）
  val idle :: buzy :: Nil = Enum(2)
  val state = RegInit(idle)
  val is_idle = state === idle
  val is_buzy = state === buzy

  // 开始信号
  val fire = io.data_in_ready && io.data_out_ready
  dontTouch(fire)

  // 这个计数器是两用的，prefill阶段和decode阶段都会用 [1,N,D]或者[1,N,A,Dh]
  // prefill阶段：[1,N,D]或者[1,N,A,Dh]，A=64，每次加载两个长度为Dh的向量，所以要加载32次
  // decode阶段：[B,1,D]或者[B,1,A,Dh]，A=64，每次加载两个长度为Dh的向量，所以要加载32次
  val vec_cnt = Wire(UInt(log2Up(VECNUM).W))
  val vec_last = vec_cnt === (VECNUM - 1).U
  vec_cnt := RegEnable(
    Mux(
      vec_last,
      0.U,
      vec_cnt + 1.U
    ),
    0.U,
     is_buzy
  )

  // prefill阶段的计数器 
  // 数据维度：[1,N,D]或者[1,N,A,Dh] 用于计数N，也就是seqlen
  val prefill_cnt = Wire(UInt(log2Up(MAX_PREFILL).W))
  val prefill_last = prefill_cnt === seqlen
  prefill_cnt := RegEnable(
    Mux(
      prefill_last,
      0.U,
      prefill_cnt + 1.U
    ),
    0.U,
    vec_last && is_buzy && is_prefill // 只在向量加载完毕时，并且处于prefill阶段时，才递增
  )

  // decode阶段的计数器
  // 数据维度：[B,1,D]或者[B,1,A,Dh] 用于计数B，也就是batch size
  val batch_cnt = Wire(UInt(log2Up(BATCHSIZE).W))
  val batch_last = batch_cnt === (BATCHSIZE - 1).U
  batch_cnt := RegEnable(
    Mux(
      batch_last,
      0.U,
      batch_cnt + 1.U
    ),
    0.U,
    vec_last && is_buzy && !is_prefill
  )

  val idle_mux = Mux(fire,buzy,idle) // 如果握手成功，就进入buzy状态
  // vec_last表示decode阶段中一个batch或者prefill阶段中一个seq len向量加载完
  // is_prefill && prefill_last 表示处于prefill阶段，并且所有seq的向量加载完毕
  // /!is_prefill && batch_last 表示处于decode阶段，并且所有batch向量加载完毕
  // 如果满足条件就可以进入idle
  val buzy_mux = Mux(vec_last && (is_prefill && prefill_last || !is_prefill && batch_last ),idle_mux, buzy)
  state := Mux(is_buzy, buzy_mux, idle_mux)


  io.data_in_addr := Mux(is_prefill, prefill_cnt*VECNUM.U + vec_cnt, batch_cnt*VECNUM.U + vec_cnt)


  io.data_out := io.data_in
  io.data_out_st := RegNext( is_buzy && vec_cnt === 0.U && prefill_cnt === 0.U && batch_cnt === 0.U, false.B) // 刚开始的时候，拉高一下，表示开始输出
  io.data_out_last := RegNext( is_buzy && vec_last && (is_prefill && prefill_last || !is_prefill && batch_last ),false.B) // 最后结束的时候，拉高一下，表示输出结束
  io.data_out_valid := RegNext(is_buzy,false.B) // 如果状态为busy，则输出有效
  io.data_out_addr:= RegNext(Mux(is_prefill, prefill_cnt*VECNUM.U + vec_cnt, batch_cnt*VECNUM.U + vec_cnt))

}
object load0gen extends App {
  emitVerilog(new Load0, Array("--target-dir", "generated"))
}