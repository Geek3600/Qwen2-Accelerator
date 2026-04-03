package DM2

object Param {
  val BATCHSIZE = 32
  val LBANCHNUM = 4
  val LBATCHSIZE = BATCHSIZE * LBANCHNUM
  val HEAD_VECNUM = 64
  val MAX_SEQLEN = 26
  val MAX_PREFILL = 8
  val MULNUM = 32 //要求

  //  val SEND_VECNUM = 26
  val DATAW = 8
  //
  val MEM_DEPTH = BATCHSIZE
}


