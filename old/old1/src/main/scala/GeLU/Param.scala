package GeLU

object Param {
  val DATAW = 8
  val BATCHSIZE = 32

  // Prefill/Decode 模式参数
  val MAX_PREFILL = 8
  val MAX_SEQLEN = 26

  // 向量单元数（每周期处理的元素数）
  val VECNUM = 12  // 每周期处理 12 个元素

  // 数据维度：4H = 3072
  val DATA_DIM = 3072

  // 存储器参数
  val MEM_WIDTH = DATAW * VECNUM  // = 96 bits
  val MEM_DEPTH = 2048
}
