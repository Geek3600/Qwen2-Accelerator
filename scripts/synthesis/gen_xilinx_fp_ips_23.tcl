# Generate Vivado floating_point IPs that match the blackbox names emitted in
# generated/Top_vivado.sv when FpBackend.setVivadoIp() is enabled.
#
# Usage:
#   vivado -mode batch -source scripts/synthesis/gen_xilinx_fp_ips_23.tcl \
#     -tclargs /tmp/fp_ip_gen_proj /home/hyyuan/workspace/opt_acc/fp_ip

proc get_arg_or_default {idx default_value} {
  if {[llength $::argv] > $idx} {
    return [lindex $::argv $idx]
  }
  return $default_value
}

proc create_fp_ip {name config_dict ip_dir} {
  puts "=== create_ip $name ==="
  create_ip -name floating_point -vendor xilinx.com -library ip -version 7.1 -module_name $name -dir $ip_dir
  set_property -dict $config_dict [get_ips $name]
  generate_target all [get_ips $name]
}

set project_dir [get_arg_or_default 0 "/tmp/fp_ip_gen_proj"]
set ip_dir [get_arg_or_default 1 "/home/hyyuan/workspace/opt_acc/fp_ip"]
set part_name [get_arg_or_default 2 "xcvu9p_CIV-flgb2104-2-i"]

if {[file exists $project_dir]} {
  file delete -force $project_dir
}
if {[file exists $ip_dir]} {
  file delete -force $ip_dir
}
file mkdir $project_dir
file mkdir $ip_dir

create_project fp_ip_gen $project_dir -part $part_name -force

set common_single [list \
  CONFIG.A_Precision_Type {Single} \
  CONFIG.Result_Precision_Type {Single} \
  CONFIG.C_Optimization {Speed_Optimized} \
  CONFIG.Axi_Optimize_Goal {Resources} \
  CONFIG.Flow_Control {Blocking} \
  CONFIG.Maximum_Latency {true} \
  CONFIG.Has_RESULT_TREADY {false}]

create_fp_ip fp_add_sp_12 [concat $common_single [list \
  CONFIG.Operation_Type {Add_Subtract} \
  CONFIG.Add_Sub_Value {Add} \
  CONFIG.C_Latency {12}]] $ip_dir

create_fp_ip fp_sub_sp_12 [concat $common_single [list \
  CONFIG.Operation_Type {Add_Subtract} \
  CONFIG.Add_Sub_Value {Subtract} \
  CONFIG.C_Latency {12}]] $ip_dir

create_fp_ip fp_mul_sp_9 [concat $common_single [list \
  CONFIG.Operation_Type {Multiply} \
  CONFIG.C_Latency {9}]] $ip_dir

create_fp_ip fp_div_sp_29 [concat $common_single [list \
  CONFIG.Operation_Type {Divide} \
  CONFIG.C_Latency {29}]] $ip_dir

create_fp_ip fp_sqrt_sp_29 [concat $common_single [list \
  CONFIG.Operation_Type {Square_root} \
  CONFIG.C_Latency {29}]] $ip_dir

create_fp_ip fp_cmp_lt_sp_3 [concat $common_single [list \
  CONFIG.Operation_Type {Compare} \
  CONFIG.C_Compare_Operation {Less_Than} \
  CONFIG.C_Latency {3}]] $ip_dir

create_fp_ip fp_cmp_eq_sp_3 [concat $common_single [list \
  CONFIG.Operation_Type {Compare} \
  CONFIG.C_Compare_Operation {Equal} \
  CONFIG.C_Latency {3}]] $ip_dir

create_fp_ip fp_cmp_gt_sp_3 [concat $common_single [list \
  CONFIG.Operation_Type {Compare} \
  CONFIG.C_Compare_Operation {Greater_Than} \
  CONFIG.C_Latency {3}]] $ip_dir

proc create_i2f_ip {name in_width ip_dir} {
  create_fp_ip $name [list \
    CONFIG.Operation_Type {Fixed_to_float} \
    CONFIG.A_Precision_Type {Custom} \
    CONFIG.C_A_Exponent_Width $in_width \
    CONFIG.C_A_Fraction_Width {0} \
    CONFIG.Result_Precision_Type {Single} \
    CONFIG.C_Optimization {Speed_Optimized} \
    CONFIG.Axi_Optimize_Goal {Resources} \
    CONFIG.Flow_Control {Blocking} \
    CONFIG.Maximum_Latency {true} \
    CONFIG.C_Latency {7} \
    CONFIG.Has_RESULT_TREADY {false}] $ip_dir
}

proc create_f2i_ip {name out_width ip_dir} {
  create_fp_ip $name [list \
    CONFIG.Operation_Type {Float_to_fixed} \
    CONFIG.A_Precision_Type {Single} \
    CONFIG.Result_Precision_Type {Custom} \
    CONFIG.C_Result_Exponent_Width $out_width \
    CONFIG.C_Result_Fraction_Width {0} \
    CONFIG.C_Optimization {Speed_Optimized} \
    CONFIG.Axi_Optimize_Goal {Resources} \
    CONFIG.Flow_Control {Blocking} \
    CONFIG.Maximum_Latency {true} \
    CONFIG.C_Latency {7} \
    CONFIG.Has_RESULT_TREADY {false}] $ip_dir
}

create_i2f_ip fp_i2f_s8_sp_7 8 $ip_dir
create_i2f_ip fp_i2f_s9_sp_7 9 $ip_dir
create_i2f_ip fp_i2f_s32_sp_7 32 $ip_dir
create_i2f_ip fp_i2f_u8_sp_7 8 $ip_dir
create_i2f_ip fp_i2f_u18_sp_7 18 $ip_dir
create_i2f_ip fp_i2f_u26_sp_7 26 $ip_dir
create_i2f_ip fp_i2f_u31_sp_7 31 $ip_dir
create_i2f_ip fp_i2f_u36_sp_7 36 $ip_dir

create_f2i_ip fp_f2i_s8_sp_7 8 $ip_dir
create_f2i_ip fp_f2i_u8_sp_7 8 $ip_dir
create_f2i_ip fp_f2i_s17_sp_7 17 $ip_dir
create_f2i_ip fp_f2i_s32_sp_7 32 $ip_dir

puts "=== generated Xilinx floating_point IPs under $ip_dir ==="
close_project
