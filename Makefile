verilog:
	rm -rf obj_dir
	verilator -Wall -Wno-UNUSED -Wno-VARHIDDEN -Wno-DECLFILENAME -Wno-PINCONNECTEMPTY --public -Iverification -Iverification/assert -Iverification/assume -Iverification/cover --cc ./generated/Top.sv --exe ./testbench/top.cpp --trace-fst
build:verilog
	make -C obj_dir -f VTop.mk VTop
run:build
	./obj_dir/VTop
clean:
	rm -rf obj_dir
