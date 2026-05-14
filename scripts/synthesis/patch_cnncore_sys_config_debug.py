#!/usr/bin/env python3
from pathlib import Path
import re
import sys


def patch_file(path: Path) -> bool:
    text = path.read_text()
    original = text

    old = "        input   [2:0]           result_status,\n        output  reg             usr_rst,"
    new = "        input   [2:0]           result_status,\n        output  reg             usr_rst,"
    text = text.replace(old, new)

    old = "    reg     [3:0] cfg_data_valid_dly;"
    new = """    reg     [3:0] cfg_data_valid_dly;
    reg     [15:0] dbg_cfg_latch_count;
    reg     [15:0] dbg_start_count;
    reg     [15:0] dbg_clear_count;
    reg     [15:0] dbg_reset_count;
    reg     [31:0] dbg_last_control_word;
    reg     [31:0] dbg_latency_cycles;
    reg            dbg_latency_active;"""
    if new not in text:
        text = text.replace(old, new)

    old = """    reg     [15:0] dbg_reset_count;
    reg     [31:0] dbg_last_control_word;"""
    new = """    reg     [15:0] dbg_reset_count;
    reg     [31:0] dbg_last_control_word;
    reg     [31:0] dbg_latency_cycles;
    reg            dbg_latency_active;"""
    if "dbg_latency_cycles" not in text:
        text = text.replace(old, new)


    duplicate_decl = """    reg     [15:0] dbg_cfg_latch_count;
    reg     [15:0] dbg_start_count;
    reg     [15:0] dbg_clear_count;
    reg     [15:0] dbg_reset_count;
    reg     [31:0] dbg_last_control_word;"""
    full_decl = """    reg     [15:0] dbg_cfg_latch_count;
    reg     [15:0] dbg_start_count;
    reg     [15:0] dbg_clear_count;
    reg     [15:0] dbg_reset_count;
    reg     [31:0] dbg_last_control_word;
    reg     [31:0] dbg_latency_cycles;
    reg            dbg_latency_active;"""
    while text.count(duplicate_decl) > 0 and full_decl in text:
        text = text.replace(duplicate_decl, "", 1)

    old = """        5'h19   : reg_data_out <= {26'b0,result_status,input_status};
        5'h1A   : reg_data_out <= slv_reg26;
        5'h1B   : reg_data_out <= slv_reg27;
        5'h1C   : reg_data_out <= slv_reg28;
        5'h1D   : reg_data_out <= slv_reg29;
        5'h1E   : reg_data_out <= slv_reg30;
        5'h1F   : reg_data_out <= slv_reg31;"""
    new = """        5'h19   : reg_data_out <= {26'b0,result_status,input_status};
        5'h1A   : reg_data_out <= 32'h0ACC2026;
        5'h1B   : reg_data_out <= {7'd0, dbg_latency_active, cfg_data_valid_dly, slv_reg24[1], result_data_clear, batch_data_set, usr_reset, result_status, input_status};
        5'h1C   : reg_data_out <= {dbg_start_count, dbg_cfg_latch_count};
        5'h1D   : reg_data_out <= {dbg_reset_count, dbg_clear_count};
        5'h1E   : reg_data_out <= dbg_last_control_word;
        5'h1F   : reg_data_out <= dbg_latency_cycles;"""
    if new not in text:
        text = text.replace(old, new)


    duplicate_decl = """    reg     [15:0] dbg_cfg_latch_count;
    reg     [15:0] dbg_start_count;
    reg     [15:0] dbg_clear_count;
    reg     [15:0] dbg_reset_count;
    reg     [31:0] dbg_last_control_word;"""
    full_decl = """    reg     [15:0] dbg_cfg_latch_count;
    reg     [15:0] dbg_start_count;
    reg     [15:0] dbg_clear_count;
    reg     [15:0] dbg_reset_count;
    reg     [31:0] dbg_last_control_word;
    reg     [31:0] dbg_latency_cycles;
    reg            dbg_latency_active;"""
    while text.count(duplicate_decl) > 0 and full_decl in text:
        text = text.replace(duplicate_decl, "", 1)

    old = """        5'h19   : reg_data_out <= {26'b0,result_status,input_status};
        5'h1A   : reg_data_out <= 32'h0ACC2026;
        5'h1B   : reg_data_out <= {8'd0, cfg_data_valid_dly, slv_reg24[1], result_data_clear, batch_data_set, usr_reset, result_status, input_status};
        5'h1C   : reg_data_out <= {dbg_start_count, dbg_cfg_latch_count};
        5'h1D   : reg_data_out <= {dbg_reset_count, dbg_clear_count};
        5'h1E   : reg_data_out <= dbg_last_control_word;
        5'h1F   : reg_data_out <= slv_reg31;"""
    new = """        5'h19   : reg_data_out <= {26'b0,result_status,input_status};
        5'h1A   : reg_data_out <= 32'h0ACC2026;
        5'h1B   : reg_data_out <= {7'd0, dbg_latency_active, cfg_data_valid_dly, slv_reg24[1], result_data_clear, batch_data_set, usr_reset, result_status, input_status};
        5'h1C   : reg_data_out <= {dbg_start_count, dbg_cfg_latch_count};
        5'h1D   : reg_data_out <= {dbg_reset_count, dbg_clear_count};
        5'h1E   : reg_data_out <= dbg_last_control_word;
        5'h1F   : reg_data_out <= dbg_latency_cycles;"""
    if "dbg_latency_cycles" in text:
        text = text.replace(old, new)

    read_mux_pattern = re.compile(
        r"(\n\s*5'h19\s*:\s*reg_data_out\s*<=\s*\{26'b0,result_status,input_status\};\n)"
        r"\s*5'h1A\s*:\s*reg_data_out\s*<=\s*[^;]+;\n"
        r"\s*5'h1B\s*:\s*reg_data_out\s*<=\s*[^;]+;\n"
        r"\s*5'h1C\s*:\s*reg_data_out\s*<=\s*[^;]+;\n"
        r"\s*5'h1D\s*:\s*reg_data_out\s*<=\s*[^;]+;\n"
        r"\s*5'h1E\s*:\s*reg_data_out\s*<=\s*[^;]+;\n"
        r"\s*5'h1F\s*:\s*reg_data_out\s*<=\s*[^;]+;"
    )
    read_mux_new = (
        "\n        5'h19   : reg_data_out <= {26'b0,result_status,input_status};\n"
        "        5'h1A   : reg_data_out <= 32'h0ACC2026;\n"
        "        5'h1B   : reg_data_out <= {7'd0, dbg_latency_active, cfg_data_valid_dly, slv_reg24[1], result_data_clear, batch_data_set, usr_reset, result_status, input_status};\n"
        "        5'h1C   : reg_data_out <= {dbg_start_count, dbg_cfg_latch_count};\n"
        "        5'h1D   : reg_data_out <= {dbg_reset_count, dbg_clear_count};\n"
        "        5'h1E   : reg_data_out <= dbg_last_control_word;\n"
        "        5'h1F   : reg_data_out <= dbg_latency_cycles;"
    )
    if "5'h1A   : reg_data_out <= 32'h0ACC2026;" not in text or "5'h1F   : reg_data_out <= dbg_latency_cycles;" not in text:
        text = read_mux_pattern.sub(read_mux_new, text, count=1)

    old = """    always @(posedge S_AXI_ACLK) begin
               
         cfg_data <= cfg_data_reg;
         cfg_data_valid_dly[0] <= cfg_data_reg_store_valid;
         cfg_data_valid_dly[1] <= cfg_data_valid_dly[0];
         cfg_data_valid_dly[2] <= cfg_data_valid_dly[1];
         cfg_data_valid_dly[3] <= cfg_data_valid_dly[2];
    end    """
    new = """    always @(posedge S_AXI_ACLK) begin
        if (S_AXI_ARESETN == 0) begin
            cfg_data <= {22*32{1'b0}};
            cfg_data_valid_dly <= 4'd0;
            dbg_cfg_latch_count <= 16'd0;
            dbg_start_count <= 16'd0;
            dbg_clear_count <= 16'd0;
            dbg_reset_count <= 16'd0;
            dbg_last_control_word <= 32'd0;
            dbg_latency_cycles <= 32'd0;
            dbg_latency_active <= 1'b0;
        end else begin
            cfg_data <= cfg_data_reg;
            cfg_data_valid_dly[0] <= cfg_data_reg_store_valid;
            cfg_data_valid_dly[1] <= cfg_data_valid_dly[0];
            cfg_data_valid_dly[2] <= cfg_data_valid_dly[1];
            cfg_data_valid_dly[3] <= cfg_data_valid_dly[2];
            if (dbg_latency_active) begin
                dbg_latency_cycles <= dbg_latency_cycles + 32'd1;
                if (result_status != 3'd0) begin
                    dbg_latency_active <= 1'b0;
                end
            end
            if (slv_reg_wren && (axi_awaddr[ADDR_LSB+OPT_MEM_ADDR_BITS:ADDR_LSB] == 5'd25) && (S_AXI_WSTRB == 4'hf)) begin
                dbg_last_control_word <= S_AXI_WDATA;
                if (S_AXI_WDATA[3]) dbg_cfg_latch_count <= dbg_cfg_latch_count + 16'd1;
                if (S_AXI_WDATA[0]) begin
                    dbg_start_count <= dbg_start_count + 16'd1;
                    dbg_latency_cycles <= 32'd0;
                    dbg_latency_active <= 1'b1;
                end
                if (S_AXI_WDATA[1]) dbg_clear_count <= dbg_clear_count + 16'd1;
                if (S_AXI_WDATA[2]) begin
                    dbg_reset_count <= dbg_reset_count + 16'd1;
                    dbg_latency_cycles <= 32'd0;
                    dbg_latency_active <= 1'b0;
                end
            end
        end
    end    """
    if new not in text:
        text = text.replace(old, new)

    old = """    always @(posedge S_AXI_ACLK) begin
        if (S_AXI_ARESETN == 0) begin
            cfg_data <= {22*32{1'b0}};
            cfg_data_valid_dly <= 4'd0;
            dbg_cfg_latch_count <= 16'd0;
            dbg_start_count <= 16'd0;
            dbg_clear_count <= 16'd0;
            dbg_reset_count <= 16'd0;
            dbg_last_control_word <= 32'd0;
        end else begin
            cfg_data <= cfg_data_reg;
            cfg_data_valid_dly[0] <= cfg_data_reg_store_valid;
            cfg_data_valid_dly[1] <= cfg_data_valid_dly[0];
            cfg_data_valid_dly[2] <= cfg_data_valid_dly[1];
            cfg_data_valid_dly[3] <= cfg_data_valid_dly[2];
            if (slv_reg_wren && (axi_awaddr[ADDR_LSB+OPT_MEM_ADDR_BITS:ADDR_LSB] == 5'd25) && (S_AXI_WSTRB == 4'hf)) begin
                dbg_last_control_word <= S_AXI_WDATA;
                if (S_AXI_WDATA[3]) dbg_cfg_latch_count <= dbg_cfg_latch_count + 16'd1;
                if (S_AXI_WDATA[0]) dbg_start_count <= dbg_start_count + 16'd1;
                if (S_AXI_WDATA[1]) dbg_clear_count <= dbg_clear_count + 16'd1;
                if (S_AXI_WDATA[2]) dbg_reset_count <= dbg_reset_count + 16'd1;
            end
        end
    end    """
    new = """    always @(posedge S_AXI_ACLK) begin
        if (S_AXI_ARESETN == 0) begin
            cfg_data <= {22*32{1'b0}};
            cfg_data_valid_dly <= 4'd0;
            dbg_cfg_latch_count <= 16'd0;
            dbg_start_count <= 16'd0;
            dbg_clear_count <= 16'd0;
            dbg_reset_count <= 16'd0;
            dbg_last_control_word <= 32'd0;
            dbg_latency_cycles <= 32'd0;
            dbg_latency_active <= 1'b0;
        end else begin
            cfg_data <= cfg_data_reg;
            cfg_data_valid_dly[0] <= cfg_data_reg_store_valid;
            cfg_data_valid_dly[1] <= cfg_data_valid_dly[0];
            cfg_data_valid_dly[2] <= cfg_data_valid_dly[1];
            cfg_data_valid_dly[3] <= cfg_data_valid_dly[2];
            if (dbg_latency_active) begin
                dbg_latency_cycles <= dbg_latency_cycles + 32'd1;
                if (result_status != 3'd0) begin
                    dbg_latency_active <= 1'b0;
                end
            end
            if (slv_reg_wren && (axi_awaddr[ADDR_LSB+OPT_MEM_ADDR_BITS:ADDR_LSB] == 5'd25) && (S_AXI_WSTRB == 4'hf)) begin
                dbg_last_control_word <= S_AXI_WDATA;
                if (S_AXI_WDATA[3]) dbg_cfg_latch_count <= dbg_cfg_latch_count + 16'd1;
                if (S_AXI_WDATA[0]) begin
                    dbg_start_count <= dbg_start_count + 16'd1;
                    dbg_latency_cycles <= 32'd0;
                    dbg_latency_active <= 1'b1;
                end
                if (S_AXI_WDATA[1]) dbg_clear_count <= dbg_clear_count + 16'd1;
                if (S_AXI_WDATA[2]) begin
                    dbg_reset_count <= dbg_reset_count + 16'd1;
                    dbg_latency_cycles <= 32'd0;
                    dbg_latency_active <= 1'b0;
                end
            end
        end
    end    """
    if "dbg_latency_active" in text:
        text = text.replace(old, new)

    if text == original:
        return False
    path.with_suffix(path.suffix + ".codex.bak").write_text(original)
    path.write_text(text)
    return True


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: patch_cnncore_sys_config_debug.py <project_dir>", file=sys.stderr)
        return 2
    project_dir = Path(sys.argv[1])
    patterns = [
        "ip_repo/cnncore_sys_config_1.0/hdl/cnncore_sys_config_v1_0_S00_AXI.v",
        "app_shell_9p.gen/sources_1/bd/app_shell_9p/ipshared/*/hdl/cnncore_sys_config_v1_0_S00_AXI.v",
        "app_shell_9p.ip_user_files/bd/app_shell_9p/ipshared/*/hdl/cnncore_sys_config_v1_0_S00_AXI.v",
    ]
    files = []
    for pattern in patterns:
        files.extend(project_dir.glob(pattern))
    files = sorted(set(files))
    if not files:
        print(f"no sys_config AXI files found under {project_dir}", file=sys.stderr)
        return 1
    changed = 0
    for path in files:
        if patch_file(path):
            changed += 1
            print(f"patched {path}")
        else:
            print(f"already patched {path}")
    print(f"patched_files={changed} total_files={len(files)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
