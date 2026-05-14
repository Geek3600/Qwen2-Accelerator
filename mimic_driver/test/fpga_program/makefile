LDFLAGS=../../src/
CFLAGS=../../src/
CFLAGS += -DDEBUG
GXX=gcc
SRC=fpga_program.c
TARGET=fpga_program

all:
	${GXX} ${SRC} -I${CFLAGS} -L${LDFLAGS} -lmimic_v6_api -lmimic_v6_sdk -o ${TARGET}

.PHONY: clean install

clean:
	rm -rf ${TARGET}
install:
	
