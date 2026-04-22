# Fix and prepare the opt_acc_core OOC synthesis run inside the board Vivado project.
#
# Usage:
#   vivado -mode batch -source scripts/synthesis/fix_opt_acc_core_ooc_run.tcl \
#     -tclargs <project.xpr> ?<ip_name>? ?<run_name>? ?<mode>? ?<jobs>?
#
# Defaults:
#   project.xpr : /home/hyyuan/workspace/v6.0_9p_cnn_2slr_4core_yolov8/v6.0_9p_cnn_v0825/app_shell_9p.xpr
#   ip_name     : app_shell_9p_opt_acc_core_0_3
#   run_name    : app_shell_9p_opt_acc_core_0_3_synth_1
#   mode        : scripts_only   ; one of prepare_only / scripts_only / launch / launch_wait
#   jobs        : 16
#
# What this script does:
#   1. Regenerates the IP output products so the OOC run is not fed stale/missing files.
#   2. Forces the heavy OOC synthesis run away from Vivado's default hierarchy rebuild flow.
#   3. Applies runtime-oriented synthesis args that are friendlier to a very large user RTL block.

proc get_arg_or_default {idx default_value} {
  if {[llength $::argv] > $idx} {
    return [lindex $::argv $idx]
  }
  return $default_value
}

