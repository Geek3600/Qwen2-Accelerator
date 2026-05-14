# Prepare host-visible board debug/control registers in the v0825 Vivado project.
#
# This script is intentionally small and is meant to run before the current
# full-project implementation flow in resume_top_impl_23.tcl.  It fixes the
# existing cnncore_sys_config AXI-lite register IP so the host can reach it via
# the Aurora/AXI path, then regenerates BD output products.

proc get_arg_or_default {idx default_value} {
  if {[llength $::argv] > $idx} {
    return [lindex $::argv $idx]
  }
  return $default_value
}

proc ensure_connected {cmd} {
  if {[catch {uplevel 1 $cmd} msg]} {
    if {[string first "already connected" $msg] < 0 &&
        [string first "is already connected" $msg] < 0 &&
        [string first "already exists" $msg] < 0 &&
        [string first "all ports/pins are already connected" $msg] < 0 &&
        [string first "BD 41-394" $msg] < 0 &&
        [string first "BD 41-395" $msg] < 0 &&
        [string first "connect_bd_net' failed due to earlier errors" $msg] < 0} {
      error $msg
    }
    puts "INFO: $msg"
  }
}

proc connect_net_if_pins_exist {net_name pin_names} {
  set pins {}
  foreach pin_name $pin_names {
    if {[catch {set pin [get_bd_pins -quiet $pin_name]}]} {
      set pin {}
    }
    if {[llength $pin] == 0} {
      puts "INFO: skip optional net $net_name, missing pin $pin_name"
      return
    }
    lappend pins $pin
  }
  ensure_connected "connect_bd_net -net $net_name $pins"
}

proc connect_cell_pin_by_names {net_name cell_name candidate_names source_pin_name} {
  set cell [get_bd_cells -quiet $cell_name]
  set source_pin [get_bd_pins -quiet $source_pin_name]
  if {[llength $cell] == 0 || [llength $source_pin] == 0} {
    puts "INFO: skip optional net $net_name, missing cell/source $cell_name $source_pin_name"
    return
  }
  set connected 0
  foreach candidate $candidate_names {
    set pin [get_bd_pins -quiet -of_objects $cell -filter "NAME == $candidate"]
    if {[llength $pin] != 0} {
      if {[catch {connect_bd_net $pin $source_pin} msg]} {
        if {[string first "already connected" $msg] < 0 &&
            [string first "is already connected" $msg] < 0 &&
            [string first "already exists" $msg] < 0 &&
            [string first "all ports/pins are already connected" $msg] < 0 &&
            [string first "BD 5-4" $msg] < 0 &&
            [string first "connect_bd_net' failed due to earlier errors" $msg] < 0} {
          error $msg
        }
        puts "INFO: $msg"
      }
      set connected 1
    }
  }
  if {$connected == 0} {
    puts "INFO: skip optional net $net_name, no matching pin on $cell_name in $candidate_names"
  }
}



set project_xpr [get_arg_or_default 0 "/home/hyyuan/workspace/v6.0_9p_cnn_2slr_4core_yolov8/v6.0_9p_cnn_v0825/app_shell_9p.xpr"]
set debug_base  [get_arg_or_default 1 "0x1000000000"]
set debug_range [get_arg_or_default 2 "0x000001000"]

puts "=== prepare_board_debug_regs_79.tcl ==="
puts "project_xpr=$project_xpr"
puts "debug_base=$debug_base"
puts "debug_range=$debug_range"

open_project $project_xpr
set bd_file [get_files -quiet */app_shell_9p.bd]
if {[llength $bd_file] == 0} {
  error "Unable to find app_shell_9p.bd in project"
}
open_bd_design [lindex $bd_file 0]

set axi_ic [get_bd_cells -quiet axi_interconnect_0]
set reg_slice [get_bd_cells -quiet axi_register_slice_5]
set sys_cfg [get_bd_cells -quiet cnncore_sys_config_1]
if {[llength $axi_ic] == 0 || [llength $reg_slice] == 0 || [llength $sys_cfg] == 0} {
  error "Missing required BD cells: axi_interconnect_0 / axi_register_slice_5 / cnncore_sys_config_1"
}

