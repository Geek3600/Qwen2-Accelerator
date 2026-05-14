# ============================================================
# opt_acc_core 资源约束
# 适用场景：已将 opt_acc_core 接入 v0825 的 Block Design 后，
#          在顶层工程中作为额外 XDC 加入综合/实现。
#
# 目标：
#   1. 强制关键乘法器走 DSP48E2
#   2. 强制大容量权重/Attention 中间缓存走 URAM
#   3. 强制中小容量缓存走 BRAM，避免 LUTRAM 爆炸
#
# 注意：
#   - 这里不再添加 create_clock，BD 顶层已有时钟约束。
#   - 这里不使用旧 cnn_core 的层次路径，而是按当前 opt_acc_core
#     内部综合后唯一的 REF_NAME 做约束，避免实例名变化导致失效。
# ============================================================

# ------------------------------------------------------------
# 0. 高扇出控制信号
# ------------------------------------------------------------
# 这部分改为 RTL 属性实现。
# 原因：
#   - XDC 在综合前对内部 net 的匹配不稳定，容易出现空对象告警
#   - `.xdc` 不支持 `if`，无法像 Tcl 一样做存在性保护
# 当前高扇出复制由：
#   - OutLinear/QKVLinear 内部关键寄存器的 `max_fanout`
#   - opt_acc_core.sv 顶层 wrapper 控制线的 `max_fanout`
# 来承担。

# ------------------------------------------------------------
# 1. DSP 约束
# ------------------------------------------------------------
# 线性层 / FFN / QKV 的 8x8 乘法器
set_property -quiet USE_DSP yes [get_cells -quiet -hier -filter {
    REF_NAME =~ "SignedMultiplierInt8*" ||
    REF_NAME =~ "UnsignedSignedMultiplierInt8*" ||
    REF_NAME =~ "SignedMacChain32*"
}]

# QKV / OutLinear / FFN 的 int16->int32 加法树是当前 LUT 热点，
# 这里仅提示 Vivado 优先尝试用 DSP 做映射，不改功能和节拍。
set_property -quiet USE_DSP yes [get_cells -quiet -hier -filter {REF_NAME =~ "SignedAddTree32*"}]

# 新的 MAC 串联链也要显式打上 DSP 提示；否则替换掉旧 addTree 之后，
# 原先只命中 SignedAddTree32 / mul_list 的约束会整体失效。
set_property -quiet USE_DSP yes [get_cells -quiet -hier -filter {REF_NAME =~ "SignedMacChain32*"}]

# 历史上这里做过按 NAME 的兜底匹配，但 `*macChainList_*` 这类模式会把
# `SignedMacChain32` 实例内部的寄存器一起匹配到，导致 Vivado 把 `USE_DSP`
# 错误地下发到 `alignedIn*_reg` 之类的寄存器上。当前 Top_vivado.sv 中
# `SignedMacChain32 / SignedMultiplierInt8 / UnsignedSignedMultiplierInt8 /
# SignedAddTree32` 都有稳定的 REF_NAME，因此不再保留这种宽泛的 NAME 兜底。

# ------------------------------------------------------------
# 2. URAM 约束
# ------------------------------------------------------------
# 2.1 大矩阵权重存储
# 当前四个大线性模块的 WeightMem 已改成显式 XPM URAM wrapper，
# 不再依赖 inferred RAM + RAM_STYLE 约束推断。

# 2.2 DM / DM2 里的大容量 512-bit 缓存
# 现已改成显式 XPM URAM wrapper，不再依赖 inferred RAM + RAM_STYLE。

# ------------------------------------------------------------
# 3. BRAM 约束
# ------------------------------------------------------------
# 3.1 线性层/FFN/Out/QKV 的双缓冲 DataMem
set_property -quiet RAM_STYLE BLOCK [get_cells -quiet -hier -filter {REF_NAME == mem_2048x96}]

# 3.2 DM1/DM2 的 int32 / 宽向量中间缓存
# 16-token / single-tile 收口后，原来的 mem_2048x32 / mem_26x512 已不再是主实例名。
# 当前 Top_vivado.sv 中实际对应的是：
#   - mem_768x32
#   - mem_16x512
#   - mem_192x512
set_property -quiet RAM_STYLE BLOCK [get_cells -quiet -hier -filter {
    REF_NAME == mem_768x32 ||
    REF_NAME == mem_16x512 ||
    REF_NAME == mem_192x512
}]

# 3.3 ResMEM / VCache 小块缓冲
set_property -quiet RAM_STYLE BLOCK [get_cells -quiet -hier -filter {REF_NAME == mem_512x16}]

# 3.4 中等容量缓存
# ResAdd / ResAdd2 当前分别使用 1024x384 / 2048x384 缓冲
set_property -quiet RAM_STYLE BLOCK [get_cells -quiet -hier -filter {
    REF_NAME == mem_1024x384 ||
    REF_NAME == mem_2048x384
}]

# 3.5 Chisel Queue 生成的大宽度 FIFO RAM
# 这些小深度/中深度宽 RAM 当前更容易被 Vivado 推成 LUTRAM。
# 这里优先把它们压到 BRAM，不改功能，只改实现风格。
set_property -quiet RAM_STYLE BLOCK [get_cells -quiet -hier -filter {
    REF_NAME == ram_12x523 ||
    REF_NAME == ram_16x518 ||
    REF_NAME == ram_64x512 ||
    REF_NAME == ram_256x513 ||
    REF_NAME == ram_1024x396
}]

# ------------------------------------------------------------
# 4. 说明
# ------------------------------------------------------------
# 当前这份约束是按 Top_vivado.sv 实际综合出来的 REF_NAME 写的：
#   - DSP: SignedMultiplierInt8 / SignedMultiplierInt8_432 / UnsignedSignedMultiplierInt8 / SignedMacChain32
#   - URAM: XilinxUramCompatMem_* / xpm_memory_sdpram wrappers
#   - BRAM: mem_2048x96 / mem_768x32 / mem_512x16 / mem_1024x384 / mem_2048x384 / mem_16x512 / mem_192x512
#   - Queue RAM: ram_12x523 / ram_16x518 / ram_64x512 / ram_256x513 / ram_1024x396
#
# 如果后续重新生成 Top_vivado.sv 后模块名变化，需要重新核对这些 REF_NAME。
# ============================================================
