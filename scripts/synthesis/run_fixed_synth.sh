#!/bin/bash
# 运行修复后的综合

SERVER="hyyuan@10.12.133.23"
REMOTE_DIR="/home/hyyuan/workspace/opt_accelerator"
LOCAL_DIR="/home/remote/workspace/Qwen2-Accelerator"

# 切换到项目根目录
cd "$LOCAL_DIR"

echo "=========================================="
echo "Qwen2 加速器 - 修复综合"
echo "=========================================="

# 0. 重新生成最新 Verilog
echo ""
echo "[0/5] 生成最新 Top.sv..."
sbt "runMain AttenTopGen"

# 1. 上传修复后的文件
echo ""
echo "[1/5] 上传修复后的 Verilog、约束和脚本..."
scp generated/Top.sv $SERVER:$REMOTE_DIR/Top.sv
scp constraints/main.xdc $SERVER:$REMOTE_DIR/constraints_fixed.xdc
scp scripts/synthesis/vivado_synth_fixed.tcl $SERVER:$REMOTE_DIR/

# 2. 在服务器上运行综合
echo ""
echo "[2/5] 在服务器上运行 Vivado 综合..."
echo "预计时间: 30-60 分钟"
echo ""

ssh $SERVER << 'REMOTE_SCRIPT'
cd /home/hyyuan/workspace/opt_accelerator

# 查找 Vivado
VIVADO_PATH=$(find /opt /tools /usr/local /home/EDA -name "vivado" -type f 2>/dev/null | grep -E "bin/vivado$" | head -1)

if [ -z "$VIVADO_PATH" ]; then
    echo "错误：找不到 Vivado！"
    exit 1
fi

echo "找到 Vivado: $VIVADO_PATH"

# 运行综合
echo ""
echo "开始综合..."
$VIVADO_PATH -mode batch -source vivado_synth_fixed.tcl -log vivado_synth_fixed.log

echo ""
echo "综合完成！"
REMOTE_SCRIPT

# 3. 下载结果
echo ""
echo "[3/5] 下载综合结果..."
mkdir -p vivado_results_fixed
scp $SERVER:$REMOTE_DIR/*.rpt vivado_results_fixed/
scp $SERVER:$REMOTE_DIR/vivado_synth_fixed.log vivado_results_fixed/

# 4. 分析结果
echo ""
echo "[4/5] 分析资源使用量..."
echo ""

if [ -f vivado_results_fixed/utilization_fixed.rpt ]; then
    echo "=========================================="
    echo "资源利用率摘要"
    echo "=========================================="
    grep -A 20 "Slice Logic" vivado_results_fixed/utilization_fixed.rpt | head -25
    
    echo ""
    echo "=========================================="
    echo "DSP 使用量"
    echo "=========================================="
    grep -A 5 "DSP" vivado_results_fixed/utilization_fixed.rpt | head -10
    
    echo ""
    echo "=========================================="
    echo "存储器使用量"
    echo "=========================================="
    grep -A 10 "Memory" vivado_results_fixed/utilization_fixed.rpt | head -15
fi

# 5. 对比修复前后
echo ""
echo "[5/5] 对比修复前后..."
echo ""
echo "=========================================="
echo "修复前 vs 修复后"
echo "=========================================="

if [ -f vivado_results/utilization.rpt ] && [ -f vivado_results_fixed/utilization_fixed.rpt ]; then
    echo "LUT:"
    echo "  修复前: $(grep "Slice LUTs" vivado_results/utilization.rpt | head -1 | awk '{print $4, $5, $6}')"
    echo "  修复后: $(grep "Slice LUTs" vivado_results_fixed/utilization_fixed.rpt | head -1 | awk '{print $4, $5, $6}')"
    
    echo ""
    echo "DSP:"
    echo "  修复前: $(grep "DSPs" vivado_results/utilization.rpt | head -1 | awk '{print $3, $4, $5}')"
    echo "  修复后: $(grep "DSPs" vivado_results_fixed/utilization_fixed.rpt | head -1 | awk '{print $3, $4, $5}')"
    
    echo ""
    echo "URAM:"
    echo "  修复前: $(grep "URAM" vivado_results/utilization.rpt | head -1 | awk '{print $3, $4, $5}')"
    echo "  修复后: $(grep "URAM" vivado_results_fixed/utilization_fixed.rpt | head -1 | awk '{print $3, $4, $5}')"
fi

echo ""
echo "=========================================="
echo "所有结果已保存到: vivado_results_fixed/"
echo "=========================================="
