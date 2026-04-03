# Qwen2 加速器综合上下文文档

**日期：2026-03-24**
**FPGA：Xilinx VU9P (xcvu9p-flga2104-2-i)**

---

## 📋 项目状态总览

### 当前阶段
- ✅ Chisel 代码标准化命名完成
- ✅ Verilog 生成成功
- ✅ 约束文件优化完成
- ⚠️ 综合完成但存储资源映射不完整

### 关键问题
**权重存储映射不完整：只有 46% 的 weight_banks 被正确映射到 URAM**

---

## 🏗️ 设计架构

### 流水线结构
```
LNAddrGen → LayerNorm → QKVLinear → Attention → OutLinear → ResAdd → FFNUp → GeLU → FFNDown → ResAdd2
```

### 模块组成
1. **LayerNorm** (2 个实例：layernorm, layernorm2)
2. **QKVLinear** - Q/K/V 线性映射
3. **Attention** - 包含 DM1, Softmax, VCache, DM2
4. **OutLinear** - 注意力输出映射
5. **FFNUp** - FFN 上投影 (H→4H)
6. **GeLU** - 激活函数
7. **FFNDown** - FFN 下投影 (4H→H)
8. **ResAdd/ResAdd2** - 残差连接

---

## 💾 存储器设计参数

### 权重存储 (Weight Banks)

| 模块 | Banks | Slices | 总数 | 每个大小 | 总容量 |
|------|-------|--------|------|----------|--------|
| **QKVLinear** | 12 | 4 | 48 | 4K×72 (288Kb) | 13.8 MB |
| **OutLinear** | 5 | 4 | 20 | 4K×72 (288Kb) | 5.76 MB |
| **FFNUp** | 17 | 4 | 68 | 4K×72 (288Kb) | 19.6 MB |
| **FFNDown** | 17 | 4 | 68 | 4K×72 (288Kb) | 19.6 MB |
| **总计** | - | - | **204** | - | **58.8 MB** |

**注意：设计中有 2 个处理阶段，所以实际 weight_banks 总数 = 204 × 2 = 408 个**

### 其他存储器

| 存储器 | 数量 | 大小 | 类型 | 用途 |
|--------|------|------|------|------|
| **KCache** | 2 | 3328×512 | URAM | Attention K 缓存 |
| **VCache** | 2 | 3328×512 | URAM | Attention V 缓存 |
| **LayerNorm Mem** | 2 | 4K×96 | URAM | LayerNorm 数据缓存 |
| **Softmax Mem** | 1 | 4K×208 | URAM | Softmax 数据缓存 |
| **Acc Mem** | 1 | 2K×192 | URAM | 累加器存储 |
| **总计** | - | - | **26 URAM** | - |

---

## 🔧 已完成的优化工作

### 1. 代码标准化命名 (2026-03-24)
**目的：**简化约束文件，统一命名规范

**修改内容：**
- `DataMen` → `DataMem`
- `Muler` → `Multiplier`
- `LoadU` → `LoadUnit`
- `StoreU` → `StoreUnit`
- `LoadW` → `LoadWeight`

**影响文件：**
- 所有 `src/main/scala/` 下的模块
- 重新生成了 `generated/Top.sv`

### 2. 约束文件优化
**文件：**`constraints/main.xdc`

**核心约束：**
```tcl
# 时钟约束
create_clock -period 10.000 -name clk [get_ports clock]

# DSP 强制使用
set_property USE_DSP48 yes [get_cells -hierarchical -filter {REF_NAME =~ "*Multiplier*"}]

# 权重存储 → URAM
set_property RAM_STYLE ULTRA [get_cells -hierarchical -filter {REF_NAME =~ "*LoadWeight/weight_mem/weight_banks*"}]

# KCache/VCache → URAM
set_property RAM_STYLE ULTRA [get_cells -hierarchical -filter {NAME =~ "*KCache*"}]
set_property RAM_STYLE ULTRA [get_cells -hierarchical -filter {NAME =~ "*VCache*"}]
```

### 3. 目录结构整理
```
Qwen2-Accelerator/
├── scripts/
│   ├── synthesis/          # 综合脚本
│   └── analysis/           # 分析工具
├── constraints/
│   └── main.xdc           # 主约束文件
├── testbench/
│   └── top.cpp            # 主测试文件
├── logs/                  # 日志文件
├── waveforms/             # 波形文件
└── generated/
    └── Top.sv             # 生成的 Verilog
```

---

## 📊 综合结果对比

### BRAM 版本 (失败)
**问题：**BRAM 资源不足

