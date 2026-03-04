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

ETCD_NAME="q1-etcd"
ETCD_PORT=2379
ETCD_IMAGE="gcr.io/etcd-development/etcd:v3.5.17"

JAVA="java --enable-preview --enable-native-access=ALL-UNNAMED"

cleanup() {
  echo ""
  echo "==> Stopping Q1 nodes..."
  kill "${PIDS[@]}" 2>/dev/null || true
  wait "${PIDS[@]}" 2>/dev/null || true
  echo "==> Stopping etcd container..."
  docker rm -f "$ETCD_NAME" 2>/dev/null || true
  echo "==> Done."
}
trap cleanup EXIT INT TERM

# Start etcd
echo "==> Starting etcd container ($ETCD_IMAGE)..."
docker rm -f "$ETCD_NAME" 2>/dev/null || true
docker run -d --name "$ETCD_NAME" \
  -p "${ETCD_PORT}:2379" \
  "$ETCD_IMAGE" \
  etcd \
    --listen-client-urls=http://0.0.0.0:2379 \
    --advertise-client-urls=http://0.0.0.0:2379

echo "==> Waiting for etcd to be ready..."
for i in $(seq 1 20); do
  if docker exec "$ETCD_NAME" etcdctl endpoint health &>/dev/null; then
    echo "    etcd ready."
    break
  fi
  sleep 0.5
  if [[ $i -eq 20 ]]; then
    echo "ERROR: etcd did not become ready in time." >&2
    exit 1
  fi
done

ETCD_ENDPOINTS="http://localhost:${ETCD_PORT}"
PIDS=()

start_node() {
  local node_id="$1"
  local port="$2"
  local data_dir="q1-data-node${node_id}"

  echo "==> Starting node${node_id} on port ${port} (data: ${data_dir})..."
  Q1_NODE_ID="node-${node_id}" \
  Q1_HOST="localhost" \
  Q1_PORT="${port}" \
  Q1_DATA_DIR="${data_dir}" \
  Q1_ETCD="${ETCD_ENDPOINTS}" \
  Q1_RF=3 \
  $JAVA -jar "$JAR" \
    > "q1-node${node_id}.log" 2>&1 &
  PIDS+=($!)
  echo "    PID ${PIDS[-1]} — logs: q1-node${node_id}.log"
}

start_node 1 9001
start_node 2 9002
start_node 3 9003

echo ""
echo "==> Cluster is up. Endpoints:"
echo "    node1: http://localhost:9001"
echo "    node2: http://localhost:9002"
echo "    node3: http://localhost:9003"
echo ""
echo "Press Ctrl+C to stop all nodes and etcd."

wait "${PIDS[@]}"
