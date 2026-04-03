# Vivado XDC 约束完全指南

## 当前约束文件详解

### 1. 时钟约束

#### 1.1 创建时钟
```tcl
create_clock -period 10.000 -name clk [get_ports clock]
```

**含义：**
- 在名为 `clock` 的端口上创建一个时钟
- 周期为 10.000 ns（频率 = 1000/10 = 100 MHz）
- 给这个时钟命名为 `clk`

**详细解释：**
- `-period 10.000`：时钟周期，单位是纳秒（ns）
- `-name clk`：时钟的名称，用于后续约束引用
- `[get_ports clock]`：指定时钟源端口

**为什么需要：**
- 时钟约束是所有时序约束的基础
- Vivado 需要知道时钟频率才能进行时序分析
- 没有时钟约束，Vivado 无法验证设计是否满足时序要求

**常见变体：**
```tcl
# 多个时钟
create_clock -period 5.0 -name clk_fast [get_ports clk_in]
create_clock -period 20.0 -name clk_slow [get_ports clk_slow_in]

# 虚拟时钟（用于 I/O 时序约束）
create_clock -period 10.0 -name virt_clk

# 生成时钟（从 PLL/MMCM 输出）
create_generated_clock -name clk_div2 -source [get_pins pll/CLKIN] \
    -divide_by 2 [get_pins pll/CLKOUT0]
```

---

#### 1.2 时钟不确定性
```tcl
set_clock_uncertainty 0.5 [get_clocks clk]
```

**含义：**
- 为时钟 `clk` 设置 0.5 ns 的不确定性
- 这是时钟抖动（jitter）和偏斜（skew）的总和

**详细解释：**
- 时钟不确定性会减少可用的时序裕量
- 例如：10 ns 周期 - 0.5 ns 不确定性 = 9.5 ns 有效周期
- 包括：
  - **时钟抖动（Jitter）**：时钟边沿的随机变化
  - **时钟偏斜（Skew）**：不同寄存器接收时钟的时间差

**如何选择值：**
- 板载时钟：0.3-0.5 ns（典型值）
- PLL/MMCM 输出：0.1-0.3 ns
- 高速时钟（> 200 MHz）：需要更精确的分析

**示例：**
```tcl
# 分别设置 setup 和 hold 不确定性
set_clock_uncertainty -setup 0.5 [get_clocks clk]
set_clock_uncertainty -hold 0.2 [get_clocks clk]

# 跨时钟域不确定性
set_clock_uncertainty -from [get_clocks clk1] -to [get_clocks clk2] 1.0
```

---

### 2. 输入/输出时序约束

#### 2.1 输入延迟
```tcl
set_input_delay -clock clk 2.0 [get_ports -filter {NAME !~ "*clock*" && NAME !~ "*reset*"}]
```

**含义：**
- 所有输入端口（除了时钟和复位）相对于时钟 `clk` 有 2.0 ns 的延迟
- 这表示外部数据在时钟边沿后 2.0 ns 到达 FPGA

**详细解释：**
```
外部器件                FPGA
  ┌─────┐              ┌─────┐
  │     │  PCB走线     │     │
  │ REG ├──────────────►│ REG │
  │     │   2.0 ns     │     │
  └─────┘              └─────┘
    ↑                    ↑
   CLK                  CLK
```

- `set_input_delay 2.0` 表示数据在时钟边沿后 2.0 ns 到达
- Vivado 会确保 FPGA 内部有足够的时序裕量来捕获这个数据

**为什么排除时钟和复位：**
- 时钟端口已经有 `create_clock` 约束
- 复位通常是异步的，不需要时序约束
- 如果对时钟端口设置 input_delay 会导致冲突

**如何计算 input_delay：**
```
input_delay = Tco_external + Tpcb + Tsetup_margin

其中：
- Tco_external: 外部器件的时钟到输出延迟
- Tpcb: PCB 走线延迟
- Tsetup_margin: 安全裕量
```

