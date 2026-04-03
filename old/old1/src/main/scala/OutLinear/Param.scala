package OutLinear

object Param {
  val DATAW = 8
  val BATCHSIZE = 32

  // Prefill/Decode 模式参数
  val MAX_PREFILL = 8
  val MAX_SEQLEN = 26

  // 计算阵列参数
  val ROW = 12          // 每周期处理12个输入元素
  val COL = 36          // 36个输出通道

  // 矩阵维度: 64 x 768
  // 输入: 64维向量 (DM2 输出的 Attention 结果)
  // 输出: 768维向量 (映射回原始维度)
  val ROW_W = 64        // 输入维度 (HEAD_DIM)
  val COL_W = 768       // 输出维度

  val ROWBLOCK = (ROW_W + ROW - 1) / ROW  // = 6 (输入分块数，向上取整)
  val COLBLOCK = (COL_W + COL - 1) / COL  // = 22 (输出分块数)

  // 数据存储参数
  val MEM_WIDTH = DATAW * ROW          // = 96 bits (12个8-bit元素)
  val MEM_DEPTH = 2048

  // DM2 输出参数
  val DM2_VECNUM = 64                  // DM2 输出向量维度
  val DM2_WIDTH = DATAW * DM2_VECNUM   // = 512 bits

  // 权重存储参数
  val WMEM_WIDTH = DATAW * COL         // = 288 bits (36个8-bit元素)
  val WMEM_DEPTH_PER_HEAD = ROW * ROWBLOCK * COLBLOCK  // = 12 * 6 * 22 = 1584
  val HEAD_NUM = 12                    // 注意力头数
  val WMEM_DEPTH = WMEM_DEPTH_PER_HEAD * HEAD_NUM  // = 19008

  // 输出参数
  val OUT_VECTOR = COL_W               // = 768
  val SUBVEC = 12                      // 每周期输出元素数
}
