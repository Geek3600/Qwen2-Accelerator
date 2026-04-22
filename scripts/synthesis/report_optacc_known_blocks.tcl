open_checkpoint /home/hyyuan/workspace/v6.0_9p_cnn_2slr_4core_yolov8/v6.0_9p_cnn_v0825/app_shell_9p.runs/impl_1/app_shell_9p_wrapper_opt.dcp

set fp [open "/tmp/optacc_known_blocks.txt" w]
foreach p [list \
  app_shell_9p_i/opt_acc_core_0/inst/u_core/layernorm \
  app_shell_9p_i/opt_acc_core_0/inst/u_core/qkvlinear \
  app_shell_9p_i/opt_acc_core_0/inst/u_core/atten \
  app_shell_9p_i/opt_acc_core_0/inst/u_core/outlinear \
  app_shell_9p_i/opt_acc_core_0/inst/u_core/resadd \
  app_shell_9p_i/opt_acc_core_0/inst/u_core/layernorm2 \
  app_shell_9p_i/opt_acc_core_0/inst/u_core/ffnup \
  app_shell_9p_i/opt_acc_core_0/inst/u_core/ffndown \
  app_shell_9p_i/opt_acc_core_0/inst/u_core/resadd2 \
] {
  set cells [get_cells -quiet $p]
  if {[llength $cells] > 0} {
    puts $fp $p
  } else {
    puts $fp "$p MISSING"
  }
}
close $fp

report_utilization \
  -cells [get_cells -quiet {app_shell_9p_i/opt_acc_core_0/inst/u_core/layernorm app_shell_9p_i/opt_acc_core_0/inst/u_core/qkvlinear app_shell_9p_i/opt_acc_core_0/inst/u_core/atten app_shell_9p_i/opt_acc_core_0/inst/u_core/outlinear app_shell_9p_i/opt_acc_core_0/inst/u_core/resadd app_shell_9p_i/opt_acc_core_0/inst/u_core/layernorm2 app_shell_9p_i/opt_acc_core_0/inst/u_core/ffnup app_shell_9p_i/opt_acc_core_0/inst/u_core/ffndown app_shell_9p_i/opt_acc_core_0/inst/u_core/resadd2}] \
  -file /tmp/optacc_known_blocks.rpt

close_design
