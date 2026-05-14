LDFLAGS=../../src/
CFLAGS=../../src/
GXX=gcc
SRC=fpga_sensor_det.c
TARGET=fpga_sensor_det

all:
	${GXX} fpga_sensor_det.c -I${CFLAGS} -L${LDFLAGS} -lmimic_v6_api -lmimic_v6_sdk -o fpga_sensor_det
	${GXX} fpga_power_det.c -I${CFLAGS} -L${LDFLAGS} -lmimic_v6_api -lmimic_v6_sdk -o fpga_power_det
	${GXX} fpga_temperature_det.c -I${CFLAGS} -L${LDFLAGS} -lmimic_v6_api -lmimic_v6_sdk -o fpga_temperature_det

.PHONY: clean install

clean:
	rm -rf ${TARGET}

install:
	
