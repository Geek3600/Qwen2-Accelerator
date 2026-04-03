#!/usr/bin/env python3
# Verilog 后处理脚本
# 用于在生成的 Verilog 中添加 Xilinx 属性

import re
import sys

def add_xilinx_attributes(verilog_file, output_file):
    """
    在 Verilog 文件中添加 Xilinx 综合属性
    """

    with open(verilog_file, 'r') as f:
        content = f.read()

    print(f"处理文件: {verilog_file}")
    print(f"文件大小: {len(content)} 字节")
    print("")

    modifications = []

    # 1. 为大容量存储器添加 URAM 属性
    # 查找 reg [WIDTH:0] mem_name [0:DEPTH];
    uram_pattern = r'(reg\s+\[[\d:]+\]\s+(\w*mem\w*)\s+\[[\d:]+\];)'

    def add_uram_attr(match):
        original = match.group(0)
        mem_name = match.group(2)

        # 判断是否需要 URAM（根据命名规则）
        if any(x in mem_name.lower() for x in ['qkvlinear', 'outlinear', 'ffnup', 'ffndown', 'wmem']):
            modifications.append(f"添加 URAM 属性: {mem_name}")
            return f'(* ram_style = "ultra" *) {original}'
        # 判断是否需要 BRAM
        elif any(x in mem_name.lower() for x in ['datamem', 'vcache', 'resadd']):
            modifications.append(f"添加 BRAM 属性: {mem_name}")
            return f'(* ram_style = "block" *) {original}'
        else:
            return original

    content = re.sub(uram_pattern, add_uram_attr, content)

    # 2. 为乘法器添加 DSP 属性
    # 查找 wire [WIDTH:0] product = a * b;
    dsp_pattern = r'(wire\s+\[[\d:]+\]\s+(\w*)\s*=\s*\w+\s*\*\s*\w+;)'

    def add_dsp_attr(match):
        original = match.group(0)
        signal_name = match.group(2)

        if 'mul' in signal_name.lower() or 'prod' in signal_name.lower():
            modifications.append(f"添加 DSP 属性: {signal_name}")
            return f'(* use_dsp = "yes" *) {original}'
        else:
            return original

    content = re.sub(dsp_pattern, add_dsp_attr, content)

    # 3. 为关键模块添加 DONT_TOUCH 属性
    # 查找 module 定义
    module_pattern = r'(module\s+(\w+)\s*\()'

    def add_dont_touch(match):
        original = match.group(0)
        module_name = match.group(2)

        # 为关键模块添加 DONT_TOUCH
        if any(x in module_name for x in ['LayerNorm', 'QKVLinear', 'Atten', 'OutLinear',
                                            'ResAdd', 'FFNUp', 'GELU', 'FFNDown']):
            modifications.append(f"添加 DONT_TOUCH 属性: {module_name}")
            return f'(* dont_touch = "yes" *)\n{original}'
        else:
            return original

    content = re.sub(module_pattern, add_dont_touch, content)

    # 4. 为部分和累加器添加 KEEP 属性
    # 查找 reg [WIDTH:0] psums...
    keep_pattern = r'(reg\s+\[[\d:]+\]\s+(psums\w*)\s+\[[\d:]+\];)'

    def add_keep_attr(match):
        original = match.group(0)
        signal_name = match.group(2)
        modifications.append(f"添加 KEEP 属性: {signal_name}")
        return f'(* keep = "true" *) {original}'

    content = re.sub(keep_pattern, add_keep_attr, content)

    # 写入输出文件
    with open(output_file, 'w') as f:
        f.write(content)

    print("修改总结:")
    print(f"  总修改数: {len(modifications)}")
    for mod in modifications[:10]:  # 只显示前 10 个
        print(f"  - {mod}")
    if len(modifications) > 10:
        print(f"  ... 还有 {len(modifications) - 10} 个修改")
    print("")
    print(f"输出文件: {output_file}")
    print("✓ 处理完成")

if __name__ == "__main__":
    if len(sys.argv) != 3:
        print("用法: python3 add_xilinx_attrs.py <input.sv> <output.sv>")
        sys.exit(1)

    input_file = sys.argv[1]
    output_file = sys.argv[2]

    add_xilinx_attributes(input_file, output_file)
