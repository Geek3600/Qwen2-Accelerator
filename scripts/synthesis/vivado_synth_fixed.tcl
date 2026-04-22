# ============================================================
# Vivado 综合脚本 - 修复版本
# 目标：防止逻辑优化，正确映射 DSP 和 URAM
# ============================================================

# 设置工作目录
set work_dir [pwd]
puts "工作目录: $work_dir"

# 创建工程
create_project -in_memory -part xcvu9p-flga2104-2-i

# ============================================================
# 1. 读取设计文件
# ============================================================
puts "\n=========================================="
puts "读取 Verilog 源文件..."
puts "=========================================="

read_verilog Top.sv

# ============================================================
# 2. 读取约束文件
# ============================================================
puts "\n=========================================="
puts "读取约束文件..."
puts "=========================================="

read_xdc constraints_fixed.xdc

# ============================================================
# 3. 综合设置 - 禁用激进优化
# ============================================================
puts "\n=========================================="
puts "配置综合选项..."
puts "=========================================="

# 基本综合选项
set_property top Top [current_fileset]

# 关键：禁用激进优化
# 注释掉可能导致错误的 rodinMoreOptions 设置
# set_param synth.elaboration.rodinMoreOptions "set rt::set_parameter max_loop_limit 100000"
# set_param synth.elaboration.rodinMoreOptions "set rt::set_parameter elaborateRtl 1"

# 注意：在内存模式下，综合选项直接传递给 synth_design 命令（见下方）

# ============================================================
# 4. 运行综合
# ============================================================
puts "\n=========================================="
puts "开始综合..."
puts "=========================================="

synth_design \
    -top Top \
    -part xcvu9p-flga2104-2-i \
    -mode out_of_context \
    -flatten_hierarchy rebuilt \
    -keep_equivalent_registers \
    -resource_sharing off \
    -no_lc \
    -shreg_min_size 5 \
    -directive RuntimeOptimized

puts "\n综合完成！"

# ============================================================
# 5. 应用约束（再次确认）
# ============================================================
# 注意：所有约束已在 constraints_fixed.xdc 中定义，无需重复应用
# ============================================================
# 6. 生成报告
# ============================================================
puts "\n=========================================="
puts "生成报告..."
puts "=========================================="

# 资源利用率报告（详细）
report_utilization -file utilization_fixed.rpt -hierarchical -hierarchical_depth 5

# DSP 使用报告
set dsp_cells [get_cells -quiet -hierarchical -filter {REF_NAME =~ "DSP*"}]
if {[llength $dsp_cells] > 0} {
    report_utilization -cells $dsp_cells -file dsp_usage.rpt
} else {
    puts "WARNING: No DSP primitive cells matched; skip dsp_usage.rpt"
}

# RAM 使用报告
report_ram_utilization -file ram_usage.rpt

# 权重存储专项报告
set weight_bank_wrappers [get_cells -quiet -hierarchical -filter {REF_NAME == weight_banks_4096x72}]
if {[llength $weight_bank_wrappers] > 0} {
    report_utilization -cells $weight_bank_wrappers -file weight_bank_usage.rpt
} else {
    puts "WARNING: No weight bank wrappers matched; skip weight_bank_usage.rpt"
}

# 时序摘要
report_timing_summary -file timing_summary_fixed.rpt -max_paths 10

# DRC 检查
report_drc -file drc_fixed.rpt

# 功耗估算
report_power -file power_fixed.rpt

# 层次化资源报告
report_utilization -hierarchical -hierarchical_depth 3 -file utilization_hierarchical.rpt

# ============================================================
# 7. 保存结果
# ============================================================
puts "\n=========================================="
puts "保存结果..."
puts "=========================================="

write_checkpoint -force post_synth_fixed.dcp
write_verilog -force post_synth_fixed.v

# ============================================================
# 8. 显示摘要
# ============================================================
puts "\n=========================================="
puts "综合结果摘要"
puts "=========================================="

# 获取资源使用量
set lut_used [get_property USED [get_cells -hierarchical -filter {PRIMITIVE_TYPE =~ LUT.*}]]
set ff_used [get_property USED [get_cells -hierarchical -filter {PRIMITIVE_TYPE =~ REGISTER.*}]]
set dsp_used [llength $dsp_cells]
set bram_used [llength [get_cells -quiet -hierarchical -filter {PRIMITIVE_TYPE =~ BMEM.bram.*}]]
set uram_used [llength [get_cells -quiet -hierarchical -filter {PRIMITIVE_TYPE =~ BMEM.uram.*}]]
set weight_bank_bram [get_cells -quiet -hierarchical -filter {NAME =~ "*weight_banks_*_ext*" && PRIMITIVE_TYPE =~ BMEM.bram.*}]
set weight_bank_uram [get_cells -quiet -hierarchical -filter {NAME =~ "*weight_banks_*_ext*" && PRIMITIVE_TYPE =~ BMEM.uram.*}]

puts "LUT: $lut_used"
puts "FF: $ff_used"
puts "DSP: $dsp_used"
puts "BRAM: $bram_used"
puts "URAM: $uram_used"
puts "Weight bank wrappers: [llength $weight_bank_wrappers]"
puts "Weight bank BRAM primitives: [llength $weight_bank_bram]"
puts "Weight bank URAM primitives: [llength $weight_bank_uram]"

puts "\n所有报告已保存！"
puts "=========================================="
