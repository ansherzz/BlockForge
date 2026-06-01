#!/bin/bash
# ─────────────────────────────────────────────────────────────────────────────
# BlockForge — build & run
#
#   ./run.sh              Demo of all features (default)
#   ./run.sh demo         Same as above
#   ./run.sh cli          Interactive wallet & block explorer CLI
#   ./run.sh api          REST API server  →  http://localhost:4567
#   ./run.sh p2p          Multi-node P2P consensus demonstration
#   ./run.sh mining       Mempool + miner reward demonstration
#   ./run.sh test         Run the unit test suite
# ─────────────────────────────────────────────────────────────────────────────

set -e
cd "$(dirname "$0")"

compile() {
    echo ">>> Compiling..."
    mvn -q compile
}

MODE="${1:-demo}"

case "$MODE" in
  test)
    echo ">>> Running tests..."
    mvn -q test-compile
    mvn test
    ;;
  *)
    compile
    echo ">>> BlockForge — mode: $MODE"
    mvn -q exec:java \
      -Dexec.mainClass="com.blockforge.BlockForge" \
      -Dexec.args="$MODE"
    ;;
esac
