package Softmax

object Param {
  val DATAW = 8
  val BATCHSIZE = 32
  
  val SEQ_LEN = 26
  val V = 26

  val MEM_WIDTH = DATAW * V
  val MEM_DEPTH = 2048

  val WMEM_WIDTH = 1 * V
  val WMEM_DEPTH = 2048
}
