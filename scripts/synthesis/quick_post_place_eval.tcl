proc get_arg_or_default {idx default_value} {
  if {[llength $::argv] > $idx} {
    return [lindex $::argv $idx]
  }
  return $default_value
}

proc assert_run_exists {run_name} {
  set run [get_runs $run_name]
  if {[llength $run] == 0} {
    error "Run '$run_name' not found."
  }
  return $run
}

proc assert_run_complete_like {run_name} {
  set run [assert_run_exists $run_name]
  set status [get_property STATUS $run]
  puts "run '$run_name' status=$status"
  if {![string match "*Complete*" $status]} {
    error "Run '$run_name' is not complete: $status"
  }
}

proc safe_report {description command} {
  puts "=== $description ==="
  if {[catch {uplevel #0 $command} err]} {
    puts "WARNING: $description failed: $err"
  }
}

proc write_stage_reports {out_dir stage max_paths} {
  set prefix [file join $out_dir "app_shell_9p_wrapper_${stage}"]

  safe_report "${stage} max timing" [list report_timing_summary \
    -file "${prefix}_timing_summary_max.rpt" \
    -delay_type max \
    -max_paths $max_paths \
    -nworst 5 \
    -report_unconstrained]

  safe_report "${stage} min timing" [list report_timing_summary \
    -file "${prefix}_timing_summary_min.rpt" \
    -delay_type min \
    -max_paths $max_paths \
    -nworst 5 \
    -report_unconstrained]

  safe_report "${stage} utilization" [list report_utilization \
    -file "${prefix}_utilization.rpt" \
    -hierarchical \
    -hierarchical_depth 3]

  safe_report "${stage} control sets" [list report_control_sets \
    -verbose \
    -file "${prefix}_control_sets.rpt"]

  safe_report "${stage} high fanout" [list report_high_fanout_nets \
    -max_nets 200 \
    -file "${prefix}_high_fanout.rpt"]

  safe_report "${stage} congestion" [list report_design_analysis \
    -congestion \
    -file "${prefix}_congestion.rpt"]

  safe_report "${stage} power" [list report_power \
    -file "${prefix}_power.rpt"]

  safe_report "${stage} qor suggestions" [list report_qor_suggestions \
    -file "${prefix}_qor_suggestions.rpt"]
}

set project_xpr  [get_arg_or_default 0 "/home/hyyuan/workspace/v6.0_9p_cnn_2slr_4core_yolov8/v6.0_9p_cnn_v0825/app_shell_9p.xpr"]
set out_dir      [get_arg_or_default 1 "/home/hyyuan/workspace/v6.0_9p_cnn_2slr_4core_yolov8/v6.0_9p_cnn_v0825/quick_eval_postplace"]
set run_physopt  [get_arg_or_default 2 1]
set max_paths    [get_arg_or_default 3 100]
set jobs         [get_arg_or_default 4 16]
set synth_run    [get_arg_or_default 5 "synth_1"]
set impl_run_name [get_arg_or_default 6 "impl_quick_eval_1"]

puts "=== quick_post_place_eval.tcl ==="
puts "project_xpr=$project_xpr"
puts "out_dir=$out_dir"
puts "run_physopt=$run_physopt"
puts "max_paths=$max_paths"
puts "jobs=$jobs"
puts "synth_run=$synth_run"
puts "impl_run_name=$impl_run_name"

if {![file exists $project_xpr]} {
  error "Missing project xpr: $project_xpr"
}

file mkdir $out_dir
open_project $project_xpr

assert_run_complete_like $synth_run

set synth_run_obj [assert_run_exists $synth_run]
set synth_run_dir [get_property DIRECTORY $synth_run_obj]
set synth_dcp [glob -nocomplain -directory $synth_run_dir *.dcp]
if {[llength $synth_dcp] == 0} {
  error "No synth DCP found under '$synth_run_dir'"
}
puts "using synth dcp candidate=[lindex $synth_dcp 0]"

set impl_run [get_runs $impl_run_name]
if {[llength $impl_run] == 0} {
  puts "creating implementation run '$impl_run_name'"
  create_run $impl_run_name -parent_run $synth_run -flow {Vivado Implementation 2021}
  set impl_run [get_runs $impl_run_name]
}

set_property STRATEGY Performance_Explore [get_runs $impl_run_name]

puts "=== reset_run $impl_run_name ==="
reset_run $impl_run

set to_step [expr {$run_physopt ? "phys_opt_design" : "place_design"}]
puts "=== launch_runs $impl_run_name -to_step $to_step ==="
launch_runs $impl_run_name -to_step $to_step -jobs $jobs
wait_on_run $impl_run

set impl_status [get_property STATUS $impl_run]
puts "impl run status=$impl_status"

set impl_dir [get_property DIRECTORY $impl_run]
set top_name [get_property TOP $synth_run_obj]
if {$top_name eq ""} {
  set top_name "app_shell_9p_wrapper"
}

if {$run_physopt} {
  set dcp_path [file join $impl_dir "${top_name}_physopt.dcp"]
  set stage_name "physopt_quick"
} else {
  set dcp_path [file join $impl_dir "${top_name}_placed.dcp"]
  set stage_name "placed_quick"
}

if {![file exists $dcp_path]} {
  error "Expected quick-eval checkpoint missing: $dcp_path"
}

open_checkpoint $dcp_path
write_stage_reports $out_dir $stage_name $max_paths
close_design
close_project

puts "=== quick_post_place_eval complete ==="