| 资源 | 使用量 | 可用量 | 使用率 |
|------|--------|--------|--------|
| LUT | 161,932 | 1,182,240 | 13.7% |
| FF | 196,484 | 2,364,480 | 8.3% |
| DSP48E2 | 1,894 | 6,840 | 27.7% |
| **RAMB36E2** | **1,588** | **2,160** | **73.5%** |
| URAM288 | 26 | 960 | 2.7% |

**分析：**
- 需求：408 weight_banks × 8 RAMB36 = 3,264 个
- 可用：2,160 个
- **超出 51%** → 大量 weight_banks 未映射

### URAM 版本 (当前)
**问题：**约束未完全生效

| 资源 | 使用量 | 可用量 | 使用率 |
|------|--------|--------|--------|
| LUT | 160,248 | 1,182,240 | 13.5% |
| FF | 196,527 | 2,364,480 | 8.3% |
| DSP48E2 | 1,894 | 6,840 | 27.7% |
| RAMB36E2 | 84 | 2,160 | 3.9% |
| **URAM288** | **214** | **960** | **22.3%** |

**分析：**
- 约束应用：408 个 weight_banks
- 实际映射：214 - 26 = 188 个 weight_banks
- **缺失：220 个 (54%)** → 约束未完全生效

---

## ⚠️ 当前问题详细分析

### 问题描述
虽然约束已应用到 408 个 weight_banks，但实际只有 188 个被映射到 URAM。

### 可能原因

1. **权重数据未初始化**
   - 如果 weight_banks 的初始值未设置，综合工具可能优化掉
   - Chisel 中的 `SyncReadMem` 默认无初始值

2. **约束优先级问题**
   - 可能有其他约束覆盖了 URAM 约束
   - 或者约束模式匹配不完整

3. **综合工具优化**
   - Vivado 可能认为某些 weight_banks 不需要实现
   - 或者将它们合并/优化为其他结构

4. **路径匹配问题**
   - 约束中的路径模式可能没有匹配到所有实例
   - 需要检查实际的层次路径

### 验证方法

**检查约束应用情况：**
```bash
grep "Applied set_property RAM_STYLE = ULTRA.*weight_banks" vivado_synth_uram.log | wc -l
# 结果：408 ✓
```

**检查实际 URAM 映射：**
```bash
grep "weight_banks.*URAM" vivado_synth_uram.log
# 需要查看详细的 URAM Final Mapping Report
```

**检查未映射的 weight_banks：**
- 查看是否被映射为 BRAM
- 查看是否被优化掉
- 查看是否被映射为分布式 RAM

---

## 🔍 下一步调试方向

### 1. 查看详细的 URAM 映射报告
```bash
ssh hyyuan@10.12.133.23 "grep -A 500 'Ultra RAM: Final Mapping Report' /home/hyyuan/workspace/opt_accelerator/vivado_synth_uram.log"
```

**目的：**
- 确认哪些 weight_banks 被映射到 URAM
- 找出未映射的 weight_banks 的模块归属

### 2. 检查权重初始化
**文件：**
- `src/main/scala/QKVLinear/WeightMem.scala`
- `src/main/scala/OutLinear/WeightMem.scala`
- `src/main/scala/FFNUp/WeightMem.scala`
- `src/main/scala/FFNDown/WeightMem.scala`

**检查点：**
- `SyncReadMem` 是否有初始化逻辑
- `init_mode` 和 `init_wen` 信号是否正确连接

### 3. 优化约束文件
**当前约束：**
```tcl
set_property RAM_STYLE ULTRA [get_cells -hierarchical -filter {REF_NAME =~ "*LoadWeight/weight_mem/weight_banks*"}]
```

**可能需要改为更精确的路径：**
```tcl
# 分别为每个模块指定
set_property RAM_STYLE ULTRA [get_cells -hierarchical -filter {NAME =~ "qkvlinear/*/weight_mem/weight_banks*"}]
set_property RAM_STYLE ULTRA [get_cells -hierarchical -filter {NAME =~ "outlinear/*/weight_mem/weight_banks*"}]
set_property RAM_STYLE ULTRA [get_cells -hierarchical -filter {NAME =~ "ffnup/*/weight_mem/weight_banks*"}]
set_property RAM_STYLE ULTRA [get_cells -hierarchical -filter {NAME =~ "ffndown/*/weight_mem/weight_banks*"}]
```

### 4. 添加综合指令
在 TCL 脚本中添加：
```tcl
# 防止存储器优化
set_property KEEP_HIERARCHY yes [get_cells -hierarchical -filter {REF_NAME =~ "*WeightMem*"}]

# 强制保留所有 weight_banks
set_property DONT_TOUCH true [get_cells -hierarchical -filter {REF_NAME =~ "*weight_banks*"}]
```

