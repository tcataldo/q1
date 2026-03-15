# Q1 — Status and TODO

## Current state (March 2026)

### Storage
- Append-only segments + persistent RocksDB index (no rescan on startup)
- Two-phase crash-safe compaction (configurable threshold, summary log after each pass)
- CRC32 verified on all read paths: `scan()`, `scanStream()`, and direct GET (`Segment.read`)
- `RocksDbIndex.Entry`: 22 bytes — segmentId(4) + valueOffset(8) + valueLength(8) + keyLen(2)
  Legacy 20-byte entries (keyLen=-1) skip CRC on read; upgraded to 22 bytes on next compaction
- 16 partitions by default, routed by `hashCode() % N`
- Real object size surfaced in S3 listings (non-EC); EC objects show 0 (no persistent metadata yet)
- Internal buckets (`__q1_*`) protected: any access returns 403 AccessDenied

### Cluster
- Embedded Apache Ratis (Raft, no external coordinator)
- Replication mode: Raft log on write path, transparent proxy to leader (client sees 200, never 307)
- EC mode (`Q1_EC_K > 0`): direct HTTP fan-out, Raft off the data path; any node accepts PUT/DELETE
- Bucket CREATE/DELETE go through the Raft log in both modes
- Raft snapshots: `takeSnapshot()` + `initialize()` with `SimpleStateMachineStorage`;
  auto-trigger every 10 000 committed entries; restart replays only entries after last snapshot

### Erasure Coding
- Vendored RS codec (`io.q1.cluster.erasure`)
- `EcObjectHandler`: encode → shard fan-out → 200 (no Raft write)
- Deterministic ring placement: `hash(bucket+key) % N`, k+m consecutive nodes
- Self-describing shard payload: 8B originalSize header + RS data
- Transparent fallback to plain replication for pre-EC objects
- `EcRepairScanner`: background scanner, RocksDB checkpoint, reconstructs missing shards

### S3 API
- GET, PUT, HEAD, DELETE objects
- ListObjectsV1 + ListObjectsV2 with `delimiter` (CommonPrefixes), `max-keys`,
  `continuation-token` / `marker` — 100% RocksDB listing, no segment scan
- EC listing transparent: EC objects appear in normal S3 listings with size=0
- Bucket list, create, delete
- `GET /healthz`: JSON `{nodeId, mode, status, partitions}` + cluster fields; 200/503
- `s3cmd ls`, `s3cmd ls --recursive`, `s3cmd ls s3://bucket/prefix/` working

### Tests (34 ITs + 102 unit tests = 136 total)

| Module | Suite | Class | Tests |
|---|---|---|---|
| q1-core | Unit | `SegmentTest` | 9 |
| q1-core | Unit | `PartitionTest` | 24 |
| q1-core | Unit | `StorageEngineSyncTest` | 5 |
| q1-erasure | Unit | `ReedSolomonTest` | 21 |
| q1-erasure | Unit | `MatrixTest` | 19 |
| q1-erasure | Unit | `GaloisTest` | 14 |
| q1-cluster | Unit | `ErasureCoderTest` | 10 |
| q1-tests | IT | `S3CompatibilityIT` | 14 |
| q1-tests | IT | `ClusterIT` | 5 |
| q1-tests | IT | `ClusterReplicaIT` | 4 |
| q1-tests | IT | `RestartResilienceIT` | 4 |
| q1-tests | IT | `EcClusterIT` | 4 |
| q1-tests | IT | `HealthzIT` | 5 |
| q1-tests | IT (opt-in) | `BenchmarkIT` | 2 |

### Not implemented
- Dynamic cluster membership (static topology via `Q1_PEERS`)
- AWS Signature V4 (any credentials accepted)
- TLS
- Multipart upload
- Persistent object metadata: ETag, Content-Type, Last-Modified, real Size for EC objects
- Prometheus metrics endpoint (`/metrics`)
- Elastic EC re-encoding (add/remove nodes without restart)

---

## TODO — performance and resilience focus

### ⚡ Quick wins (half a day each)

**Read-path: skip CRC on HEAD**
`ObjectHandler.head()` calls `engine.exists()` (index-only, no segment read) — correct.
`EcObjectHandler.head()` currently does a full shard fetch to check existence.
Replace with a HEAD request to the shard endpoint (already implemented in `HttpShardClient.shardExists()`).
Saves one full shard decode per HEAD request in EC mode.

**Write-path: async Raft submit for non-critical ops**
`DELETE` goes through `cluster.submit()` which blocks until Raft quorum.
For the email workload, a DELETE that is durably acknowledged by a quorum
but not yet applied on all followers is acceptable.
`RaftClient.async().send()` exists in Ratis — fire and await only at the HTTP response boundary.
Expected gain: ~30–50 % lower DELETE latency at the tail (P99).

**Segment read: single I/O for CRC + value**
Current `Segment.read(valueOffset, valueLength, keyLen)` does two reads:
one for the header (19 bytes) and one for key bytes, then returns pre-read value bytes.
Consolidate into one `FileIO.read` call: read `[HEADER_SIZE + keyLen + valueLength]` bytes
starting at `headerOffset`, verify CRC, return the value slice in-place.
Eliminates one syscall per GET — measurable at high QPS on short objects.

