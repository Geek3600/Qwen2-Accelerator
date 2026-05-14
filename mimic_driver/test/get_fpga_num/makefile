LDFLAGS=../../src/
CFLAGS=../../src/
GXX=g++
SRC=get_fpga_num.cpp
TARGET=get_fpga_num

all:
	${GXX} ${SRC} -I${CFLAGS} -L${LDFLAGS} -lmimic_v6_api -lmimic_v6_sdk -o ${TARGET}

.PHONY: clean install

clean:
	rm -rf ${TARGET}
install:
	
