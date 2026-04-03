package ResAdd2

object Param {
  val DATAW = 8
  val BATCHSIZE = 32

  // Prefill/Decode 模式参数
  val MAX_PREFILL = 8
  val MAX_SEQLEN = 26

  // 数据参数
  val SUBVEC = 12  // 每周期处理 12 个元素
  val DATA_WIDTH = DATAW * SUBVEC  // = 96 bits

  // 存储器参数
  val MEM_DEPTH = 2048
}