# Host path enters axi_interconnect_0/S00_AXI as full AXI4.  The debug register
# IP is AXI4-Lite/32-bit, so insert a small converter chain explicitly instead
# of asking the interconnect coupler to infer an illegal AXI4 -> AXI4-Lite path.
set_property -dict [list CONFIG.NUM_MI {3} CONFIG.M02_HAS_REGSLICE {3} CONFIG.M02_HAS_DATA_FIFO {0}] $axi_ic

set dbg_pc [get_bd_cells -quiet dbg_axi_protocol_converter]
if {[llength $dbg_pc] == 0} {
  set dbg_pc [create_bd_cell -type ip -vlnv xilinx.com:ip:axi_protocol_converter:2.1 dbg_axi_protocol_converter]
}
set dbg_dw [get_bd_cells -quiet dbg_axi_dwidth_converter]
if {[llength $dbg_dw] == 0} {
  set dbg_dw [create_bd_cell -type ip -vlnv xilinx.com:ip:axi_dwidth_converter:2.1 dbg_axi_dwidth_converter]
}

set_property -dict [list CONFIG.SI_DATA_WIDTH {512} CONFIG.MI_DATA_WIDTH {32} CONFIG.PROTOCOL {AXI4}] $dbg_dw
set_property -dict [list CONFIG.SI_PROTOCOL {AXI4} CONFIG.MI_PROTOCOL {AXI4LITE} CONFIG.DATA_WIDTH {32}] $dbg_pc

ensure_connected {connect_bd_intf_net -intf_net axi_interconnect_0_M02_AXI [get_bd_intf_pins axi_interconnect_0/M02_AXI] [get_bd_intf_pins dbg_axi_dwidth_converter/S_AXI]}
ensure_connected {connect_bd_intf_net -intf_net dbg_axi_dwidth_converter_M_AXI [get_bd_intf_pins dbg_axi_dwidth_converter/M_AXI] [get_bd_intf_pins dbg_axi_protocol_converter/S_AXI]}
ensure_connected {connect_bd_intf_net -intf_net dbg_axi_protocol_converter_M_AXI [get_bd_intf_pins dbg_axi_protocol_converter/M_AXI] [get_bd_intf_pins axi_register_slice_5/S_AXI]}
ensure_connected {connect_bd_intf_net -intf_net axi_register_slice_5_M_AXI [get_bd_intf_pins axi_register_slice_5/M_AXI] [get_bd_intf_pins cnncore_sys_config_1/S00_AXI]}

ensure_connected {connect_bd_net -net clk_wiz_0_clk_out100m [get_bd_pins axi_interconnect_0/M02_ACLK] [get_bd_pins clk_wiz_0/clk_out100m]}
ensure_connected {connect_bd_net -net clk_wiz_0_clk_out100m [get_bd_pins dbg_axi_protocol_converter/aclk] [get_bd_pins clk_wiz_0/clk_out100m]}
connect_cell_pin_by_names clk_wiz_0_clk_out100m dbg_axi_dwidth_converter {s_axi_aclk aclk} clk_wiz_0/clk_out100m
ensure_connected {connect_bd_net -net proc_sys_reset_3_peripheral_aresetn [get_bd_pins axi_interconnect_0/M02_ARESETN] [get_bd_pins proc_sys_reset_3/peripheral_aresetn]}
ensure_connected {connect_bd_net -net proc_sys_reset_3_peripheral_aresetn [get_bd_pins dbg_axi_protocol_converter/aresetn] [get_bd_pins proc_sys_reset_3/peripheral_aresetn]}
connect_cell_pin_by_names proc_sys_reset_3_peripheral_aresetn dbg_axi_dwidth_converter {s_axi_aresetn aresetn} proc_sys_reset_3/peripheral_aresetn

assign_bd_address -offset $debug_base -range $debug_range \
  -target_address_space [get_bd_addr_spaces axi_combine_v1_0_1/m00_axi] \
  [get_bd_addr_segs cnncore_sys_config_1/S00_AXI/S00_AXI_reg] -force

validate_bd_design
save_bd_design
generate_target all $bd_file
export_ip_user_files -of_objects $bd_file -no_script -sync -force -quiet
close_project

puts "=== Done: cnncore_sys_config_1 host-visible at 9P offset $debug_base ==="
