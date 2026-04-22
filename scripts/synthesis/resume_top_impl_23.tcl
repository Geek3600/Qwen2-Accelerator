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

set project_xpr [get_arg_or_default 0 "/home/hyyuan/workspace/v6.0_9p_cnn_2slr_4core_yolov8/v6.0_9p_cnn_v0825/app_shell_9p.xpr"]
set jobs        [get_arg_or_default 1 16]
set top_run_name [get_arg_or_default 2 "synth_1"]
set impl_run_name [get_arg_or_default 3 "impl_1"]

set project_dir [file dirname $project_xpr]
set optacc_ip_dir [file join $project_dir "app_shell_9p.gen" "sources_1" "bd" "app_shell_9p" "ip" "app_shell_9p_opt_acc_core_0_3"]
set c2c_ip_dir [file join $project_dir "app_shell_9p.gen" "sources_1" "bd" "app_shell_9p" "ip" "app_shell_9p_axi_chip2chip_0to3_l_0_0"]

puts "=== resume_top_impl_23.tcl ==="
puts "project_xpr=$project_xpr"
puts "jobs=$jobs"
puts "top_run_name=$top_run_name"
puts "impl_run_name=$impl_run_name"

open_project $project_xpr

assert_run_complete "app_shell_9p_opt_acc_core_0_3_synth_1"
assert_run_complete "app_shell_9p_axi_chip2chip_0to3_l_0_0_synth_1"

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
launch_runs $top_run -jobs $jobs
wait_on_run $top_run
assert_run_complete $top_run_name

puts "=== Launch impl_1 ==="
reset_run $impl_run
launch_runs $impl_run -jobs $jobs
puts "impl_1 status=[get_property STATUS $impl_run]"
puts "impl_1 dir=[get_property DIRECTORY $impl_run]"

close_project
puts "=== Done ==="
