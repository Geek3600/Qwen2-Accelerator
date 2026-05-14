ROOT = .
SUBDIR  = ${ROOT}/xdma_driver/xdma
SUBDIR += ${ROOT}/src
SUBDIR += ${ROOT}/test/fpga_program
SUBDIR += ${ROOT}/test/get_fpga_num
SUBDIR += ${ROOT}/test/pcie_reset
SUBDIR += ${ROOT}/test/reset
SUBDIR += ${ROOT}/test/sensor_detection
SUBDIR += ${ROOT}/test/get_hw_version
SUBDIR += ${ROOT}/test/flash_program



define make_subdir
@for i in ${SUBDIR}; do \
	(cd $$i && make $1 ) \
done;
endef 

ALL:
	$(call make_subdir)

clean:
	$(call make_subdir, clean)

install:
	$(call make_subdir, install)
