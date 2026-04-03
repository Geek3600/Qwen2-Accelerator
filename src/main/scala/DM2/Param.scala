package DM2

object Param {
  val BATCHSIZE = 32 // decode阶段下的batch size
  val SINGLE_QUERY_BATCH = 12
  val LBANCHNUM = 4
  val LBATCHSIZE = BATCHSIZE * LBANCHNUM
  val HEAD_VECNUM = 64 // 每个注意力头的维度
  val MAX_SEQLEN = 912
  val MAX_PREFILL = 912
  val MULNUM = 32 //乘法器数量
  val DATAW = 8 // 数据位宽
  val MEM_DEPTH = BATCHSIZE
}


