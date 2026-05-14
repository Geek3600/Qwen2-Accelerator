LDFLAGS=../../src/
CFLAGS=../../src/
GXX=gcc
SRC=fpga_hw_version.c
TARGET=fpga_hw_version

all:
	${GXX} ${SRC} -I${CFLAGS} -L${LDFLAGS} -lmimic_v6_api -lmimic_v6_sdk -o ${TARGET}

.PHONY: clean install

clean:
	rm -rf ${TARGET}

install:
	
