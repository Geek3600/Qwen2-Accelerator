LDFLAGS=../../src/
CFLAGS=../../src/
GXX=gcc
SRC=fpga_reset.c
TARGET=fpga_reset

all:
	${GXX} ${SRC} -I${CFLAGS} -L${LDFLAGS} -lmimic_v6_api -lmimic_v6_sdk -o ${TARGET}

.PHONY: clean install

clean:
	rm -rf ${TARGET}

install:
	
