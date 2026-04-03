#!/bin/bash
# Vivado 综合结果分析脚本
# 用于诊断资源使用量异常和优化问题

REPORT_DIR="$1"

if [ -z "$REPORT_DIR" ]; then
    echo "用法: $0 <vivado_reports_目录>"
    exit 1
fi

if [ ! -d "$REPORT_DIR" ]; then
    echo "错误: 目录不存在: $REPORT_DIR"
    exit 1
fi

echo "=========================================="
echo "Vivado 综合结果分析"
echo "=========================================="
echo ""

UTIL_REPORT=""
for candidate in "$REPORT_DIR/utilization_fixed.rpt" "$REPORT_DIR/utilization.rpt"; do
    if [ -f "$candidate" ]; then
        UTIL_REPORT="$candidate"
        break
    fi
done

TIMING_REPORT=""
for candidate in "$REPORT_DIR/timing_summary_fixed.rpt" "$REPORT_DIR/timing_summary.rpt"; do
    if [ -f "$candidate" ]; then
        TIMING_REPORT="$candidate"
        break
    fi
done

DRC_REPORT=""
for candidate in "$REPORT_DIR/drc_fixed.rpt" "$REPORT_DIR/drc.rpt"; do
    if [ -f "$candidate" ]; then
        DRC_REPORT="$candidate"
        break
    fi
done

SYNTH_LOG=""
for candidate in "$REPORT_DIR/vivado_synth_fixed.log" "$REPORT_DIR/vivado_synth.log" "vivado_synth_fixed.log" "vivado_synth.log"; do
    if [ -f "$candidate" ]; then
        SYNTH_LOG="$candidate"
        break
    fi
done

# 1. 资源利用率分析
echo "1. 资源利用率分析"
echo "----------------------------------------"
if [ -n "$UTIL_REPORT" ]; then
    echo "总体资源使用:"
    grep -A 30 "Slice Logic" "$UTIL_REPORT" | grep -E "Slice LUTs|Slice Registers|Block RAM Tile|RAMB36|RAMB18|URAM|DSPs" | head -10
    echo ""

    # 提取具体数值
    LUT=$(grep "Slice LUTs" "$UTIL_REPORT" | head -1 | awk '{print $4}')
    FF=$(grep "Slice Registers" "$UTIL_REPORT" | head -1 | awk '{print $4}')
    BRAM=$(grep "Block RAM Tile" "$UTIL_REPORT" | head -1 | awk '{print $4}')
    DSP=$(grep "DSPs" "$UTIL_REPORT" | head -1 | awk '{print $4}')

    echo "关键资源数值:"
    echo "  LUT:  $LUT"
    echo "  FF:   $FF"
    echo "  BRAM: $BRAM"
    echo "  DSP:  $DSP"
    echo ""

    # 判断是否异常
    if [ "$LUT" -lt 100000 ]; then
        echo "⚠️  警告: LUT 使用量异常低 ($LUT < 100000)"
        echo "   可能原因: 大量逻辑被优化掉"
    fi

    if [ "$DSP" -lt 1000 ]; then
        echo "⚠️  警告: DSP 使用量异常低 ($DSP < 1000)"
        echo "   可能原因: 乘法器未使用 DSP 或被优化掉"
    fi
else
    echo "✗ 未找到 utilization(.rpt|_fixed.rpt)"
fi
echo ""

# 2. 层次化资源分析
echo "2. 层次化资源分析"
echo "----------------------------------------"
if [ -f "$REPORT_DIR/utilization_hierarchical.rpt" ]; then
    echo "各模块资源使用 (Top 10):"
    grep -A 200 "Instance" "$REPORT_DIR/utilization_hierarchical.rpt" | grep -E "^\|" | grep -v "^|---" | head -15
    echo ""
else
    echo "✗ 未找到 utilization_hierarchical.rpt"
fi
echo ""

# 3. 时序分析
echo "3. 时序分析"
echo "----------------------------------------"
if [ -n "$TIMING_REPORT" ]; then
    echo "时序摘要:"
    grep -A 10 "Design Timing Summary" "$TIMING_REPORT" | grep -E "WNS|TNS|WHS|THS"
    echo ""

    WNS=$(grep "WNS(ns)" "$TIMING_REPORT" | awk '{print $2}')
    if [ ! -z "$WNS" ]; then
        if (( $(echo "$WNS < 0" | bc -l) )); then
            echo "⚠️  警告: 时序不满足 (WNS = $WNS ns)"
        else
            echo "✓ 时序满足 (WNS = $WNS ns)"
        fi
    fi
else
    echo "✗ 未找到 timing_summary(.rpt|_fixed.rpt)"
