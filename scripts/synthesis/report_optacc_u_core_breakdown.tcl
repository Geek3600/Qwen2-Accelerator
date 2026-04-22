open_checkpoint /home/hyyuan/workspace/v6.0_9p_cnn_2slr_4core_yolov8/v6.0_9p_cnn_v0825/app_shell_9p.runs/impl_1/app_shell_9p_wrapper_opt.dcp

set fp [open "/tmp/optacc_cells.txt" w]
foreach c [lsort [get_cells -hierarchical *app_shell_9p_i/opt_acc_core_0/inst/u_core*]] {
  puts $fp $c
}
close $fp

report_utilization \
  -cells [get_cells -hierarchical *app_shell_9p_i/opt_acc_core_0/inst/u_core*] \
  -hierarchical \
  -hierarchical_depth 2 \
  -file /tmp/optacc_breakdown.rpt

close_design