---

## 📁 关键文件位置

### 本地文件
- **Chisel 源码：**`src/main/scala/`
- **生成的 Verilog：**`generated/Top.sv`
- **约束文件：**`constraints/main.xdc`
- **综合脚本：**`scripts/synthesis/run_fixed_synth.sh`
- **TCL 脚本：**`scripts/synthesis/vivado_synth_fixed.tcl`

### 服务器文件
- **工作目录：**`/home/hyyuan/workspace/opt_accelerator/`
- **Verilog：**`Top.sv`
- **约束：**`constraints_fixed.xdc`
- **日志：**`vivado_synth_uram.log`
- **报告：**`utilization_optimized.rpt`

### 服务器连接
```bash
ssh hyyuan@10.12.133.23
```

---

## 🔄 重新生成 Verilog 的步骤

```bash
cd /home/remote/workspace/Qwen2-Accelerator
sbt "runMain AttenTopGen"
# 生成的文件：generated/Top.sv
```

---

## 🚀 运行综合的步骤

```bash
# 上传文件到服务器
scp generated/Top.sv hyyuan@10.12.133.23:/home/hyyuan/workspace/opt_accelerator/
scp constraints/main.xdc hyyuan@10.12.133.23:/home/hyyuan/workspace/opt_accelerator/constraints_fixed.xdc
scp scripts/synthesis/vivado_synth_fixed.tcl hyyuan@10.12.133.23:/home/hyyuan/workspace/opt_accelerator/

# SSH 到服务器
ssh hyyuan@10.12.133.23

# 运行综合
cd /home/hyyuan/workspace/opt_accelerator
/home/EDA/Xilinx/Vivado/2021.1/bin/vivado -mode batch -source vivado_synth_fixed.tcl -log vivado_synth_uram.log &

# 实时查看进度
tail -f vivado_synth_uram.log
```

---

## 📈 性能目标

### 资源目标
- **LUT：**< 20% (< 236K)
- **FF：**< 15% (< 355K)
- **DSP：**< 30% (< 2,052)
- **URAM：**< 50% (< 480)
- **BRAM：**< 10% (< 216) - 释放给其他用途

### 时序目标
- **时钟周期：**10ns (100 MHz)
- **WNS (最差负时序余量)：**> 0
- **TNS (总负时序余量)：**= 0

### 功耗目标
- **静态功耗：**< 10W
- **动态功耗：**< 50W
- **总功耗：**< 60W

---

## 📝 已知 Bug 和修复

### 1. DataMen 死锁 (已修复)
- **问题：**r_cnt/w_cnt 逻辑错误导致死锁
- **修复：**替换为 buzy_cnt/full_cnt 模式

### 2. ResAdd dm2_ready 死锁 (已修复)
- **问题：**idle 状态下 dm2_ready=0
- **修复：**`dm2_ready := (is_reading || (is_idle && mem.io.r_ready)) && io.res_ready`

### 3. ResAdd 首拍数据丢失 (已修复)
- **问题：**idle→reading 转换时数据丢失
- **修复：**引入 active 信号

### 4. OutLinear data_out_st 时序不对 (已修复)
- **问题：**data_out_st 比 data_out_valid 早很多拍
- **修复：**在 StoreU 中添加 data_out_st 信号

---

## 🎯 当前任务优先级

1. **[高优先级]** 解决 weight_banks URAM 映射不完整问题
   - 查看详细的 URAM 映射报告
   - 找出未映射的 weight_banks
   - 优化约束或修改 Chisel 代码

2. **[中优先级]** 时序分析
   - 运行布局布线
   - 获取准确的时序报告
   - 优化关键路径

3. **[中优先级]** 功耗分析
   - 运行功耗估计
   - 优化高功耗模块

4. **[低优先级]** 功能验证
   - 运行 Verilator 仿真
   - 验证 Prefill 和 Decode 模式

---

## 💡 重要提示

1. **不要重复执行相同的操作** - 如果一个方法失败了，尝试不同的方法
2. **检查约束是否生效** - 使用 `grep "Applied"` 检查约束应用情况
3. **查看详细报告** - 不要只看总结，要看详细的映射报告
4. **保存中间结果** - 每次综合后保存日志和报告文件
5. **使用版本控制** - 重要修改前先提交 git

---

## 📞 联系信息

- **项目路径：**`/home/remote/workspace/Qwen2-Accelerator`
- **服务器：**`hyyuan@10.12.133.23`
- **FPGA：**Xilinx VU9P (xcvu9p-flga2104-2-i)
- **Vivado 版本：**2021.1

---

**文档更新时间：2026-03-24 22:30**
