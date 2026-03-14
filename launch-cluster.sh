#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

JAR=q1-api/target/q1-api-*.jar

# Build if no jar found
if ! ls $JAR &>/dev/null 2>&1; then
  echo "==> Building..."
  mvn package -DskipTests -q
fi

# Resolve glob once
JAR=$(ls q1-api/target/q1-api-*.jar | head -1)

JAVA="java --enable-preview --enable-native-access=ALL-UNNAMED"

# Ratis peer list: node-1|localhost|9001|6001,node-2|localhost|9002|6002,node-3|localhost|9003|6003
PEERS="node-1|localhost|9001|6001,node-2|localhost|9002|6002,node-3|localhost|9003|6003"

PIDS=()

cleanup() {
  echo ""
  echo "==> Stopping Q1 nodes..."
  kill "${PIDS[@]}" 2>/dev/null || true
  wait "${PIDS[@]}" 2>/dev/null || true
  echo "==> Done."
}
trap cleanup EXIT INT TERM

start_node() {
  local node_id="$1"
  local port="$2"
  local raft_port="$3"
  local data_dir="q1-data-node${node_id}"

  echo "==> Starting node${node_id} on port ${port} (raft: ${raft_port}, data: ${data_dir})..."
  Q1_NODE_ID="node-${node_id}" \
  Q1_HOST="localhost" \
  Q1_PORT="${port}" \
  Q1_RAFT_PORT="${raft_port}" \
  Q1_DATA_DIR="${data_dir}" \
  Q1_PEERS="${PEERS}" \
  $JAVA -jar "$JAR" \
    > "q1-node${node_id}.log" 2>&1 &
  PIDS+=($!)
  echo "    PID ${PIDS[-1]} — logs: q1-node${node_id}.log"
}

start_node 1 9001 6001
start_node 2 9002 6002
start_node 3 9003 6003

echo ""
echo "==> Cluster is up. Endpoints:"
echo "    node1: http://localhost:9001"
echo "    node2: http://localhost:9002"
echo "    node3: http://localhost:9003"
echo ""
echo "Press Ctrl+C to stop all nodes."

wait "${PIDS[@]}"
