package Load0

//import DM.Param._

object Param {
  val LOAD_VECNUM= 2
  val HEAD_VECNUM = 64
  val DATAW = 8
  val MEMW = LOAD_VECNUM*3*DATAW
  val VECNUM = HEAD_VECNUM/LOAD_VECNUM
  val MAX_PREFILL = 8
  val BATCHSIZE = 32
}
