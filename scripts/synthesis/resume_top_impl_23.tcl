proc get_arg_or_default {idx default_value} {
  if {[llength $::argv] > $idx} {
    return [lindex $::argv $idx]
  }
  return $default_value
}

proc assert_run_complete {run_name} {
  set run [get_runs $run_name]
  if {[llength $run] == 0} {
    error "Run '$run_name' not found."
  }
  set status [get_property STATUS $run]
  puts "run '$run_name' status=$status"
  if {![string match "*Complete*" $status]} {
    error "Run '$run_name' did not complete successfully: $status"
  }
}

proc assert_files_exist {base_dir files} {
  foreach rel $files {
    set path [file join $base_dir $rel]
    if {![file exists $path]} {
      error "Expected generated product missing: $path"
    }
    puts "verified: $path"
  }
}

proc ensure_ip_ooc_product {project_dir ip_name filename required} {
  set run_dir [file join $project_dir "app_shell_9p.runs" "${ip_name}_synth_1"]
  set ip_dir [file join $project_dir "app_shell_9p.gen" "sources_1" "bd" "app_shell_9p" "ip" $ip_name]
  set src [file join $run_dir $filename]
  set dst [file join $ip_dir $filename]

  if {[file exists $dst]} {
    puts "Reusing existing IP product: $dst"
    return
  }
  if {[file exists $src]} {
    file mkdir $ip_dir
    file copy -force $src $dst
    puts "Copied IP product into generated dir: $src -> $dst"
    return
  }
  if {$required} {
    error "Missing required IP product '$filename' in both '$dst' and '$src'."
  }
}