**示例：**
```tcl
# 最大和最小延迟（考虑 PVT 变化）
set_input_delay -clock clk -max 3.0 [get_ports data_in]
set_input_delay -clock clk -min 1.0 [get_ports data_in]

# 相对于时钟下降沿
set_input_delay -clock clk -clock_fall 2.0 [get_ports data_in]

# 添加到现有约束
set_input_delay -clock clk -add_delay 0.5 [get_ports data_in]
```

---

#### 2.2 输出延迟
```tcl
set_output_delay -clock clk 2.0 [get_ports -filter {NAME !~ "*clock*" && NAME !~ "*reset*"}]
```

**含义：**
- 所有输出端口（除了时钟和复位）相对于时钟 `clk` 有 2.0 ns 的延迟要求
- 这表示外部器件需要在时钟边沿后 2.0 ns 内接收到稳定数据

**详细解释：**
```
FPGA                   外部器件
  ┌─────┐              ┌─────┐
  │     │  PCB走线     │     │
  │ REG ├──────────────►│ REG │
  │     │   Tpcb       │     │
  └─────┘              └─────┘
    ↑                    ↑
   CLK                  CLK
         ←─ 2.0 ns ─→
```

- `set_output_delay 2.0` 表示外部器件需要 2.0 ns 的 setup 时间
- Vivado 会确保 FPGA 输出在时钟边沿前足够早地稳定

**如何计算 output_delay：**
```
output_delay = Tpcb + Tsetup_external + Tsetup_margin

其中：
- Tpcb: PCB 走线延迟
- Tsetup_external: 外部器件的 setup 时间
- Tsetup_margin: 安全裕量
```

**示例：**
```tcl
# 最大和最小延迟
set_output_delay -clock clk -max 3.0 [get_ports data_out]
set_output_delay -clock clk -min 0.5 [get_ports data_out]

# 相对于时钟下降沿
set_output_delay -clock clk -clock_fall 2.0 [get_ports data_out]
```

---

### 3. 资源映射约束

#### 3.1 强制使用 DSP48E2
```tcl
set_property USE_DSP48 yes [get_cells -hierarchical -filter {PRIMITIVE_TYPE =~ BMULT.*}]
set_property USE_DSP48 yes [get_cells -hierarchical -filter {PRIMITIVE_TYPE =~ MULT.*}]
```

**含义：**
- 强制所有乘法器使用 DSP48E2 硬核
- 而不是使用 LUT 实现乘法

**详细解释：**
- `USE_DSP48 yes`：强制使用 DSP
- `USE_DSP48 no`：禁止使用 DSP，用 LUT 实现
- `USE_DSP48 auto`：让 Vivado 自动决定（默认）

**为什么需要：**
- DSP48E2 是专用的乘法/累加硬核，性能高、功耗低
- 默认情况下，小位宽乘法可能用 LUT 实现
- 强制使用 DSP 可以节省 LUT 资源

**DSP48E2 的能力：**
- 27x18 位乘法器
- 48 位累加器
- 支持流水线（最多 4 级）
- 支持 MACC（乘累加）操作

**示例：**
```tcl
# 针对特定模块
set_property USE_DSP48 yes [get_cells -hierarchical -filter {NAME =~ "*multiplier*"}]

# 全局设置（影响所有乘法）
set_property USE_DSP yes [current_design]

# 禁用 DSP（用于调试）
set_property USE_DSP48 no [get_cells mult_inst]
```

**权衡：**
- ✓ 优点：高性能、低功耗、节省 LUT
- ✗ 缺点：DSP 资源有限、可能增加延迟（如果需要流水线）

---

#### 3.2 存储器资源映射
```tcl
set_property RAM_STYLE ULTRA [get_cells -hierarchical -filter {NAME =~ "*KCache*mem_ext*"}]
set_property RAM_STYLE ULTRA [get_cells -hierarchical -filter {NAME =~ "*VCache*mem_ext*"}]
```