---

### 🔒 Resilience (1–3 days each)

**EC fault tolerance IT**
`EcRepairScanner` is implemented but tested only on the happy path.
Write `EcResilienceIT`:
1. Start 3-node EC(2+1) cluster, PUT 20 objects.
2. Stop node 1 (one shard permanently unavailable).
3. GET all 20 objects → must reconstruct from k=2 remaining shards.
4. Restart node 1 (empty data dir — simulates disk replacement).
5. Wait for `EcRepairScanner` to push back missing shards.
6. Verify all shards present on node 1 via HEAD on shard endpoint.
`RestartResilienceIT` already covers replication mode — same structure, adapt for EC.

**Replication: write quorum timeout and client error**
`cluster.submit()` has no explicit timeout beyond Ratis internals.
If the leader loses quorum mid-write (2nd node crashes during a 2-node cluster),
`submit()` blocks indefinitely until Ratis times out — the client hangs.
Add a configurable `Q1_RAFT_SUBMIT_TIMEOUT_MS` (default 5 000 ms), wrap `submit()`
in a `CompletableFuture.orTimeout`, and surface `503 ServiceUnavailable` immediately.
Test: `ClusterIT` variant that kills the follower mid-PUT and expects a timely error.

**Compaction: prevent starvation under high write load**
Under sustained PUT load, the active segment rolls over faster than compaction runs.
Sealed segments accumulate → more open file descriptors → slower GETs (more segments
to search if the index lookup fails — unlikely but possible after a crash that clears
the RocksDB index).
Add a back-pressure mechanism: if sealed segment count exceeds `Q1_MAX_SEALED_SEGMENTS`
(default 32), pause new PUT operations until compaction catches up.
Implementation: a `Semaphore` in `Partition.put()` released by `Compactor.compact()`.

---

### 📈 Performance (1–2 weeks)

**Prometheus `/metrics`**
Undertow `ExchangeCompletionListener` captures request latency and status.
Storage metrics via RocksDB property API + `Partition` counters.
Suggested metric set:
```
q1_requests_total{method, status_class}        # 2xx/4xx/5xx buckets
q1_request_duration_seconds{method, quantile}  # P50/P95/P99 via summary
q1_segment_count{partition}
q1_live_keys{partition}
q1_dead_bytes_ratio{partition}                 # triggers compaction above threshold
q1_compaction_runs_total{result}               # "compacted" | "skipped" | "noop"
q1_compaction_bytes_reclaimed_total
q1_raft_term                                   # from RaftServer.getDivision().getInfo()
q1_raft_commit_index
q1_ec_repair_shards_reconstructed_total
```
Expose on `GET /metrics` (Prometheus text format, no dependency on Micrometer).

**Merge compaction (multi-segment)**
Current compaction handles one segment at a time (worst dead-ratio first).
After many passes, many small compacted segments accumulate → more open file descriptors,
worse read locality for large values that span multiple segments.
A merge pass selects N consecutive small sealed segments and rewrites them as one:
same two-phase structure (write `.compact`, then atomic rename + RocksDB batch).
Trigger condition: `sealedCount > Q1_MERGE_THRESHOLD` (default 8) AND total size of
candidates < `Q1_MERGE_MAX_BYTES` (default 64 MiB, to bound lock hold time).

**Read-ahead buffer for large GETs**
`Segment.read()` does one `FileIO.read` per virtual-thread GET.
For objects >= 64 KiB, `FileChannel.read` with `O_DIRECT` alignment (4 KiB multiples)
would reduce kernel buffer pressure. For 128 KiB objects (the workload sweet spot),
a single aligned read covers the entire value.
Prerequisite: align `valueOffset` to the nearest 4 KiB boundary at write time
(insert at most 4095 bytes of padding before the value). Cost: ≤ 3 % space overhead.
Not urgent until `/metrics` shows read latency as the bottleneck.

---

### 🏗️ Long term

**Elastic EC re-encoding**
When a node joins or leaves, existing shard placement is stale.
Background scanner (similar to `EcRepairScanner`) detects objects whose
`computeShardPlacement` result changed, re-encodes, and pushes new shards.
Depends on dynamic cluster membership (Ratis `setConfiguration()`).

**Persistent object metadata (ETag, Content-Type, Last-Modified)**
Required for `s3cmd sync` and proper HEAD responses.
Preferred approach: second RocksDB key prefix `\x01{internalKey}` →
`{size:8B}{etag:16B}{contentType:N}`, written atomically with the data entry.
Enables EC size in listings without reading shard headers.

**AWS Signature V4**
Validate `Authorization: AWS4-HMAC-SHA256` against a configured key list.

**TLS**
Undertow `XnioSsl` + `Q1_TLS_CERT` / `Q1_TLS_KEY`.

**Multipart upload**
S3 three-step protocol for objects > 5 GB.
Intermediate parts stored under `__q1_mpu__/{uploadId}/{partNumber}`.

---

## Out of scope

- S3 object versioning, Tagging, ACLs, Select
- ListObjectsV2 pathological delimiter edge cases
- io_uring (Panama FFI) — NIO FileChannel sufficient for 128 KB workload
