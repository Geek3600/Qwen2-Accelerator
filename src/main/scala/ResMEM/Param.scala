package ResMEM

object Param {
  val DATAW = 8 // 数据位宽
  val DATAINNUM= 2 // 
  val DATAOUTNUM = 64
//  val VECNUM = 64
  val DATAINW = DATAW*DATAINNUM
  val DATAOUTW = DATAW*DATAOUTNUM

  val BATCHSIZE = 32
  val SINGLE_QUERY_BATCH = 12
  val MAX_PRELEN = 912





//  val BLOCKNUM = (D_H + COL - 1)/COL

//  val D_H = 768

}
