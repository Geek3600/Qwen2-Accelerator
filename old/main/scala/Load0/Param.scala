package Load0

//import DM.Param._

object Param {
  val LOAD_VECNUM= 2 // 每个时钟周期加载的向量元素数量
  val HEAD_VECNUM = 64 // 一个注意力头的维度，意思是每个注意力头处理64维的向量
  val DATAW = 8 // 数据位宽
  val MEMW = LOAD_VECNUM*3*DATAW // 内存宽度，3是因为同时加载QKV三个向量的对应元素
  val VECNUM = HEAD_VECNUM/LOAD_VECNUM //= 64 / 2 = 32，加载一个完整向量需要的周期数
  val MAX_PREFILL = 8
  val BATCHSIZE = 32
}