proc inject_optacc_xdc_into_run_tcl {run_tcl xdc_path project_dir ip_name} {
  if {![file exists $run_tcl]} {
    error "Run Tcl '$run_tcl' not found."
  }
  if {![file exists $xdc_path]} {
    error "XDC '$xdc_path' not found."
  }

  set fh [open $run_tcl r]
  set data [read $fh]
  close $fh

  set begin_marker "# opt_acc_core_resources.xdc injected begin\n"
  set end_marker   "# opt_acc_core_resources.xdc injected end\n"
  set old_begin [string first $begin_marker $data]
  if {$old_begin >= 0} {
    set old_end [string first $end_marker $data $old_begin]
    if {$old_end >= 0} {
      set data [string replace $data $old_begin [expr {$old_end + [string length $end_marker] - 1}] ""]
    }
  }
  set legacy_block [string map [list __XDC__ $xdc_path] {# opt_acc_core_resources.xdc injected
read_xdc __XDC__
# opt_acc_core RTL injected as a fallback when XCI output products lag behind.
foreach f [concat [glob -nocomplain /home/hyyuan/workspace/v6.0_9p_cnn_2slr_4core_yolov8/v6.0_9p_cnn_v0825/app_shell_9p.ip_user_files/bd/app_shell_9p/ip/app_shell_9p_opt_acc_core_0_3/sim/app_shell_9p_opt_acc_core_0_3.sv] [glob -nocomplain /home/hyyuan/workspace/v6.0_9p_cnn_2slr_4core_yolov8/v6.0_9p_cnn_v0825/app_shell_9p.ip_user_files/bd/app_shell_9p/ipshared/*/opt_acc_core.sv] [glob -nocomplain /home/hyyuan/workspace/v6.0_9p_cnn_2slr_4core_yolov8/v6.0_9p_cnn_v0825/app_shell_9p.ip_user_files/bd/app_shell_9p/ipshared/*/Top_vivado.sv]] {
  read_verilog -sv $f
}
}]
  set data [string map [list $legacy_block ""] $data]
  set legacy_simple [string map [list __XDC__ $xdc_path] {# opt_acc_core_resources.xdc injected
read_xdc __XDC__
}]
  set data [string map [list $legacy_simple ""] $data]

  set reports_begin "# opt_acc_core_extra_reports injected begin\n"
  set reports_end   "# opt_acc_core_extra_reports injected end\n"
  set reports_old_begin [string first $reports_begin $data]
  if {$reports_old_begin >= 0} {
    set reports_old_end [string first $reports_end $data $reports_old_begin]
    if {$reports_old_end >= 0} {
      set data [string replace $data $reports_old_begin [expr {$reports_old_end + [string length $reports_end] - 1}] ""]
    }
  }

  set cache_anchor "OPTRACE \"Configure IP Cache\" END { }\n"
  if {[string first $cache_anchor $data] >= 0} {
    set data [string map [list $cache_anchor "${cache_anchor}set cached_ip {}\n# opt_acc_core_resources.xdc injected: always resynthesize to honor external synth constraints\n"] $data]
  } else {
    puts "Cache anchor not found in '$run_tcl'; skip cached_ip override."
  }

  set synth_anchor "OPTRACE \"synth_design\" START { }\n"
  if {[string first $synth_anchor $data] < 0} {
    error "Unable to find synth_design anchor in '$run_tcl'."
  }
  set read_xdc_line [string map [list \
      __BEGIN__ $begin_marker \
      __END__ $end_marker \
      __XDC__ $xdc_path] {__BEGIN__read_xdc __XDC__
__END__}]
  set read_fp_ip_line {foreach ipxci [glob -nocomplain /home/hyyuan/workspace/opt_acc/fp_ip/*/*.xci] {
  read_ip $ipxci
  # Force global synthesis for fp_* IPs. This avoids the per-IP OOC checkpoint
  # flow that later explodes into hundreds of derived *_in_context.xdc
  # applications during the parent opt_acc_core synth.
  catch {set_property generate_synth_checkpoint false [get_files $ipxci]}
}
set fp_ips [get_ips fp_*]
if {[llength $fp_ips] > 0} {
  generate_target all $fp_ips
  puts "Configured [llength $fp_ips] fp_* IPs for global synthesis (generate_synth_checkpoint=false)"
}}
  set read_wrapper_line [string map [list __IP__ $ip_name __PROJ__ $project_dir] {foreach f [concat \
  [glob -nocomplain __PROJ__/app_shell_9p.ip_user_files/bd/app_shell_9p/ip/__IP__/sim/__IP__.sv] \
  [glob -nocomplain __PROJ__/app_shell_9p.gen/sources_1/bd/app_shell_9p/ip/__IP__/sim/__IP__.sv] \
  [glob -nocomplain __PROJ__/app_shell_9p.ip_user_files/bd/app_shell_9p/ipshared/*/opt_acc_core.sv] \
  [glob -nocomplain __PROJ__/app_shell_9p.ip_user_files/bd/app_shell_9p/ipshared/*/Top_vivado.sv] \
  [glob -nocomplain __PROJ__/app_shell_9p.gen/sources_1/bd/app_shell_9p/ipshared/*/opt_acc_core.sv] \
  [glob -nocomplain __PROJ__/app_shell_9p.gen/sources_1/bd/app_shell_9p/ipshared/*/Top_vivado.sv]] {
  read_verilog -sv $f
}
}]
  set data [string map [list $synth_anchor "${read_xdc_line}${read_fp_ip_line}\n${read_wrapper_line}\n${synth_anchor}"] $data]

  set reports_anchor "OPTRACE \"synth reports\" START { REPORT }\n"
  if {[string first $reports_anchor $data] < 0} {
    error "Unable to find synth reports anchor in '$run_tcl'."
  }
  set reports_block [string map [list __IP__ $ip_name __BEGIN__ $reports_begin __END__ $reports_end] {__BEGIN__create_report "__IP___synth_report_utilization_hier_0" "report_utilization -hierarchical -hierarchical_depth 2 -file __IP___utilization_hier_synth.rpt"
create_report "__IP___synth_report_timing_summary_0" "report_timing_summary -file __IP___timing_summary_synth.rpt -delay_type max -max_paths 50 -nworst 3 -report_unconstrained"
create_report "__IP___synth_report_power_0" "report_power -file __IP___power_synth.rpt"
__END__}]
  set data [string map [list $reports_anchor "${reports_anchor}${reports_block}"] $data]

  set fh [open $run_tcl w]
  puts -nonewline $fh $data
  close $fh
  puts "Injected synth XDC into run Tcl: $run_tcl"
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

proc launch_runme_wait {run_dir} {
  set run_sh [file join $run_dir "runme.sh"]
  if {![file exists $run_sh]} {
    error "runme.sh not found in '$run_dir'."
  }
  file attributes $run_sh -permissions u+x
  exec /bin/sh -c "cd [list $run_dir] && ./runme.sh"
}

set project_xpr [get_arg_or_default 0 "/home/hyyuan/workspace/v6.0_9p_cnn_2slr_4core_yolov8/v6.0_9p_cnn_v0825/app_shell_9p.xpr"]
set ip_name     [get_arg_or_default 1 "app_shell_9p_opt_acc_core_0_3"]
set run_name    [get_arg_or_default 2 "app_shell_9p_opt_acc_core_0_3_synth_1"]
set mode        [get_arg_or_default 3 "scripts_only"]
set jobs        [get_arg_or_default 4 16]
set project_dir [file dirname $project_xpr]
set optacc_xdc [file join $project_dir "app_shell_9p.srcs" "constrs_1" "new" "opt_acc_core_resources.xdc"]

if {$mode ni {"prepare_only" "scripts_only" "launch" "launch_wait"}} {
  error "Unsupported mode '$mode'. Use prepare_only, scripts_only, launch, or launch_wait."
}

puts "=== fix_opt_acc_core_ooc_run.tcl ==="
puts "project_xpr=$project_xpr"
puts "ip_name=$ip_name"
puts "run_name=$run_name"
puts "mode=$mode"
puts "jobs=$jobs"
puts "optacc_xdc=$optacc_xdc"

open_project $project_xpr

set ip [get_ips $ip_name]
if {[llength $ip] == 0} {
  error "IP '$ip_name' not found in project '$project_xpr'."
}

set run [get_runs $run_name]
if {[llength $run] == 0} {
  puts "Run '$run_name' does not exist yet. Creating it from IP '$ip_name'."
  create_ip_run $ip
  set run [get_runs $run_name]
}

set regen_obj $ip
set ip_file_candidates [get_files -all -quiet */$ip_name/$ip_name.xci]
if {[llength $ip_file_candidates] > 0} {
  set ip_file [lindex $ip_file_candidates 0]
  set parent_bd_path [get_property PARENT_COMPOSITE_FILE $ip_file]
  if {$parent_bd_path ne ""} {
    set parent_bd [get_files -quiet $parent_bd_path]
    if {[llength $parent_bd] > 0} {
      set regen_obj $parent_bd
      puts "Nested BD IP detected. Regenerating via parent BD: $parent_bd_path"
    }
  }
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

puts "=== Regenerate IP output products ==="
reset_target all $regen_obj
generate_target all $regen_obj
export_ip_user_files -of_objects $regen_obj -no_script -sync -force -quiet

puts "=== Apply synthesis run properties ==="
set_property STEPS.SYNTH_DESIGN.ARGS.FLATTEN_HIERARCHY rebuilt $run
set_property STEPS.SYNTH_DESIGN.ARGS.DIRECTIVE RuntimeOptimized $run
set_property STEPS.SYNTH_DESIGN.ARGS.RESOURCE_SHARING off $run
set_property STEPS.SYNTH_DESIGN.ARGS.NO_LC 1 $run
set_property STEPS.SYNTH_DESIGN.ARGS.KEEP_EQUIVALENT_REGISTERS 1 $run
set_property STEPS.SYNTH_DESIGN.ARGS.SHREG_MIN_SIZE 5 $run

puts "=== Reset run ==="
reset_run $run

set run_dir [get_property DIRECTORY $run]
set run_tcl [file join $run_dir "${ip_name}.tcl"]

if {$mode eq "scripts_only"} {
  puts "=== Generate scripts only ==="
  launch_runs $run -scripts_only
  inject_optacc_xdc_into_run_tcl $run_tcl $optacc_xdc $project_dir $ip_name
} elseif {$mode eq "launch"} {
  puts "=== Generate scripts, patch run Tcl, then launch runme.sh ==="
  launch_runs $run -scripts_only
  inject_optacc_xdc_into_run_tcl $run_tcl $optacc_xdc $project_dir $ip_name
  launch_runme_async $run_dir
} elseif {$mode eq "launch_wait"} {
  puts "=== Generate scripts, patch run Tcl, launch runme.sh, and wait ==="
  launch_runs $run -scripts_only
  inject_optacc_xdc_into_run_tcl $run_tcl $optacc_xdc $project_dir $ip_name
  launch_runme_wait $run_dir
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
