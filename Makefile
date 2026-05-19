CC = gcc
CFLAGS = $(shell pkg-config --cflags gtk+-3.0) -Wall -g
LIBS = $(shell pkg-config --libs gtk+-3.0)

TARGET = Elec-Lock
SOURCE = Elec-Lock.c

all: $(TARGET)

$(TARGET): $(SOURCE)
	$(CC) $(CFLAGS) -o $(TARGET) $(SOURCE) $(LIBS)

clean:
	rm -f $(TARGET)

install-deps:
	sudo apt-get update
	sudo apt-get install libgtk-3-dev

.PHONY: all clean install-deps