**含义：**
- 强制 KCache 和 VCache 使用 URAM（UltraRAM）
- 而不是使用 BRAM 或 LUTRAM

**RAM_STYLE 选项：**

| 选项 | 含义 | 适用场景 |
|------|------|----------|
| `BLOCK` | 使用 BRAM (RAMB36E2) | 中等容量（512-36K 深度） |
| `ULTRA` | 使用 URAM (URAM288) | 大容量（4K 深度，固定 72 位宽） |
| `DISTRIBUTED` | 使用 LUTRAM | 小容量（< 512 深度）、需要异步读 |
| `REGISTERS` | 使用寄存器 | 极小容量（< 64 深度）、需要最高性能 |
| `AUTO` | 让 Vivado 自动选择 | 默认值 |

**URAM vs BRAM 对比：**

| 特性 | URAM288 | RAMB36E2 |
|------|---------|----------|
| 容量 | 288 Kb | 36 Kb |
| 配置 | 固定 4K x 72 | 灵活（512x72 到 32Kx1） |
| 端口 | 单端口或简单双端口 | 真双端口 |
| 级联 | 支持（CASCADE_ORDER） | 不支持 |
| 数量（VU9P） | 960 | 2160 |

**示例：**
```tcl
# 强制使用 BRAM
set_property RAM_STYLE BLOCK [get_cells -hierarchical -filter {NAME =~ "*buffer*"}]

# 强制使用分布式 RAM
set_property RAM_STYLE DISTRIBUTED [get_cells -hierarchical -filter {NAME =~ "*fifo*"}]

# 禁止使用 BRAM（强制用 LUTRAM）
set_property RAM_STYLE DISTRIBUTED [get_cells small_mem]

# 设置 URAM 级联高度
set_property CASCADE_HEIGHT 2 [get_cells -hierarchical -filter {REF_NAME == URAM288}]
```

**注意事项：**
- URAM 只支持 4K 深度，位宽最大 72 位
- 如果位宽 > 72 位，需要手动拆分为多个 URAM 并联
- 如果深度 > 4K，需要使用级联（CASCADE）

---

### 4. 优化控制约束

#### 4.1 保留信号
```tcl
set_property KEEP true [get_nets -hierarchical -filter {NAME =~ "*io_res*"}]
set_property KEEP true [get_nets -hierarchical -filter {NAME =~ "*io_res_valid*"}]
set_property KEEP true [get_nets -hierarchical -filter {NAME =~ "*io_res_st*"}]
```

**含义：**
- 防止这些信号被优化掉
- 确保这些信号在综合和实现后仍然存在

**为什么需要：**
- 如果输出端口没有被使用（例如在 out_of_context 模式下），Vivado 可能会优化掉整个逻辑锥
- `KEEP` 属性告诉 Vivado："这个信号很重要，不要删除它"

**KEEP vs DONT_TOUCH：**

| 属性 | 作用对象 | 效果 | 使用场景 |
|------|----------|------|----------|
| `KEEP` | 信号（net） | 保留信号，但允许优化驱动逻辑 | 防止信号被优化掉 |
| `DONT_TOUCH` | 单元格（cell） | 保留单元格，禁止任何优化 | 调试、保护关键逻辑 |
| `KEEP_HIERARCHY` | 模块（module） | 保留层次结构，禁止跨层次优化 | 保持设计层次 |

**示例：**
```tcl
# 保留调试信号
set_property KEEP true [get_nets debug_*]

# 保留所有输出端口的驱动信号
set_property KEEP true [get_nets -of_objects [get_ports -filter {DIRECTION == OUT}]]

# 保护关键单元格（更激进）
set_property DONT_TOUCH true [get_cells critical_logic]

# 保持模块层次
set_property KEEP_HIERARCHY yes [get_cells top/subsystem]
```

**注意：**
- 过度使用 `KEEP` 会阻止优化，增加资源使用
- 只对关键信号使用 `KEEP`
- 对于调试，考虑使用 ILA（Integrated Logic Analyzer）而不是 `KEEP`

