package DM

object Param {
    val BATCHSIZE = 32
    val LBANCHNUM = 4
    val LBATCHSIZE = BATCHSIZE * LBANCHNUM // 加速器可以同时为128个用户维护独立的历史KVcache
    val LOAD_VECNUM = 2 // 每周期从Load0收到2个Q元素+2个K元素
    val HEAD_VECNUM = 64 // 一个Q/K向量有64个元素
    val MAX_SEQLEN = 26  // 最大序列长度26
    val MAX_PREFILL = 8 // 最大prefill长度，也就是prompt的长度？
    val MULNUM = 32 //32个乘法器
    val DATAW = 8 // 数据位宽
    val MEM_DEPTH = 512

}