proc sanitize_shell_xdc {xdc_path} {
  if {![file exists $xdc_path]} {
    puts "WARNING: shell xdc not found, skip sanitize: $xdc_path"
    return
  }

  set fh [open $xdc_path r]
  set lines [split [read $fh] "\n"]
  close $fh

  set patterns {
    {^\s*set_property PACKAGE_PIN AW8 \[get_ports GTXQ1_N_high\]\s*$}
    {^\s*set_property PACKAGE_PIN AW9 \[get_ports GTXQ1_P_high\]\s*$}
    {^\s*set gt_refclk1_in_low_port }
    {^\s*set gt_refclk1_in_hgih_port }
    {^\s*set gt_refclk1_in_low_clk }
    {^\s*set gt_refclk1_in_hgih_clk }
    {^\s*create_clock -period 6\.400 -name gt_refclk1_in_low }
    {^\s*create_clock -period 6\.400 -name gt_refclk1_in_hgih }
    {^\s*set_clock_groups -asynchronous -group \$gt_refclk1_in_low_clk}
    {^\s*set_clock_groups -asynchronous -group \$gt_refclk1_in_hgih_clk}
    {^\s*set aurora_cdc_to_pins }
    {^\s*set_false_path -to \$aurora_cdc_to_pins}
    {^\s*create_clock -period 5\.000 -name sys_clk }
    {^\s*create_clock -period 10\.000 -name ddr0_clk }
    {^\s*create_clock -period 10\.000 -name ddr1_clk }
    {^\s*set shell_clk_100 }
    {^\s*set ddr0_ui_clk }
    {^\s*set ddr1_ui_clk }
    {^\s*set_clock_groups -asynchronous -group \$shell_clk_100 -group \$ddr0_ui_clk}
    {^\s*set_clock_groups -asynchronous -group \$shell_clk_100 -group \$ddr1_ui_clk}
    {^\s*set proc_sys_reset_0_bsr_pins }
    {^\s*set_false_path -from \$proc_sys_reset_0_bsr_pins}
    {^\s*set proc_sys_reset_3_pr_pins }
    {^\s*set_false_path -from \$proc_sys_reset_3_pr_pins}
    {^\s*set proc_sys_reset_2_pr_pins }
    {^\s*set_false_path -from \$proc_sys_reset_2_pr_pins}
  }

  set if_patterns {
    {^\s*if \{\[llength \$gt_refclk1_in_low_port\] > 0\} \{$}
    {^\s*if \{\[llength \$gt_refclk1_in_hgih_port\] > 0\} \{$}
    {^\s*if \{\[llength \$gt_refclk1_in_low_clk\] > 0\} \{$}
    {^\s*if \{\[llength \$gt_refclk1_in_hgih_clk\] > 0\} \{$}
    {^\s*if \{\[llength \$aurora_cdc_to_pins\] > 0\} \{$}
    {^\s*if \{\[llength \$shell_clk_100\] > 0 && \[llength \$ddr0_ui_clk\] > 0\} \{$}
    {^\s*if \{\[llength \$shell_clk_100\] > 0 && \[llength \$ddr1_ui_clk\] > 0\} \{$}
    {^\s*if \{\[llength \$proc_sys_reset_0_bsr_pins\] > 0\} \{$}
    {^\s*if \{\[llength \$proc_sys_reset_3_pr_pins\] > 0\} \{$}
    {^\s*if \{\[llength \$proc_sys_reset_2_pr_pins\] > 0\} \{$}
  }

  set out {}
  set pending_braces 0
  set changed 0
  foreach line $lines {
    set should_comment 0
    foreach pat $patterns {
      if {[regexp $pat $line]} {
        set should_comment 1
        break
      }
    }
    foreach pat $if_patterns {
      if {[regexp $pat $line]} {
        set should_comment 1
        incr pending_braces
        break
      }
    }
    if {!$should_comment && $pending_braces > 0 && [string trim $line] eq "\}"} {
      set should_comment 1
      incr pending_braces -1
    }
    if {$should_comment && ![string match "# sanitized-by-codex *" $line]} {
      lappend out "# sanitized-by-codex $line"
      incr changed
    } else {
      lappend out $line
    }
  }

  if {$changed > 0} {
    file copy -force $xdc_path "${xdc_path}.codex.bak"
    set fh [open $xdc_path w]
    puts -nonewline $fh [join $out "\n"]
    close $fh
    puts "Sanitized shell xdc: $xdc_path (changed lines=$changed)"
  } else {
    puts "Shell xdc already sanitized: $xdc_path"
  }
}

proc sanitize_aurora_exdes_xdc {xdc_path} {
  if {![file exists $xdc_path]} {
    puts "WARNING: aurora exdes xdc not found, skip sanitize: $xdc_path"
    return
  }

  set fh [open $xdc_path r]
  set lines [split [read $fh] "\n"]
  close $fh

  set patterns {
    {^\s*set_clock_groups -asynchronous -group \[get_clocks init_clk_in -include_generated_clocks\]\s*$}
    {^\s*set_clock_groups -asynchronous -group \[get_clocks gt_refclk1_in -include_generated_clocks\]\s*$}
    {^\s*set_property LOC AJ40 \[get_ports GTYQ0_P\]\s*$}
    {^\s*set_property LOC AJ41 \[get_ports GTYQ0_N\]\s*$}
  }

  set out {}
  set changed 0
  foreach line $lines {
    set should_comment 0
    foreach pat $patterns {
      if {[regexp $pat $line]} {
        set should_comment 1
        break
      }
    }
    if {$should_comment && ![string match "# sanitized-by-codex *" $line]} {
      lappend out "# sanitized-by-codex $line"
      incr changed
    } else {
      lappend out $line
    }
  }

  if {$changed > 0} {
    file copy -force $xdc_path "${xdc_path}.codex.bak"
    set fh [open $xdc_path w]
    puts -nonewline $fh [join $out "\n"]
    close $fh
    puts "Sanitized aurora exdes xdc: $xdc_path (changed lines=$changed)"
  } else {
    puts "Aurora exdes xdc already sanitized: $xdc_path"
  }
}

proc inject_top_synth_overrides_into_run_tcl {run_tcl} {
  if {![file exists $run_tcl]} {
    error "Run Tcl '$run_tcl' not found."
  }

  set fh [open $run_tcl r]
  set data [read $fh]
  close $fh

  set begin_marker "# top_synth_safe_constraints injected begin\n"
  set end_marker   "# top_synth_safe_constraints injected end\n"
  set old_begin [string first $begin_marker $data]
  if {$old_begin >= 0} {
    set old_end [string first $end_marker $data $old_begin]
    if {$old_end >= 0} {
      set data [string replace $data $old_begin [expr {$old_end + [string length $end_marker] - 1}] ""]
    }
  }

  set synth_anchor "OPTRACE \"synth_design\" START { }\n"
  if {[string first $synth_anchor $data] < 0} {
    error "Unable to find synth_design anchor in '$run_tcl'."
  }

  set block [string map [list __BEGIN__ $begin_marker __END__ $end_marker] {__BEGIN__set_msg_config -id {Vivado 12-180} -new_severity INFO
set gt_refclk1_in_low_port [get_ports -quiet GTXQ1_P_low]
if {[llength $gt_refclk1_in_low_port] > 0} {
  create_clock -period 6.400 -name gt_refclk1_in_low $gt_refclk1_in_low_port
  set gt_refclk1_in_low_clk [get_clocks -quiet gt_refclk1_in_low -include_generated_clocks]
  if {[llength $gt_refclk1_in_low_clk] > 0} {
    set_clock_groups -asynchronous -group $gt_refclk1_in_low_clk
  }
}
	proc optacc_collect_clocks {patterns} {
	  set found {}
	  foreach pattern $patterns {
	    set found [concat $found [get_clocks -quiet -include_generated_clocks $pattern]]
	  }
	  return [lsort -unique $found]
	}
	set shell_clk_100 [optacc_collect_clocks {clk_out100m_app_shell_9p_clk_wiz_0_0 clk_out100m_app_shell_9p_clk_wiz_0_0_*}]
	set ddr_ui_clks [optacc_collect_clocks {mmcm_clkout0 mmcm_clkout0_*}]
	if {[llength $shell_clk_100] > 0 && [llength $ddr_ui_clks] > 0} {
	  set_clock_groups -asynchronous -group $shell_clk_100 -group $ddr_ui_clks
	}
set aurora_cdc_to_pins [get_pins -quiet -hier *aurora_64b66b_0_cdc_to*/D]
if {[llength $aurora_cdc_to_pins] > 0} {
  set_false_path -to $aurora_cdc_to_pins
}
set proc_sys_reset_0_bsr_pins [get_pins -quiet {app_shell_9p_i/proc_sys_reset_0/U0/ACTIVE_LOW_BSR_OUT_DFF[0].FDRE_BSR_N_*/C}]
if {[llength $proc_sys_reset_0_bsr_pins] > 0} {
  set_false_path -from $proc_sys_reset_0_bsr_pins
}
set proc_sys_reset_3_pr_pins [get_pins -quiet {app_shell_9p_i/proc_sys_reset_3/U0/ACTIVE_LOW_PR_OUT_DFF[0].FDRE_PER_N/C}]
if {[llength $proc_sys_reset_3_pr_pins] > 0} {
  set_false_path -from $proc_sys_reset_3_pr_pins
}
set proc_sys_reset_2_pr_pins [get_pins -quiet {app_shell_9p_i/proc_sys_reset_2/U0/ACTIVE_LOW_PR_OUT_DFF[0].FDRE_PER_N/C}]
if {[llength $proc_sys_reset_2_pr_pins] > 0} {
  set_false_path -from $proc_sys_reset_2_pr_pins
}
__END__}]

  set data [string map [list $synth_anchor "${block}\n${synth_anchor}"] $data]

  set fh [open $run_tcl w]
  puts -nonewline $fh $data
  close $fh
  puts "Injected safe top constraints into run Tcl: $run_tcl"
}

proc inject_impl_overrides_into_run_tcl {run_tcl} {
  if {![file exists $run_tcl]} {
    error "Impl run Tcl '$run_tcl' not found."
  }

  set fh [open $run_tcl r]
  set data [read $fh]
  close $fh

  set begin_marker "# impl_safe_constraints injected begin\n"
  set end_marker   "# impl_safe_constraints injected end\n"
  set old_begin [string first $begin_marker $data]
  if {$old_begin >= 0} {
    set old_end [string first $end_marker $data $old_begin]
    if {$old_end >= 0} {
      set data [string replace $data $old_begin [expr {$old_end + [string length $end_marker] - 1}] ""]
    }
  }

  set impl_anchor "OPTRACE \"read constraints: implementation\" END { }\n"
  if {[string first $impl_anchor $data] < 0} {
    error "Unable to find implementation constraints anchor in '$run_tcl'."
  }
  set link_anchor "OPTRACE \"link_design\" END { }\n"
  if {[string first $link_anchor $data] < 0} {
    error "Unable to find link_design anchor in '$run_tcl'."
  }

  set block [string map [list __BEGIN__ $begin_marker __END__ $end_marker] {__BEGIN__proc optacc_collect_impl_clocks {patterns} {
  set found {}
  foreach pattern $patterns {
    set found [concat $found [get_clocks -quiet -include_generated_clocks $pattern]]
  }
  return [lsort -unique $found]
}
set shell_clk_100 [optacc_collect_impl_clocks {clk_out100m_app_shell_9p_clk_wiz_0_0 clk_out100m_app_shell_9p_clk_wiz_0_0_*}]
set ddr_ui_clks [optacc_collect_impl_clocks {mmcm_clkout0 mmcm_clkout0_*}]
if {[llength $shell_clk_100] > 0 && [llength $ddr_ui_clks] > 0} {
  set_clock_groups -asynchronous -group $shell_clk_100 -group $ddr_ui_clks
}
set aurora_cdc_to_pins [get_pins -quiet -hier *aurora_64b66b_0_cdc_to*/D]
if {[llength $aurora_cdc_to_pins] > 0} {
  set_false_path -to $aurora_cdc_to_pins
}
set proc_sys_reset_0_bsr_pins [get_pins -quiet {app_shell_9p_i/proc_sys_reset_0/U0/ACTIVE_LOW_BSR_OUT_DFF[0].FDRE_BSR_N_*/C}]
if {[llength $proc_sys_reset_0_bsr_pins] > 0} {
  set_false_path -from $proc_sys_reset_0_bsr_pins
}
set proc_sys_reset_3_pr_pins [get_pins -quiet {app_shell_9p_i/proc_sys_reset_3/U0/ACTIVE_LOW_PR_OUT_DFF[0].FDRE_PER_N/C}]
if {[llength $proc_sys_reset_3_pr_pins] > 0} {
  set_false_path -from $proc_sys_reset_3_pr_pins
}
set proc_sys_reset_2_pr_pins [get_pins -quiet {app_shell_9p_i/proc_sys_reset_2/U0/ACTIVE_LOW_PR_OUT_DFF[0].FDRE_PER_N/C}]
if {[llength $proc_sys_reset_2_pr_pins] > 0} {
  set_false_path -from $proc_sys_reset_2_pr_pins
}
__END__}]

  set data [string map [list $impl_anchor "${impl_anchor}${block}"] $data]
  set floorplan_begin "# impl_qkv_attn_floorplan injected begin\n"
  set floorplan_end   "# impl_qkv_attn_floorplan injected end\n"
  set floorplan_old_begin [string first $floorplan_begin $data]
  if {$floorplan_old_begin >= 0} {
    set floorplan_old_end [string first $floorplan_end $data $floorplan_old_begin]
    if {$floorplan_old_end >= 0} {
      set data [string replace $data $floorplan_old_begin [expr {$floorplan_old_end + [string length $floorplan_end] - 1}] ""]
    }
  }
  set floorplan_block [string map [list __BEGIN__ $floorplan_begin __END__ $floorplan_end] {__BEGIN__set optacc_core_clk_pin [get_pins -quiet -hier -regexp {.*u_core_clk_div/O$}]
set optacc_core_clk_src [get_pins -quiet -hier -regexp {.*u_core_clk_div/I$}]
if {[llength [get_clocks -quiet optacc_core_clk_100m]] == 0 && [llength $optacc_core_clk_pin] > 0 && [llength $optacc_core_clk_src] > 0} {
  create_generated_clock -name optacc_core_clk_100m -source [lindex $optacc_core_clk_src 0] -divide_by 3 [lindex $optacc_core_clk_pin 0]
}
proc optacc_remove_impl_clocks {clks remove_clks} {
  set out {}
  foreach clk $clks {
    if {[lsearch -exact $remove_clks $clk] < 0} {
      lappend out $clk
    }
  }
  return [lsort -unique $out]
}
set optacc_core_clk [get_clocks -quiet -include_generated_clocks optacc_core_clk_100m]
set shell_clk_100 [optacc_collect_impl_clocks {clk_out100m_app_shell_9p_clk_wiz_0_0 clk_out100m_app_shell_9p_clk_wiz_0_0_*}]
set ddr_ui_clks [optacc_collect_impl_clocks {mmcm_clkout0 mmcm_clkout0_*}]
set shell_clk_100 [optacc_remove_impl_clocks $shell_clk_100 $optacc_core_clk]
set ddr_ui_clks [optacc_remove_impl_clocks $ddr_ui_clks $optacc_core_clk]
if {[llength $shell_clk_100] > 0 && [llength $ddr_ui_clks] > 0 && [llength $optacc_core_clk] > 0} {
  set_clock_groups -asynchronous -group $shell_clk_100 -group $ddr_ui_clks -group $optacc_core_clk
} elseif {[llength $optacc_core_clk] > 0 && [llength $ddr_ui_clks] > 0} {
  set_clock_groups -asynchronous -group $optacc_core_clk -group $ddr_ui_clks
} elseif {[llength $shell_clk_100] > 0 && [llength $ddr_ui_clks] > 0} {
  set_clock_groups -asynchronous -group $shell_clk_100 -group $ddr_ui_clks
}
set core_clk_div_clr_pins [get_pins -quiet -hier -regexp {.*u_core_clk_div/CLR$}]
if {[llength $core_clk_div_clr_pins] > 0} {
  set_false_path -to $core_clk_div_clr_pins
}
set qkv_attn_regs [get_cells -quiet -hier -regexp {.*opt_acc_core_0/inst/u_core/qkvlinear/(headCntReg|prefillCntReg|batchCntReg|outputCntReg|output_valid_reg|output_st_reg|output_last_reg|output_data_reg.*|output_head_reg.*|output_addr_reg.*|headQReg_.*|headKReg_.*|headVReg_.*|activeHeadQWordReg.*|activeHeadKWordReg.*|activeHeadVWordReg.*|shadowHeadQWordReg.*|shadowHeadKWordReg.*|shadowHeadVWordReg.*|activeHeadOffsetReg|shadowHeadOffsetReg)}]
set atten_dm1_cells [get_cells -quiet -hier -regexp {.*opt_acc_core_0/inst/u_core/atten/dm1/mem_inst/mem/mem_ext/Memory_reg_bram_.*}]
set atten_vcache_cells [get_cells -quiet -hier -regexp {.*opt_acc_core_0/inst/u_core/atten/vcache/mem_inst/mem_list_.*/mem_ext/Memory_reg_bram_.*}]
set qkv_attn_cells [concat $qkv_attn_regs $atten_dm1_cells $atten_vcache_cells]
puts "qkv_attn_regs=[llength $qkv_attn_regs] atten_dm1_cells=[llength $atten_dm1_cells] atten_vcache_cells=[llength $atten_vcache_cells]"
if {[llength $qkv_attn_cells] > 0} {
  set_property USER_SLR_ASSIGNMENT SLR1 $qkv_attn_cells
  if {[llength [get_pblocks -quiet p_qkv_attn_crit]] > 0} {
    delete_pblocks [get_pblocks -quiet p_qkv_attn_crit]
  }
  create_pblock p_qkv_attn_crit
  resize_pblock [get_pblocks p_qkv_attn_crit] -add {SLR1}
  add_cells_to_pblock [get_pblocks p_qkv_attn_crit] $qkv_attn_cells
}
__END__}]
  set data [string map [list $link_anchor "${link_anchor}${floorplan_block}"] $data]

  set fh [open $run_tcl w]
  puts -nonewline $fh $data
  close $fh
  puts "Injected safe impl constraints into run Tcl: $run_tcl"
}

proc launch_runme_async {run_dir} {
  set run_sh [file join $run_dir "runme.sh"]
  if {![file exists $run_sh]} {
    error "runme.sh not found in '$run_dir'."
  }
  file attributes $run_sh -permissions u+x
  set pid [exec /bin/sh -c "cd [list $run_dir] && ./runme.sh >/dev/null 2>&1 & echo \\$!"]
  puts "runme.sh launched with pid=$pid"
}

set project_xpr [get_arg_or_default 0 "/home/hyyuan/workspace/v6.0_9p_cnn_2slr_4core_yolov8/v6.0_9p_cnn_v0825/app_shell_9p.xpr"]
set jobs        [get_arg_or_default 1 16]
set top_run_name [get_arg_or_default 2 "synth_1"]
set impl_run_name [get_arg_or_default 3 "impl_1"]

set project_dir [file dirname $project_xpr]
set optacc_ip_dir [file join $project_dir "app_shell_9p.gen" "sources_1" "bd" "app_shell_9p" "ip" "app_shell_9p_opt_acc_core_0_3"]
set c2c_ip_dir [file join $project_dir "app_shell_9p.gen" "sources_1" "bd" "app_shell_9p" "ip" "app_shell_9p_axi_chip2chip_0to3_l_0_0"]
set shell_xdc [file join $project_dir "app_shell_9p.srcs" "constrs_1" "new" "app_shell_9p.xdc"]
set aurora0_exdes_xdc [file join $c2c_ip_dir "src" "aurora_64b66b_0_exdes.xdc"]

puts "=== resume_top_impl_23.tcl ==="
puts "project_xpr=$project_xpr"
puts "jobs=$jobs"
puts "top_run_name=$top_run_name"
puts "impl_run_name=$impl_run_name"

sanitize_shell_xdc $shell_xdc
sanitize_aurora_exdes_xdc $aurora0_exdes_xdc

open_project $project_xpr

assert_run_complete "app_shell_9p_opt_acc_core_0_3_synth_1"
set c2c_run [get_runs "app_shell_9p_axi_chip2chip_0to3_l_0_0_synth_1"]
if {[llength $c2c_run] > 0} {
  set c2c_status [get_property STATUS $c2c_run]
  puts "run 'app_shell_9p_axi_chip2chip_0to3_l_0_0_synth_1' status=$c2c_status"
  if {![string match "*Complete*" $c2c_status]} {
    puts "=== Relaunch axi_chip2chip_0to3_l_0_0 synth_1 ==="
    reset_run $c2c_run
    launch_runs $c2c_run -jobs $jobs
    wait_on_run $c2c_run
    assert_run_complete "app_shell_9p_axi_chip2chip_0to3_l_0_0_synth_1"
  }
}

ensure_ip_ooc_product $project_dir "app_shell_9p_opt_acc_core_0_3" "app_shell_9p_opt_acc_core_0_3.dcp" 1
ensure_ip_ooc_product $project_dir "app_shell_9p_opt_acc_core_0_3" "app_shell_9p_opt_acc_core_0_3_stub.v" 0
ensure_ip_ooc_product $project_dir "app_shell_9p_opt_acc_core_0_3" "app_shell_9p_opt_acc_core_0_3_sim_netlist.v" 0
ensure_ip_ooc_product $project_dir "app_shell_9p_axi_chip2chip_0to3_l_0_0" "app_shell_9p_axi_chip2chip_0to3_l_0_0.dcp" 1
ensure_ip_ooc_product $project_dir "app_shell_9p_axi_chip2chip_0to3_l_0_0" "app_shell_9p_axi_chip2chip_0to3_l_0_0_stub.v" 0
ensure_ip_ooc_product $project_dir "app_shell_9p_axi_chip2chip_0to3_l_0_0" "app_shell_9p_axi_chip2chip_0to3_l_0_0_sim_netlist.v" 0

assert_files_exist $optacc_ip_dir [list \
  "app_shell_9p_opt_acc_core_0_3.dcp" \
  "app_shell_9p_opt_acc_core_0_3_stub.v" \
  "app_shell_9p_opt_acc_core_0_3_sim_netlist.v"]

assert_files_exist $c2c_ip_dir [list \
  "app_shell_9p_axi_chip2chip_0to3_l_0_0.dcp" \
  "app_shell_9p_axi_chip2chip_0to3_l_0_0_stub.v" \
  "app_shell_9p_axi_chip2chip_0to3_l_0_0_sim_netlist.v"]

set top_run [get_runs $top_run_name]
if {[llength $top_run] == 0} {
  error "Top run '$top_run_name' not found."
}
set impl_run [get_runs $impl_run_name]
if {[llength $impl_run] == 0} {
  error "Impl run '$impl_run_name' not found."
}

puts "=== Configure Top synth_1 Run ==="
set_property STEPS.SYNTH_DESIGN.ARGS.FLATTEN_HIERARCHY rebuilt $top_run
set_property STEPS.SYNTH_DESIGN.ARGS.DIRECTIVE RuntimeOptimized $top_run
set_property STEPS.SYNTH_DESIGN.ARGS.RESOURCE_SHARING off $top_run
set_property STEPS.SYNTH_DESIGN.ARGS.NO_LC 1 $top_run
set_property STEPS.SYNTH_DESIGN.ARGS.KEEP_EQUIVALENT_REGISTERS 1 $top_run
set_property STEPS.SYNTH_DESIGN.ARGS.SHREG_MIN_SIZE 5 $top_run

puts "=== Relaunch Top synth_1 ==="
reset_run $top_run
launch_runs $top_run -scripts_only
set top_run_dir [get_property DIRECTORY $top_run]
set top_name [get_property TOP $top_run]
if {$top_name eq ""} {
  set top_name "app_shell_9p_wrapper"
}
set top_run_tcl [file join $top_run_dir "${top_name}.tcl"]
inject_top_synth_overrides_into_run_tcl $top_run_tcl
file attributes [file join $top_run_dir "runme.sh"] -permissions u+x
exec /bin/sh -c "cd [list $top_run_dir] && ./runme.sh"
assert_run_complete $top_run_name

puts "=== Launch impl_1 ==="
reset_run $impl_run
launch_runs $impl_run -scripts_only
set impl_run_dir [get_property DIRECTORY $impl_run]
set impl_run_tcl [file join $impl_run_dir "app_shell_9p_wrapper.tcl"]
inject_impl_overrides_into_run_tcl $impl_run_tcl
launch_runme_async $impl_run_dir
puts "impl_1 status=[get_property STATUS $impl_run]"
puts "impl_1 dir=[get_property DIRECTORY $impl_run]"

close_project
puts "=== Done ==="