---

## 5. 高级约束技巧

### 5.1 对象选择（get_* 命令）

```tcl
# 获取端口
get_ports clock                          # 单个端口
get_ports data_*                         # 通配符
get_ports -filter {DIRECTION == IN}      # 过滤器

# 获取时钟
get_clocks clk                           # 单个时钟
get_clocks *                             # 所有时钟

# 获取单元格
get_cells top/module/reg                 # 层次路径
get_cells -hierarchical *reg*            # 递归搜索
get_cells -filter {REF_NAME == FDRE}     # 按类型过滤

# 获取信号
get_nets data_valid                      # 单个信号
get_nets -hierarchical *valid*           # 递归搜索
get_nets -of_objects [get_pins cell/Q]   # 从引脚获取

# 获取引脚
get_pins cell/D                          # 单元格引脚
get_pins -hierarchical */CLK             # 所有时钟引脚
```

### 5.2 过滤器语法

```tcl
# 逻辑运算符
-filter {NAME =~ "*mult*" && PRIMITIVE_TYPE =~ DSP.*}
-filter {NAME !~ "*debug*" || IS_SEQUENTIAL}

# 常用属性
DIRECTION        # IN, OUT, INOUT
PRIMITIVE_TYPE   # DSP.*, BMEM.*, CLB.*
REF_NAME         # FDRE, RAMB36E2, DSP48E2
IS_SEQUENTIAL    # true/false
IS_COMBINATIONAL # true/false
```

### 5.3 时序例外

```tcl
# 伪路径（不需要时序分析）
set_false_path -from [get_clocks clk1] -to [get_clocks clk2]
set_false_path -through [get_pins mux/S]

# 多周期路径（允许多个时钟周期）
set_multicycle_path 2 -from [get_cells src_reg] -to [get_cells dst_reg]

# 最大延迟约束
set_max_delay 5.0 -from [get_cells src] -to [get_cells dst]

# 最小延迟约束
set_min_delay 1.0 -from [get_cells src] -to [get_cells dst]
```

---

## 6. 约束优先级和冲突处理

### 优先级顺序（从高到低）

1. **set_max_delay / set_min_delay** - 显式延迟约束
2. **set_multicycle_path** - 多周期路径
3. **set_false_path** - 伪路径
4. **时钟约束** - create_clock, set_input_delay, set_output_delay
5. **默认约束** - Vivado 自动生成

### 冲突处理

```tcl
# 错误示例：冲突的约束
create_clock -period 10.0 [get_ports clk]
create_clock -period 5.0 [get_ports clk]  # 错误！同一端口两个时钟

# 正确示例：使用 -add 添加多个时钟
create_clock -period 10.0 -name clk1 [get_ports clk]
create_clock -period 5.0 -name clk2 -add [get_ports clk]
```

---

## 7. 约束验证和调试

### 7.1 检查约束

```tcl
# 检查时序约束
report_timing_summary
report_clock_interaction

# 检查未约束的路径
report_timing -unconstrained

# 检查约束覆盖率
check_timing -verbose
```

### 7.2 常见问题

**问题 1：时序违例（Timing Violation）**
```
WNS (Worst Negative Slack) < 0
```
解决方案：
- 降低时钟频率（增加 period）
- 添加流水线寄存器
- 优化关键路径逻辑
- 使用更快的速度等级

**问题 2：未约束的路径**
```
WARNING: [Timing 38-313] There are no user specified timing constraints.
```
解决方案：
- 添加 create_clock 约束
- 添加 input_delay / output_delay 约束
- 检查时钟是否正确传播

**问题 3：过约束（Over-constrained）**
```
ERROR: [Timing 38-282] The design failed to meet the timing requirements.
```
解决方案：
- 检查约束是否过于严格
- 检查 clock_uncertainty 是否过大
- 检查 input_delay / output_delay 是否合理

---

## 8. 最佳实践

### ✓ 推荐做法

