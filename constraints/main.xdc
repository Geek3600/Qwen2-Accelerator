# ============================================================
# Qwen2 Accelerator - 精简约束文件
# 只保留核心约束：时钟、DSP、BRAM、URAM
# ============================================================

# 时钟约束
create_clock -period 10.000 -name clk [get_ports clock]

# DSP 约束 - 强制所有乘法器使用 DSP48E2（1398 个实例）
set_property USE_DSP48 yes [get_cells -hierarchical -filter {REF_NAME =~ "Multiplier*"}]
set_property USE_DSP48 yes [get_cells -hierarchical -filter {REF_NAME =~ "SignedMultiplier*"}]

# ============================================================
# URAM 约束
# ============================================================

# 权重存储
# 真实生成的 wrapper 类型是 weight_banks_4096x72。
# 之前基于层次路径的 NAME 过滤没有命中这些实例，导致权重 banks 全部回落到 BRAM。
# 这里优先按 REF_NAME 命中 204 个 wrapper 实例（QKV 48 + Out 20 + FFNUp 68 + FFNDown 68）。
set_property RAM_STYLE ULTRA [get_cells -hierarchical -filter {REF_NAME == weight_banks_4096x72}]

# 同时对展开后的层次名做兜底匹配，覆盖 wrapper 以及其内部 Memory_reg 层次。
set_property RAM_STYLE ULTRA [get_cells -hierarchical -filter {NAME =~ "*weight_banks_*_ext*"}]

# LayerNorm 数据缓存：4K x 96
set_property RAM_STYLE ULTRA [get_cells -hierarchical -filter {REF_NAME == mem_4096x96}]

# Softmax 数据缓存：4K x 208
set_property RAM_STYLE ULTRA [get_cells -hierarchical -filter {REF_NAME == mem_4096x208}]

# KCache / VCache：3328 x 512
set_property RAM_STYLE ULTRA [get_cells -hierarchical -filter {REF_NAME == mem_3328x512}]
set_property RAM_STYLE ULTRA [get_cells -hierarchical -filter {NAME =~ "*KCache*"}]
set_property RAM_STYLE ULTRA [get_cells -hierarchical -filter {NAME =~ "*VCache*"}]

# ============================================================
# BRAM 约束
# ============================================================

# 各线性层 / GELU / 残差缓存的数据双缓冲：2K x 96
set_property RAM_STYLE BLOCK [get_cells -hierarchical -filter {REF_NAME == mem_2048x96}]

# DM1 score 中间缓存：2K x 32
set_property RAM_STYLE BLOCK [get_cells -hierarchical -filter {REF_NAME == mem_2048x32}]

# VCache 输入缓冲：1K x 16
set_property RAM_STYLE BLOCK [get_cells -hierarchical -filter {REF_NAME == mem_1024x16}]

# DM2 小型片上缓存
set_property RAM_STYLE BLOCK [get_cells -hierarchical -filter {REF_NAME == mem_64x512}]
set_property RAM_STYLE BLOCK [get_cells -hierarchical -filter {REF_NAME == mem_64x208}]
