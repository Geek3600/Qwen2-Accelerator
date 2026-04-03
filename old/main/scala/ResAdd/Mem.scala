package ResMEM
import ResMEM.Param._
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


//这里相对于标准的mem，增加了一个状态机，描述片上存储的状态是正在写，写完了，还是空的 三者分别是buzy，full，以及empey
//手动算一下，可能的组合一共6种full full，full buzy，full empey，buzy buzy， buzy empey， empey empey
//状态切换由w_st，w_last，r_last控制，分别表示开始写，写完，以及读完一个存储
class DataMen(val depth: Int, val width: Int ,val num :Int)extends Module{
  val io = IO(new Bundle() {
    val w_st = Input(Bool())
    val w_last = Input(Bool())
    val w_data = Input(UInt(DATAINW.W))
    val w_addr = Input(UInt(log2Up(depth).W))
    val w_valid = Input(Bool())
    val w_ready = Output(Bool())


    val r_last = Input(Bool())
    val r_data = Output(UInt(DATAINW.W))
    val r_addr = Input(UInt(log2Up(depth).W))
    val r_ready = Output(Bool())
  })
  val buzy_cnt = Wire(UInt(log2Up(num + 1).W))
  val full_cnt = Wire(UInt(log2Up(num + 1).W))
  buzy_cnt := RegEnable(
    Mux(
      io.w_st,
      Mux(
        io.w_last,
        buzy_cnt  ,
        buzy_cnt + 1.U
      ),
      Mux(
        io.w_last,
        buzy_cnt - 1.U,
        buzy_cnt
      )
    ),
    0.U,
    io.w_st || io.w_last
  )
  full_cnt := RegEnable(
    Mux(
      io.w_last,
      Mux(
        io.r_last,
        full_cnt,
        full_cnt + 1.U
      ),
      Mux(
        io.r_last,
        full_cnt - 1.U,
        full_cnt
      )
    ),
    0.U,
    io.w_last || io.r_last
  )

  io.w_ready := full_cnt + buzy_cnt < num.U // 有空闲缓冲区可写
  io.r_ready := full_cnt>0.U // 有写满的缓冲区，可读


  val w_ptr = Wire(UInt(log2Up(num ).W))
  val w_ptr_last = w_ptr === (num - 1).U
  val r_ptr = Wire(UInt(log2Up(num ).W))
  val r_ptr_last = r_ptr === (num - 1).U
  w_ptr := RegEnable(
    Mux(
      w_ptr_last,
      0.U,
      w_ptr + 1.U
    ),
    0.U,
    io.w_last
  )

  r_ptr := RegEnable(
    Mux(
      r_ptr_last,
      0.U,
      r_ptr + 1.U
    ),
    0.U,
    io.r_last
  )

  val mem_list = List.fill(num)(Module(new R1W1Mem(depth,width)))


  for(i <- 0 until num){
    mem_list(i).io.wen := w_ptr === i.U && io.w_valid
    mem_list(i).io.waddr := io.w_addr
    mem_list(i).io.wdata := io.w_data

    mem_list(i).io.ren := r_ptr === i.U
    mem_list(i).io.raddr := io.r_addr
  }

  io.r_data := MuxLookup(
    RegNext(r_ptr),
    0.U,
    (0 until num).map{i => i.U ->mem_list(i).io.rdata }
  )




}


object DataMenGen extends App {
  emitVerilog(new DataMen(16,32,2), Array("--target-dir", "generated"))
}