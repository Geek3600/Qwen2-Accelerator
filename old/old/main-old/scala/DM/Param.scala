package DM

object Param {
    val BATCHSIZE = 32
    val LBANCHNUM = 4
    val LBATCHSIZE = BATCHSIZE * LBANCHNUM
    val LOAD_VECNUM = 2
    val HEAD_VECNUM = 64
    val MAX_SEQLEN = 26
    val MAX_PREFILL = 8
    val MULNUM = 32 //要求
    val DATAW = 8
    val MEM_DEPTH = 512

//  val BATCHSIZE = 2
//  val LBANCHNUM = 1
//  val LBATCHSIZE = BATCHSIZE * LBANCHNUM
//  val LOAD_VECNUM = 2
//  val HEAD_VECNUM = 4
//  val MAX_SEQLEN = 4
//  val MAX_PREFILL = 2
//  val MULNUM = 2 //要求
//  val DATAW = 8
//  val MEM_DEPTH = 512
}
