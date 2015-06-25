
all:
	cd ura-test/src; make
	cd ura/src; make

clean:
	cd ura-test/src; make clean
	cd ura/src; make clean