fi
echo ""

# 4. DRC 检查
echo "4. DRC 检查"
echo "----------------------------------------"
if [ -n "$DRC_REPORT" ]; then
    DRC_COUNT=$(grep -c "^Warning" "$DRC_REPORT" || echo "0")
    echo "DRC 警告数量: $DRC_COUNT"
    if [ "$DRC_COUNT" -gt 0 ]; then
        echo "前 10 个警告:"
        grep "^Warning" "$DRC_REPORT" | head -10
    fi
else
    echo "✗ 未找到 drc(.rpt|_fixed.rpt)"
fi
echo ""

# 5. 综合日志分析
echo "5. 综合日志分析"
echo "----------------------------------------"
if [ -n "$SYNTH_LOG" ]; then
    echo "检查优化警告..."

    # 检查未使用的端口
    UNUSED_PORTS=$(grep -i "unused" "$SYNTH_LOG" | grep -i "port" | wc -l)
    echo "未使用端口数量: $UNUSED_PORTS"
    if [ "$UNUSED_PORTS" -gt 0 ]; then
        echo "⚠️  发现未使用的端口:"
        grep -i "unused" "$SYNTH_LOG" | grep -i "port" | head -5
        echo ""
    fi

    # 检查被优化掉的逻辑
    OPTIMIZED=$(grep -i "optimized" "$SYNTH_LOG" | grep -i "away\|removed" | wc -l)
    echo "被优化掉的逻辑数量: $OPTIMIZED"
    if [ "$OPTIMIZED" -gt 10 ]; then
        echo "⚠️  大量逻辑被优化掉:"
        grep -i "optimized" "$SYNTH_LOG" | grep -i "away\|removed" | head -5
        echo ""
    fi

    # 检查常量传播
    CONST_PROP=$(grep -i "constant" "$SYNTH_LOG" | grep -i "propagat" | wc -l)
    echo "常量传播数量: $CONST_PROP"
    if [ "$CONST_PROP" -gt 10 ]; then
        echo "⚠️  发现大量常量传播:"
        grep -i "constant" "$SYNTH_LOG" | grep -i "propagat" | head -5
        echo ""
    fi

    # 检查错误
    ERROR_COUNT=$(grep -c "^ERROR" "$SYNTH_LOG" || echo "0")
    echo "错误数量: $ERROR_COUNT"
    if [ "$ERROR_COUNT" -gt 0 ]; then
        echo "✗ 发现错误:"
        grep "^ERROR" "$SYNTH_LOG" | head -5
        echo ""
    fi
else
    echo "✗ 未找到综合日志"
fi
echo ""

# 6. 存储器使用分析
echo "6. 存储器使用分析"
echo "----------------------------------------"
if [ -n "$UTIL_REPORT" ]; then
    echo "BRAM 使用:"
    grep -A 5 "RAMB36" "$UTIL_REPORT" | head -6
    echo ""
    echo "URAM 使用:"
    grep -A 5 "URAM" "$UTIL_REPORT" | head -6
    echo ""
fi

# 7. DSP 使用分析
echo "7. DSP 使用分析"
echo "----------------------------------------"
if [ -n "$UTIL_REPORT" ]; then
    echo "DSP48E2 使用:"
    grep -A 5 "DSPs" "$UTIL_REPORT" | head -6
    echo ""
fi

# 8. 生成诊断报告
echo "=========================================="
echo "诊断总结"
echo "=========================================="
echo ""

ISSUES=0

if [ "$LUT" -lt 100000 ]; then
    echo "❌ 问题 $((++ISSUES)): LUT 使用量异常低"
    echo "   建议: 检查顶层端口连接，使用 DONT_TOUCH 约束"
fi

if [ "$DSP" -lt 1000 ]; then
    echo "❌ 问题 $((++ISSUES)): DSP 使用量异常低"
    echo "   建议: 添加 USE_DSP48 约束，检查乘法器是否被优化"
fi

if [ "$UNUSED_PORTS" -gt 0 ]; then
    echo "❌ 问题 $((++ISSUES)): 存在未使用的端口"
    echo "   建议: 检查顶层连接，确保所有端口都被使用"
fi

if [ "$OPTIMIZED" -gt 10 ]; then
    echo "❌ 问题 $((++ISSUES)): 大量逻辑被优化掉"
    echo "   建议: 使用 DONT_TOUCH 约束，检查输入是否为常量"
fi

if [ "$ISSUES" -eq 0 ]; then
    echo "✓ 未发现明显问题"
else
    echo ""
    echo "发现 $ISSUES 个问题，请查看上述建议"
fi

echo ""
echo "详细报告已保存在: $REPORT_DIR"
