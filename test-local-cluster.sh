#!/usr/bin/env bash
# test-local-cluster.sh — smoke test for Q1 S3-compatible store
#
# Usage (standalone):
#   ./test-local-cluster.sh
#   ./test-local-cluster.sh http://localhost:9000
#
# Usage (cluster — bucket creation is not replicated so we create on each node):
#   Q1_ALL_NODES="http://localhost:9001 http://localhost:9002 http://localhost:9003" \
#   ./test-local-cluster.sh http://localhost:9001

set -euo pipefail
cd "$(dirname "$0")"

# ── config ─────────────────────────────────────────────────────────────────────
ENDPOINT="${1:-${Q1_ENDPOINT:-http://localhost:9001}}"
ALL_NODES="${Q1_ALL_NODES:-$ENDPOINT}"
BUCKET="q1-smoke-$(date +%s%3N)"
WORKDIR="$(mktemp -d)"

# ── color helpers ──────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BOLD='\033[1m'; NC='\033[0m'
PASS_COUNT=0; FAIL_COUNT=0

pass() { printf "  ${GREEN}✓${NC} %s\n" "$*"; ((++PASS_COUNT)); }
fail() { printf "  ${RED}✗${NC} %s\n" "$*"; ((++FAIL_COUNT)); }
section() { printf "\n${BOLD}▶ %s${NC}\n" "$*"; }
info()    { printf "  ${YELLOW}·${NC} %s\n" "$*"; }

# ── curl helpers ───────────────────────────────────────────────────────────────
# Writes body to $WORKDIR/response; stdout is the HTTP status code.
# -L follows 307 redirects while preserving the HTTP method (RFC 7231).
q1() {
  local method="$1"; shift
  curl -s -L -X "$method" -w "%{http_code}" -o "$WORKDIR/response" "$@"
}

check_status() {
  local name="$1" expected="$2" actual="$3"
  if [[ "$actual" == "$expected" ]]; then
    pass "$name (HTTP $actual)"
  else
    fail "$name — expected HTTP $expected, got HTTP $actual"
    [[ -s "$WORKDIR/response" ]] && sed 's/^/    /' "$WORKDIR/response" | head -5
  fi
}

# ── cleanup (runs on EXIT) ─────────────────────────────────────────────────────
cleanup() {
  echo ""
  info "Cleaning up (bucket: $BUCKET)…"
  for key in "hello.txt" "data.json" "binary.bin" "subdir/nested.txt"; do
    curl -s -L -X DELETE "$ENDPOINT/$BUCKET/$key" -o /dev/null 2>/dev/null || true
  done
  curl -s -L -X DELETE "$ENDPOINT/$BUCKET" -o /dev/null 2>/dev/null || true
  rm -rf "$WORKDIR"
}
trap cleanup EXIT INT TERM

# ── 0. connectivity ────────────────────────────────────────────────────────────
section "Connectivity"
info "Primary endpoint : $ENDPOINT"
info "All nodes        : $ALL_NODES"

wait_for_node() {
  local url="$1"
  local code
  for i in $(seq 1 30); do
    code=$(curl -s -o /dev/null -w "%{http_code}" "$url/" 2>/dev/null || true)
    # Any HTTP response (even 404) means the server is up
    [[ "$code" =~ ^[2-5][0-9][0-9]$ ]] && return 0
    sleep 0.5
  done
  printf "  ${RED}✗${NC} %s not reachable after 15 s\n" "$url" >&2
  exit 1
}

for node in $ALL_NODES; do
  wait_for_node "$node"
  pass "$node reachable"
done

# ── prepare test files ─────────────────────────────────────────────────────────
printf "Hello, Q1!\n"                                   > "$WORKDIR/hello.txt"
printf '{"service":"q1","ok":true,"ts":%s}\n' "$(date +%s)" > "$WORKDIR/data.json"
dd if=/dev/urandom bs=1024 count=64 of="$WORKDIR/binary.bin" 2>/dev/null   # 64 KiB
mkdir -p "$WORKDIR/subdir"
printf "nested content\n"                               > "$WORKDIR/subdir/nested.txt"

# ── 1. bucket operations ───────────────────────────────────────────────────────
section "Bucket operations"

# Bucket ops are replicated via Raft — create once on primary (followers redirect to leader).
code=$(q1 PUT "$ENDPOINT/$BUCKET")
check_status "Create bucket" "200" "$code"

