proc get_arg_or_default {idx default_value} {
  if {[llength $::argv] > $idx} {
    return [lindex $::argv $idx]
  }
  return $default_value
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

proc ensure_top_ip_products {project_dir} {
  ensure_ip_ooc_product $project_dir "app_shell_9p_opt_acc_core_0_3" "app_shell_9p_opt_acc_core_0_3.dcp" 1
  ensure_ip_ooc_product $project_dir "app_shell_9p_opt_acc_core_0_3" "app_shell_9p_opt_acc_core_0_3_stub.v" 0
  ensure_ip_ooc_product $project_dir "app_shell_9p_opt_acc_core_0_3" "app_shell_9p_opt_acc_core_0_3_sim_netlist.v" 0
  ensure_ip_ooc_product $project_dir "app_shell_9p_axi_chip2chip_0to3_l_0_0" "app_shell_9p_axi_chip2chip_0to3_l_0_0.dcp" 1
  ensure_ip_ooc_product $project_dir "app_shell_9p_axi_chip2chip_0to3_l_0_0" "app_shell_9p_axi_chip2chip_0to3_l_0_0_stub.v" 0
  ensure_ip_ooc_product $project_dir "app_shell_9p_axi_chip2chip_0to3_l_0_0" "app_shell_9p_axi_chip2chip_0to3_l_0_0_sim_netlist.v" 0
}

proc inject_top_synth_reports_into_run_tcl {run_tcl run_name} {
  if {![file exists $run_tcl]} {
    error "Run Tcl '$run_tcl' not found."
  }

  set fh [open $run_tcl r]
  set data [read $fh]
  close $fh

  set begin_marker "# top_synth_extra_reports injected begin\n"
  set end_marker   "# top_synth_extra_reports injected end\n"
  set old_begin [string first $begin_marker $data]
  if {$old_begin >= 0} {
    set old_end [string first $end_marker $data $old_begin]
    if {$old_end >= 0} {
      set data [string replace $data $old_begin [expr {$old_end + [string length $end_marker] - 1}] ""]
    }
  }

  set cdc_begin "# top_synth_cdc_cleanup injected begin\n"
  set cdc_end   "# top_synth_cdc_cleanup injected end\n"
  set cdc_old_begin [string first $cdc_begin $data]
  if {$cdc_old_begin >= 0} {
    set cdc_old_end [string first $cdc_end $data $cdc_old_begin]
    if {$cdc_old_end >= 0} {
      set data [string replace $data $cdc_old_begin [expr {$cdc_old_end + [string length $cdc_end] - 1}] ""]
    }
  }

  set synth_anchor "OPTRACE \"synth_design\" START { }\n"
  if {[string first $synth_anchor $data] < 0} {
    error "Unable to find synth_design anchor in '$run_tcl'."
  }
  set xpm_warning_cfg {# Vendor xpm_memory_xdc.tcl emits Vivado 12-180 when its
# primitive-cell query runs before memory primitives are visible. Our project
# XDC uses -quiet lookups, so demoting this message ID is safe here.
set_msg_config -id {Vivado 12-180} -new_severity INFO
}
  set cdc_block [string map [list __BEGIN__ $cdc_begin __END__ $cdc_end] {__BEGIN__proc optacc_unique_clocks {clks} {
  return [lsort -unique $clks]
}
proc optacc_remove_clocks {clks remove_clks} {
  set out {}
  foreach clk $clks {
    if {[lsearch -exact $remove_clks $clk] < 0} {
      lappend out $clk
    }
  }
  return [lsort -unique $out]
}
set shell_clk [optacc_unique_clocks [get_clocks -quiet -include_generated_clocks {clk_out100m_app_shell_9p_clk_wiz_0_0 clk_out100m_app_shell_9p_clk_wiz_0_0_*}]]
set ddr_ui_clks [optacc_unique_clocks [get_clocks -quiet -include_generated_clocks {mmcm_clkout0 mmcm_clkout0_* pll_clk[0]_2_DIV pll_clk[1]_2_DIV pll_clk[2]_2_DIV pll_clk[0]_3_DIV pll_clk[1]_3_DIV pll_clk[2]_3_DIV}]]
set optacc_core_clk [optacc_unique_clocks [get_clocks -quiet -include_generated_clocks optacc_core_clk_100m]]
set shell_clk [optacc_remove_clocks $shell_clk $optacc_core_clk]
set ddr_ui_clks [optacc_remove_clocks $ddr_ui_clks $optacc_core_clk]
if {[llength $shell_clk] > 0 && [llength $ddr_ui_clks] > 0 && [llength $optacc_core_clk] > 0} {
  set_clock_groups -asynchronous -group $shell_clk -group $ddr_ui_clks -group $optacc_core_clk
} elseif {[llength $shell_clk] > 0 && [llength $ddr_ui_clks] > 0} {
  set_clock_groups -asynchronous -group $shell_clk -group $ddr_ui_clks
} elseif {[llength $optacc_core_clk] > 0 && [llength $ddr_ui_clks] > 0} {
  set_clock_groups -asynchronous -group $optacc_core_clk -group $ddr_ui_clks
}
__END__}]
  set data [string map [list $synth_anchor "${xpm_warning_cfg}\n${cdc_block}\n${synth_anchor}"] $data]

  set reports_anchor "OPTRACE \"synth reports\" START { REPORT }\n"
  if {[string first $reports_anchor $data] < 0} {
    error "Unable to find synth reports anchor in '$run_tcl'."
  }

  set block [string map [list __RUN__ $run_name __BEGIN__ $begin_marker __END__ $end_marker] {__BEGIN__create_report "__RUN___synth_report_utilization_hier_0" "report_utilization -hierarchical -hierarchical_depth 2 -file app_shell_9p_wrapper_utilization_hier_synth.rpt"
create_report "__RUN___synth_report_timing_summary_0" "report_timing_summary -file app_shell_9p_wrapper_timing_summary_synth.rpt -delay_type max -max_paths 50 -nworst 3 -report_unconstrained"
create_report "__RUN___synth_report_power_0" "report_power -file app_shell_9p_wrapper_power_synth.rpt"
create_report "__RUN___synth_report_high_fanout_0" "report_high_fanout_nets -max_nets 50 -file app_shell_9p_wrapper_high_fanout_synth.rpt"
create_report "__RUN___synth_report_qor_0" "report_qor_suggestions -file app_shell_9p_wrapper_qor_suggestions_synth.rpt"
__END__}]
  set data [string map [list $reports_anchor "${reports_anchor}${block}"] $data]

  set fh [open $run_tcl w]
  puts -nonewline $fh $data
  close $fh
  puts "Injected extra top synth reports into run Tcl: $run_tcl"
}

set project_xpr [get_arg_or_default 0 "/home/hyyuan/workspace/v6.0_9p_cnn_2slr_4core_yolov8/v6.0_9p_cnn_v0825/app_shell_9p.xpr"]
set run_name    [get_arg_or_default 1 "synth_1"]
set mode        [get_arg_or_default 2 "launch"]
set jobs        [get_arg_or_default 3 16]
set bd_path     [get_arg_or_default 4 "/home/hyyuan/workspace/v6.0_9p_cnn_2slr_4core_yolov8/v6.0_9p_cnn_v0825/app_shell_9p.srcs/sources_1/bd/app_shell_9p/app_shell_9p.bd"]

if {$mode ni {"scripts_only" "launch" "launch_wait"}} {
  error "Unsupported mode '$mode'. Use scripts_only, launch, or launch_wait."
}

puts "=== launch_project_synth_23.tcl ==="
puts "project_xpr=$project_xpr"
puts "run_name=$run_name"
puts "mode=$mode"
puts "jobs=$jobs"
puts "bd_path=$bd_path"

open_project $project_xpr
set project_dir [file dirname $project_xpr]
ensure_top_ip_products $project_dir

set run [get_runs $run_name]
if {[llength $run] == 0} {
  error "Run '$run_name' not found in project '$project_xpr'."
}

set bd_file [get_files -quiet $bd_path]
if {[llength $bd_file] == 0} {
  error "BD '$bd_path' not found in project '$project_xpr'."
}

puts "=== Before ==="
foreach prop {
  STRATEGY
  STATUS
  DIRECTORY
  STEPS.SYNTH_DESIGN.ARGS.DIRECTIVE
  STEPS.SYNTH_DESIGN.ARGS.FLATTEN_HIERARCHY
  STEPS.SYNTH_DESIGN.ARGS.RESOURCE_SHARING
  STEPS.SYNTH_DESIGN.ARGS.NO_LC
  STEPS.SYNTH_DESIGN.ARGS.KEEP_EQUIVALENT_REGISTERS
  STEPS.SYNTH_DESIGN.ARGS.SHREG_MIN_SIZE
} {
  puts "$prop=[get_property $prop $run]"
}

# Keep top-level synthesis consistent with the successful OOC run knobs.
set_property STEPS.SYNTH_DESIGN.ARGS.FLATTEN_HIERARCHY rebuilt $run
set_property STEPS.SYNTH_DESIGN.ARGS.DIRECTIVE RuntimeOptimized $run
set_property STEPS.SYNTH_DESIGN.ARGS.RESOURCE_SHARING off $run
set_property STEPS.SYNTH_DESIGN.ARGS.NO_LC 1 $run
set_property STEPS.SYNTH_DESIGN.ARGS.KEEP_EQUIVALENT_REGISTERS 1 $run
set_property STEPS.SYNTH_DESIGN.ARGS.SHREG_MIN_SIZE 5 $run

puts "=== Reuse existing BD/IP output products ==="
# Do not reset the BD target tree here. That was the step that wiped freshly
# generated OOC DCPs. We still need to refresh generated outputs so the project
# file set picks up newly materialized DCP/stub/netlist files from dependent IPs.
generate_target all $bd_file
export_ip_user_files -of_objects $bd_file -no_script -sync -force -quiet

puts "=== Reset run ==="
reset_run $run

set run_dir [get_property DIRECTORY $run]
set top_name [get_property TOP $run]
if {$top_name eq ""} {
  set top_name "app_shell_9p_wrapper"
}
set run_tcl [file join $run_dir "${top_name}.tcl"]

if {$mode eq "scripts_only"} {
  puts "=== Generate scripts only ==="
  launch_runs $run -scripts_only
  inject_top_synth_reports_into_run_tcl $run_tcl $run_name
} elseif {$mode eq "launch"} {
  puts "=== Generate scripts then launch runme.sh ==="
  launch_runs $run -scripts_only
  inject_top_synth_reports_into_run_tcl $run_tcl $run_name
  launch_runme_async $run_dir
} else {
  puts "=== Generate scripts, patch reports, then launch and wait ==="
  launch_runs $run -scripts_only
  inject_top_synth_reports_into_run_tcl $run_tcl $run_name
  file attributes [file join $run_dir "runme.sh"] -permissions u+x
  exec /bin/sh -c "cd [list $run_dir] && ./runme.sh"
}

puts "=== After ==="
foreach prop {
  STRATEGY
  STATUS
  DIRECTORY
  STEPS.SYNTH_DESIGN.ARGS.DIRECTIVE
  STEPS.SYNTH_DESIGN.ARGS.FLATTEN_HIERARCHY
  STEPS.SYNTH_DESIGN.ARGS.RESOURCE_SHARING
  STEPS.SYNTH_DESIGN.ARGS.NO_LC
  STEPS.SYNTH_DESIGN.ARGS.KEEP_EQUIVALENT_REGISTERS
  STEPS.SYNTH_DESIGN.ARGS.SHREG_MIN_SIZE
} {
  puts "$prop=[get_property $prop $run]"
}

close_project
puts "=== Done ==="
