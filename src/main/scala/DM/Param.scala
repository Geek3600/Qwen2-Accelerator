package DM

object Param {
    val BATCHSIZE = 32
    val SINGLE_QUERY_BATCH = 12
    val LBANCHNUM = 4
    val LBATCHSIZE = BATCHSIZE * LBANCHNUM // 加速器可以同时为128个用户维护独立的历史KVcache
    val LOAD_VECNUM = 2 // 每周期从Load0收到2个Q元素+2个K元素
    val HEAD_VECNUM = 64 // 一个Q/K向量有64个元素
    // Attention 子系统的历史长度已经切到 full-seq 目标，供 token-by-token system wrapper 使用。
    // 非 attention 主链仍由 Top 单独保持单 token 运行，不依赖这里的长序列配置。
    val MAX_SEQLEN = 912
    val MAX_PREFILL = 912
    val MULNUM = 32 //32个乘法器
    val DATAW = 8 // 数据位宽
    val MEM_DEPTH = 512

}