# ListBuckets — bucket should appear
code=$(q1 GET "$ENDPOINT/")
check_status "ListBuckets" "200" "$code"
if grep -q "$BUCKET" "$WORKDIR/response" 2>/dev/null; then
  pass "Bucket '$BUCKET' present in ListBuckets"
else
  fail "Bucket '$BUCKET' absent from ListBuckets"
fi

# ── 2. PUT objects ─────────────────────────────────────────────────────────────
section "PUT objects"

code=$(q1 PUT "$ENDPOINT/$BUCKET/hello.txt" \
  -H "Content-Type: text/plain" --data-binary "@$WORKDIR/hello.txt")
check_status "PUT hello.txt" "200" "$code"

code=$(q1 PUT "$ENDPOINT/$BUCKET/data.json" \
  -H "Content-Type: application/json" --data-binary "@$WORKDIR/data.json")
check_status "PUT data.json" "200" "$code"

code=$(q1 PUT "$ENDPOINT/$BUCKET/binary.bin" \
  -H "Content-Type: application/octet-stream" --data-binary "@$WORKDIR/binary.bin")
check_status "PUT binary.bin (64 KiB)" "200" "$code"

code=$(q1 PUT "$ENDPOINT/$BUCKET/subdir/nested.txt" \
  -H "Content-Type: text/plain" --data-binary "@$WORKDIR/subdir/nested.txt")
check_status "PUT subdir/nested.txt" "200" "$code"

# ── 3. LIST objects ────────────────────────────────────────────────────────────
section "LIST objects"

code=$(q1 GET "$ENDPOINT/$BUCKET")
check_status "ListObjects (all)" "200" "$code"
for key in "hello.txt" "data.json" "binary.bin" "subdir/nested.txt"; do
  if grep -q "$key" "$WORKDIR/response" 2>/dev/null; then
    pass "'$key' present in listing"
  else
    fail "'$key' missing from listing"
  fi
done

# Prefix filter
code=$(q1 GET "$ENDPOINT/$BUCKET?prefix=subdir/")
check_status "ListObjects prefix=subdir/" "200" "$code"
if grep -q "nested.txt" "$WORKDIR/response" 2>/dev/null; then
  pass "Prefix filter includes subdir/nested.txt"
else
  fail "Prefix filter missing subdir/nested.txt"
fi
if ! grep -q "hello.txt" "$WORKDIR/response" 2>/dev/null; then
  pass "Prefix filter excludes hello.txt"
else
  fail "Prefix filter incorrectly includes hello.txt"
fi

# ── 4. HEAD objects ────────────────────────────────────────────────────────────
section "HEAD objects"

hello_size=$(wc -c < "$WORKDIR/hello.txt")
head_out=$(curl -s -L -D - -o /dev/null "$ENDPOINT/$BUCKET/hello.txt" -w "%{http_code}")
head_status=$(printf '%s' "$head_out" | tail -1)
content_length=$(printf '%s' "$head_out" | grep -i "^content-length:" | tr -d '\r' | awk '{print $2}')

if [[ "$head_status" == "200" ]]; then
  pass "HEAD hello.txt (HTTP 200)"
else
  fail "HEAD hello.txt — expected 200, got $head_status"
fi
if [[ "$content_length" == "$hello_size" ]]; then
  pass "Content-Length = $hello_size bytes"
else
  fail "Content-Length: got '$content_length', expected '$hello_size'"
fi

# HEAD on missing key → 404
missing_status=$(curl -s -L -o /dev/null -w "%{http_code}" "$ENDPOINT/$BUCKET/no-such-key.bin")
if [[ "$missing_status" == "404" ]]; then
  pass "HEAD non-existent key → 404"
else
  fail "HEAD non-existent key — expected 404, got $missing_status"
fi

# ── 5. GET objects — content verification ─────────────────────────────────────
section "GET objects (content verification)"

get_and_check() {
  local label="$1" key="$2" orig="$3"
  curl -s -L "$ENDPOINT/$BUCKET/$key" -o "$WORKDIR/got"
  if diff -q "$orig" "$WORKDIR/got" &>/dev/null; then
    pass "GET $key — content matches"
  else
    fail "GET $key — content mismatch"
  fi
}

