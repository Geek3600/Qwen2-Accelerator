// Local replacement for the Xilinx wrapper that avoids recursive includes when
// compiling the vendor DDR4 model directly under VCS.
`define DDR4_16G_X8
`define DDR4_938_Timing
`include "arch_package_local.sv"
`include "proj_package_local.sv"
`include "interface_local.sv"
