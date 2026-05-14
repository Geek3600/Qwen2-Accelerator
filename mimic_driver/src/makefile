LDFLAGS=-shared
CPPFLAGS=-std=c++11 -c -Wall -fPIC
CFLAGS= -c -Wall -fPIC
CPP_SOURCE=$(wildcard *.cpp)
CPP_OBJECTS=$(CPP_SOURCE:.cpp=.o)
C_SOURCE=$(wildcard *.c)
C_OBJECTS=$(C_SOURCE:.c=.o)
PREFIX=/usr/local/lib/

TARGET_C=libmimic_v6_api.so
TARGET_CPP=libmimic_v6_sdk.so

all: $(TARGET_C) $(TARGET_CPP)

$(TARGET_C): $(C_OBJECTS)
	gcc $(LDFLAGS) $(C_OBJECTS) -o $@

$(TARGET_CPP): $(CPP_OBJECTS)
	g++ $(LDFLAGS) $(CPP_OBJECTS) -o $@

%.o: %.cpp
	g++ $(CPPFLAGS) $< -o $@

%.o: %.c
	gcc $(CFLAGS) $< -o $@
install:
	cp -rf $(TARGET_C) $(TARGET_CPP) $(PREFIX)

.PHONY: clean

clean:
	rm -rf $(CPP_OBJECTS) $(TARGET_C) $(TARGET_CPP) $(C_OBJECTS)