get_and_check "hello.txt"          "hello.txt"          "$WORKDIR/hello.txt"
get_and_check "data.json"          "data.json"          "$WORKDIR/data.json"
get_and_check "binary.bin (64KiB)" "binary.bin"         "$WORKDIR/binary.bin"
get_and_check "subdir/nested.txt"  "subdir/nested.txt"  "$WORKDIR/subdir/nested.txt"

# GET missing key → 404
missing_get=$(curl -s -L -o /dev/null -w "%{http_code}" "$ENDPOINT/$BUCKET/no-such-key.bin")
if [[ "$missing_get" == "404" ]]; then
  pass "GET non-existent key → 404"
else
  fail "GET non-existent key — expected 404, got $missing_get"
fi

# ── 6. replication check (cluster mode) ───────────────────────────────────────
node_count=$(echo "$ALL_NODES" | wc -w)
if [[ "$node_count" -gt 1 ]]; then
  section "Replication (read from follower nodes)"
  # Replication is synchronous: if PUT returned 200, all replicas must have the data.
  for node in $ALL_NODES; do
    [[ "$node" == "$ENDPOINT" ]] && continue
    curl -s "$node/$BUCKET/hello.txt" -o "$WORKDIR/replica_hello.txt" 2>/dev/null
    if diff -q "$WORKDIR/hello.txt" "$WORKDIR/replica_hello.txt" &>/dev/null; then
      pass "$node — hello.txt content matches"
    else
      fail "$node — hello.txt content mismatch (replication issue?)"
    fi
    curl -s "$node/$BUCKET/binary.bin" -o "$WORKDIR/replica_binary.bin" 2>/dev/null
    if diff -q "$WORKDIR/binary.bin" "$WORKDIR/replica_binary.bin" &>/dev/null; then
      pass "$node — binary.bin content matches"
    else
      fail "$node — binary.bin content mismatch"
    fi
  done
fi

# ── 7. DELETE objects ──────────────────────────────────────────────────────────
section "DELETE objects"

for key in "hello.txt" "data.json" "binary.bin" "subdir/nested.txt"; do
  code=$(q1 DELETE "$ENDPOINT/$BUCKET/$key")
  check_status "DELETE $key" "204" "$code"
done

# Deleted objects must return 404
for key in "hello.txt" "data.json" "binary.bin" "subdir/nested.txt"; do
  code=$(curl -s -L -o /dev/null -w "%{http_code}" "$ENDPOINT/$BUCKET/$key")
  if [[ "$code" == "404" ]]; then
    pass "After delete: GET $key → 404"
  else
    fail "After delete: GET $key returned $code (expected 404)"
  fi
done

# ListObjects should be empty
code=$(q1 GET "$ENDPOINT/$BUCKET")
check_status "ListObjects after delete" "200" "$code"
key_count=$(grep -c "<Key>" "$WORKDIR/response" 2>/dev/null || true)
if [[ "$key_count" == "0" ]]; then
  pass "Listing is empty after all deletes"
else
  fail "Listing still shows $key_count key(s) after delete"
fi

# ── 8. DELETE bucket ───────────────────────────────────────────────────────────
section "DELETE bucket"

# With Raft replication, bucket ops are replicated automatically — only one node needed.
# -L follows any 307 redirect to the leader.
code=$(q1 DELETE "$ENDPOINT/$BUCKET")
check_status "Delete bucket" "204" "$code"

# Bucket must be gone from ListBuckets
code=$(q1 GET "$ENDPOINT/")
check_status "ListBuckets after bucket delete" "200" "$code"
if ! grep -q "$BUCKET" "$WORKDIR/response" 2>/dev/null; then
  pass "Deleted bucket absent from ListBuckets"
else
  fail "Deleted bucket still appears in ListBuckets"
fi

# ── summary ────────────────────────────────────────────────────────────────────
TOTAL=$((PASS_COUNT + FAIL_COUNT))
section "Summary"
printf "  Passed : ${GREEN}%d${NC} / %d\n" "$PASS_COUNT" "$TOTAL"
if [[ "$FAIL_COUNT" -gt 0 ]]; then
  printf "  Failed : ${RED}%d${NC}\n" "$FAIL_COUNT"
  exit 1
else
  printf "  ${GREEN}All tests passed.${NC}\n"
fi
