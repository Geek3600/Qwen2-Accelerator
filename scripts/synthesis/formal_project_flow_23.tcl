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

proc materialize_from_cache_if_needed {cache_root target_dir files} {
  file mkdir $target_dir
  foreach rel $files {
    set dst [file join $target_dir $rel]
    if {[file exists $dst]} {
      puts "already materialized: $dst"
      continue
    }
    set hits [glob -nocomplain -types f [file join $cache_root "*" $rel]]
    if {[llength $hits] == 0} {
      error "Generated product '$rel' missing in '$target_dir' and not found in cache '$cache_root'"
    }
    set src [lindex $hits 0]
    file copy -force $src $dst
    puts "materialized from cache: $src -> $dst"
  }
}

set project_xpr  [get_arg_or_default 0 "/home/hyyuan/workspace/v6.0_9p_cnn_2slr_4core_yolov8/v6.0_9p_cnn_v0825/app_shell_9p.xpr"]
set jobs         [get_arg_or_default 1 16]
set bd_path      [get_arg_or_default 2 "/home/hyyuan/workspace/v6.0_9p_cnn_2slr_4core_yolov8/v6.0_9p_cnn_v0825/app_shell_9p.srcs/sources_1/bd/app_shell_9p/app_shell_9p.bd"]
set ip_name      [get_arg_or_default 3 "app_shell_9p_opt_acc_core_0_3"]
set ip_run_name  [get_arg_or_default 4 "app_shell_9p_opt_acc_core_0_3_synth_1"]
set top_run_name [get_arg_or_default 5 "synth_1"]
set impl_run_name [get_arg_or_default 6 "impl_1"]
set fp_ip_dir    [get_arg_or_default 7 "/home/hyyuan/workspace/opt_acc/fp_ip"]

set project_dir [file dirname $project_xpr]
set optacc_xdc [file join $project_dir "app_shell_9p.srcs" "constrs_1" "new" "opt_acc_core_resources.xdc"]
set ip_gen_dir [file join $project_dir "app_shell_9p.gen" "sources_1" "bd" "app_shell_9p" "ip" $ip_name]
set ip_cache_root [file join $project_dir "app_shell_9p.cache" "ip" "2021.1"]
set ip_pre_tcl "/tmp/opt_acc_core_ooc_pre_read_xdc.tcl"

puts "=== formal_project_flow_23.tcl ==="
puts "project_xpr=$project_xpr"
puts "project_dir=$project_dir"
puts "jobs=$jobs"
puts "bd_path=$bd_path"
puts "ip_name=$ip_name"
puts "ip_run_name=$ip_run_name"
puts "top_run_name=$top_run_name"
puts "impl_run_name=$impl_run_name"
puts "optacc_xdc=$optacc_xdc"
puts "ip_gen_dir=$ip_gen_dir"
puts "ip_cache_root=$ip_cache_root"
puts "fp_ip_dir=$fp_ip_dir"

if {![file exists $optacc_xdc]} {
  error "Missing opt_acc_core XDC: $optacc_xdc"
}
if {![file isdirectory $fp_ip_dir]} {
  error "Missing floating-point IP directory: $fp_ip_dir"
}

set fp_ip_xci_files [lsort [glob -nocomplain -types f [file join $fp_ip_dir * *.xci]]]
if {[llength $fp_ip_xci_files] == 0} {
  error "No floating-point IP XCI files found under '$fp_ip_dir'"
}

set pre_fh [open $ip_pre_tcl w]
puts $pre_fh "read_xdc $optacc_xdc"
foreach fp_xci $fp_ip_xci_files {
  puts $pre_fh "read_ip $fp_xci"
}
close $pre_fh
puts "wrote OOC pre-tcl: $ip_pre_tcl"

open_project $project_xpr

set bd_file [get_files -quiet $bd_path]
if {[llength $bd_file] == 0} {
  error "BD '$bd_path' not found in project."
}

set ip_run [get_runs $ip_run_name]
if {[llength $ip_run] == 0} {
  error "IP run '$ip_run_name' not found."
}
set top_run [get_runs $top_run_name]
if {[llength $top_run] == 0} {
  error "Top run '$top_run_name' not found."
}
set impl_run [get_runs $impl_run_name]
if {[llength $impl_run] == 0} {
  error "Impl run '$impl_run_name' not found."
}

puts "=== Regenerate BD Output Products ==="
reset_target all $bd_file
generate_target all $bd_file
export_ip_user_files -of_objects $bd_file -no_script -sync -force -quiet

puts "=== Configure opt_acc_core OOC Run ==="
set_property STEPS.SYNTH_DESIGN.ARGS.FLATTEN_HIERARCHY rebuilt $ip_run
set_property STEPS.SYNTH_DESIGN.ARGS.DIRECTIVE RuntimeOptimized $ip_run
set_property STEPS.SYNTH_DESIGN.ARGS.RESOURCE_SHARING off $ip_run
set_property STEPS.SYNTH_DESIGN.ARGS.NO_LC 1 $ip_run
set_property STEPS.SYNTH_DESIGN.ARGS.KEEP_EQUIVALENT_REGISTERS 1 $ip_run
set_property STEPS.SYNTH_DESIGN.ARGS.SHREG_MIN_SIZE 5 $ip_run
set_property STEPS.SYNTH_DESIGN.TCL.PRE $ip_pre_tcl $ip_run

puts "=== Officially Launch opt_acc_core OOC Run ==="
reset_run $ip_run
launch_runs $ip_run -jobs $jobs
wait_on_run $ip_run
assert_run_complete $ip_run_name

puts "=== Refresh Generated Products After OOC ==="
generate_target all $bd_file
export_ip_user_files -of_objects $bd_file -no_script -sync -force -quiet
materialize_from_cache_if_needed $ip_cache_root $ip_gen_dir [list \
  "${ip_name}.dcp" \
  "${ip_name}_stub.v" \
  "${ip_name}_stub.vhdl" \
  "${ip_name}_sim_netlist.v" \
  "${ip_name}_sim_netlist.vhdl"]
assert_files_exist $ip_gen_dir [list \
  "${ip_name}.dcp" \
  "${ip_name}_stub.v" \
  "${ip_name}_stub.vhdl" \
  "${ip_name}_sim_netlist.v" \
  "${ip_name}_sim_netlist.vhdl"]

puts "=== Configure Top synth_1 Run ==="
set_property STEPS.SYNTH_DESIGN.ARGS.FLATTEN_HIERARCHY rebuilt $top_run
set_property STEPS.SYNTH_DESIGN.ARGS.DIRECTIVE RuntimeOptimized $top_run
set_property STEPS.SYNTH_DESIGN.ARGS.RESOURCE_SHARING off $top_run
set_property STEPS.SYNTH_DESIGN.ARGS.NO_LC 1 $top_run
set_property STEPS.SYNTH_DESIGN.ARGS.KEEP_EQUIVALENT_REGISTERS 1 $top_run
set_property STEPS.SYNTH_DESIGN.ARGS.SHREG_MIN_SIZE 5 $top_run

puts "=== Officially Launch Top synth_1 ==="
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
