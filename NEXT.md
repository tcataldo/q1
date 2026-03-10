# Q1 — Next Improvements

Current state: append-only segments, persistent RocksDB index, two-phase compaction,
synchronous etcd replication, 54 tests (unit + S3 compliance + cluster).

Target scope: GET, PUT, HEAD, DELETE. Object listing is not a priority.

---

## 1. Correctness fixes

### 1.1 CRC32 on the hot read path

`Segment.read()` (called on every GET) does not verify the CRC32 stored in the header.
Verification exists in `scan()` and `scanStream()` but not on direct reads.
Problem: silent corruption if a sector flips between two compactions.

Blocker: the offset stored in the index points to the *value bytes*, not the header.
It is also necessary to store `keyLen` in the index entry to be able to re-read the full
header, or recompute the header offset from `valueOffset - HEADER_SIZE - keyLen`.

Least invasive solution: store `keyLen` in `RocksDbIndex.Entry` (20 → 22 bytes), which
allows finding the header offset and verifying the CRC on read.

### 1.2 ETag and per-object metadata

HEAD returns Content-Type, ETag, Content-Length, Last-Modified. Today these fields are
either absent or fabricated. `(etag, content-type, last-modified)` must be persisted per
object for HEAD and GET to be consistent.

Option A — store metadata in the segment value (fixed prefix before the value bytes).
Decoded on every GET but no additional structure.

Option B — separate RocksDB for metadata. Decouples metadata reads from value reads;
a HEAD never opens a segment.

### 1.3 Bucket replication

`createBucket` and `deleteBucket` do not propagate to followers. Each node has its own
`buckets.properties`. A PUT to the leader for a locally created bucket returns 404 on a
follower that does not have that bucket.

---

## 2. Performance

### 2.1 io_uring for reads and writes

`Segment.read()` and `Segment.append()` use `FileChannel` (NIO). On Linux ≥ 5.1,
io_uring enables I/O without a syscall per operation via the submission ring. Measurable
impact on high-concurrency workloads (>10k req/s) — exactly the email profile.

Implementation: reintroduce `UringFileIO` via Panama FFI (already sketched, removed to
simplify). The 128 KB hotpath is in the optimal range for io_uring.

### 2.2 Merge compaction (multi-segment)

The current compaction handles one segment at a time. After many deletions, we can end up
with dozens of small compacted segments. Merge compaction combines N segments into one,
reducing the number of open files and improving sequential read locality.

Algorithm: same two-phase structure but with N source segments → 1 target segment.
The challenge is handling keys that appear in multiple source segments (keep the most
recent version by segment order).

---

## 3. Cluster resilience

### 3.1 Erasure coding

Replace full-copy replication (RF=N identical copies) with erasure coding (e.g. RS(4,2):
4 data shards + 2 parity, tolerates 2 losses at 50% overhead vs RF×100%). Implementation:
vendored Java codec (`io.q1.cluster.erasure`). Architecture: per-object sharding at the
`StorageEngine` level, reconstruction on read failure.

### 3.2 Leader lease with fencing token

The current leader election (etcd conditional put) is correct but does not protect against
a slow follower executing a stale write after losing leadership. Fencing token: the leader
stamps each write with the etcd revision number of its lease. Followers reject writes whose
token is lower than the last seen.

### 3.3 Dynamic partition count reconfiguration

`Q1_PARTITIONS` is fixed at startup today. Dynamic reconfiguration (resharding) would
allow horizontal scaling without downtime: split a partition in two, progressive key
migration via compaction.

---

## 4. Observability

### 4.1 Prometheus endpoint

`GET /metrics` in Prometheus text/plain format:
- `q1_partition_segment_count{partition="p00"}` — segment count per partition
- `q1_partition_live_keys{partition="p00"}` — estimate via RocksDB
- `q1_compaction_total{partition="p00", result="ok|skipped"}` — counter per pass
- `q1_compaction_bytes_reclaimed_total` — bytes reclaimed by compaction
- `q1_request_duration_seconds{method, status}` — HTTP latency percentiles

### 4.2 Structured health endpoint

`GET /healthz`: leader/follower state per partition, replication lag, etcd status.
Usable by load balancers and Kubernetes probes.

---

## 5. Security

### 5.1 AWS Signature V4

All credentials are currently accepted without verification. Validating
`Authorization: AWS4-HMAC-SHA256 …` would allow Q1 to integrate into existing pipelines
without modifying clients.

### 5.2 TLS

Undertow supports HTTPS natively. Add `Q1_TLS_CERT` / `Q1_TLS_KEY` and configure the
`XnioSsl` channel listener.

---

## Suggested prioritization

| Priority | Item | Effort | Impact |
|---|---|---|---|
| P0 | 1.1 CRC32 on reads | S | Data integrity |
| P0 | 1.2 ETag + metadata | M | HEAD/GET correctness |
| P1 | 1.3 Bucket replication | S | Cluster correctness |
| P1 | 4.1 Prometheus metrics | M | Ops |
| P2 | 2.1 io_uring | L | I/O perf |
| P2 | 2.2 Merge compaction | M | File perf |
| P3 | 3.1 Erasure coding | XL | Storage cost |
| P3 | 3.2 Fencing token | M | Cluster correctness |
| P3 | 5.1 Signature V4 | M | Security |