1. **总是约束所有时钟**
   ```tcl
   create_clock -period 10.0 [get_ports clk]
   ```

2. **为 I/O 设置合理的延迟**
   ```tcl
   set_input_delay -clock clk 2.0 [get_ports data_in]
   set_output_delay -clock clk 2.0 [get_ports data_out]
   ```

3. **使用过滤器排除特殊端口**
   ```tcl
   set_input_delay -clock clk 2.0 [get_ports -filter {NAME !~ "*clk*"}]
   ```

4. **为跨时钟域路径设置伪路径**
   ```tcl
   set_false_path -from [get_clocks clk1] -to [get_clocks clk2]
   ```

5. **使用层次化约束**
   ```tcl
   # 在子模块级别设置约束
   set_property RAM_STYLE BLOCK [get_cells subsystem/mem*]
   ```

### ✗ 避免做法

1. **不要过度使用 DONT_TOUCH**
   ```tcl
   # 错误：阻止所有优化
   set_property DONT_TOUCH true [get_cells -hierarchical]
   ```

2. **不要对时钟端口设置 input_delay**
   ```tcl
   # 错误：与 create_clock 冲突
   set_input_delay -clock clk 2.0 [get_ports clk]
   ```

3. **不要使用过大的 clock_uncertainty**
   ```tcl
   # 错误：过于保守
   set_clock_uncertainty 5.0 [get_clocks clk]  # 对于 10ns 周期太大了
   ```

4. **不要忘记约束生成时钟**
   ```tcl
   # 错误：只约束了输入时钟，忘记了 PLL 输出
   create_clock -period 10.0 [get_ports clk_in]
   # 正确：也要约束生成时钟
   create_generated_clock -name clk_pll -source [get_pins pll/CLKIN] \
       -multiply_by 2 [get_pins pll/CLKOUT]
   ```

---

## 9. 针对 Qwen2 加速器的约束策略

### 当前策略（constraints_optimized.xdc）

**优点：**
- ✓ 简洁明了，只约束必要的内容
- ✓ 允许 Vivado 进行优化（flatten_hierarchy rebuilt）
- ✓ 强制关键资源使用（DSP、URAM）
- ✓ 保护关键输出信号

**改进建议：**

1. **添加跨时钟域约束**（如果有多个时钟域）
   ```tcl
   set_false_path -from [get_clocks clk1] -to [get_clocks clk2]
   ```

2. **为存储器添加更精细的约束**
   ```tcl
   # 针对不同大小的存储器使用不同策略
   set_property RAM_STYLE ULTRA [get_cells -hierarchical -filter {NAME =~ "*Cache*" && DEPTH > 2048}]
   set_property RAM_STYLE BLOCK [get_cells -hierarchical -filter {NAME =~ "*buffer*" && DEPTH > 512}]
   set_property RAM_STYLE DISTRIBUTED [get_cells -hierarchical -filter {NAME =~ "*fifo*" && DEPTH < 512}]
   ```

3. **添加流水线约束**（如果需要高频率）
   ```tcl
   # 允许寄存器重定时
   set_property ALLOW_COMBINATORIAL_LOOPS false [current_design]
   ```

---

## 10. 参考资源

- **Xilinx UG903** - Vivado Design Suite User Guide: Using Constraints
- **Xilinx UG949** - UltraFast Design Methodology Guide
- **Xilinx UG901** - Vivado Design Suite User Guide: Synthesis
- **Xilinx UG906** - Vivado Design Suite User Guide: Design Analysis and Closure Techniques

---

## 总结

约束文件是 FPGA 设计的关键部分，它告诉 Vivado：
1. **时序要求**：设计需要运行在什么频率
2. **资源映射**：使用什么硬件资源（DSP、BRAM、URAM）
3. **优化策略**：哪些部分可以优化，哪些需要保护

**关键原则：**
- 约束要准确反映实际硬件环境
- 不要过度约束（阻止优化）
- 不要欠约束（导致时序违例）
- 使用层次化和模块化的约束策略
