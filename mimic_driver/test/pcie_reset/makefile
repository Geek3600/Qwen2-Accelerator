LDFLAGS=../../src/
CFLAGS=../../src/
GXX=gcc
SRC=pcie_reset.c ipc_flock.c
TARGET=pcie_reset

all:
	${GXX} ${SRC} -I${CFLAGS} -L${LDFLAGS} -std=c99 -lmimic_v6_api -lmimic_v6_sdk -o ${TARGET}

.PHONY: clean install

clean:
	rm -rf ${TARGET}
install:
	
