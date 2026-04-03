package DM

import QuantCommon.Precision._

object FP32Param {
  val BATCHSIZE = 32
  val SINGLE_QUERY_BATCH = 12
  val LOAD_VECNUM = 2
  val HEAD_VECNUM = 64
  val MAX_SEQLEN = Param.MAX_SEQLEN
  val MAX_PREFILL = Param.MAX_PREFILL
  val MULNUM = 32
  val DATAW = INT8_WIDTH
  val OUT_DATAW = FP32_WIDTH
  val OUT_WIDTH = MAX_SEQLEN * OUT_DATAW
  val MEM_DEPTH = 512
}
