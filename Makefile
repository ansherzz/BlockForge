# BlockForge — convenience targets
# Requires: Java 11+, Maven 3.6+

.PHONY: all build test demo cli api p2p mining clean

all: build

build:
	mvn -q compile

test:
	./run.sh test

demo:
	./run.sh demo

cli:
	./run.sh cli

api:
	./run.sh api

p2p:
	./run.sh p2p

mining:
	./run.sh mining

package:
	mvn package -q -DskipTests

clean:
	mvn clean -q

help:
	@echo ""
	@echo "  make build    — compile all sources"
	@echo "  make test     — run unit tests"
	@echo "  make demo     — full feature demo"
	@echo "  make cli      — interactive CLI"
	@echo "  make api      — REST API server on :4567"
	@echo "  make p2p      — P2P consensus demo"
	@echo "  make mining   — mempool & miner reward demo"
	@echo "  make package  — build JAR"
	@echo "  make clean    — remove build artifacts"
	@echo ""
