# Q1 — Status and TODO

## Current state (March 2026)

### Storage
- Append-only segments + persistent RocksDB index (no rescan on startup)
- Two-phase crash-safe compaction (configurable threshold, summary log after each pass)
- CRC32 verified on `scan()` and `scanStream()` — **not on direct GET** (`Segment.read()`)
- 16 partitions by default, routed by `hashCode() % N`

### Cluster
- Embedded Apache Ratis (Raft, no external coordinator)
- Replication mode: Raft log on write path, transparent proxy to leader (client sees 200, never 307)
- EC mode (`Q1_EC_K > 0`): direct HTTP fan-out, Raft off the data path; any node accepts PUT/DELETE
- Bucket CREATE/DELETE go through the Raft log in both modes
- **Raft snapshots implemented**: `takeSnapshot()` + `initialize()` with `SimpleStateMachineStorage`;
  empty marker file (term+index in filename), preceded by `engine.sync()`;
  auto-trigger every 10 000 committed entries; on restart only entries after the snapshot are replayed

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
- EC listing transparent: EC objects appear in normal S3 listings
- Bucket list, create, delete
- `GET /healthz`: JSON `{nodeId, mode, status, partitions}` + cluster fields if applicable; 200/503
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
| q1-tests | IT | `S3CompatibilityIT` | 12 |
| q1-tests | IT | `ClusterIT` | 5 |
| q1-tests | IT | `ClusterReplicaIT` | 4 |
| q1-tests | IT | `RestartResilienceIT` | 4 |
| q1-tests | IT | `EcClusterIT` | 4 |
| q1-tests | IT | `HealthzIT` | 5 |

### Not implemented
- Dynamic cluster membership (static topology via `Q1_PEERS`)
- AWS Signature V4 (any credentials accepted)
- TLS
- Multipart upload
- Persistent object metadata: ETag, Content-Type, Last-Modified, real Size in EC listing
- Prometheus metrics endpoint (`/metrics`)
- Elastic EC re-encoding (add/remove nodes without restart)

---

## TODO — by priority

### ⚡ Quick wins (a few hours each)

**CRC32 on direct GET**
`Segment.read()` (called on every GET) does not verify the CRC32 stored in the header.
Fix: store `keyLen` in `RocksDbIndex.Entry` (20 → 22 bytes, big-endian short) to
compute `headerOffset = valueOffset - HEADER_SIZE - keyLen`, read 4B CRC, verify, reject on mismatch.
Current risk: silent corruption between two compaction passes.

**Real object size in listings (non-EC mode)**
`RocksDbIndex.Entry` already holds `valueLength`. Just surface it through
`listPaginated()` → `ListResult` → XML `<Size>`. EC size requires persistent metadata
(see below); start with non-EC.
`s3cmd ls` currently shows 0 for all objects.

**Filter `__q1_ec_shards__` from visible errors**
The internal bucket does not appear in `listBuckets` (not in `BucketRegistry`) but a
client hitting `GET /__q1_ec_shards__/` gets `404 NoSuchBucket`. Add an explicit check
and return `403 AccessDenied` for any bucket prefixed with `__q1_`.

---

### 📦 Short term (1–3 days)

**Persistent object metadata**
ETag, Content-Type, Last-Modified, and real object size are missing from HEAD and listings.
Preferred approach: dedicated RocksDB column (or key prefix `0x01{internalKey}` →
`{size:8B}{etag:16B}{contentType:N}`).
- Enables ETag + Size in listings without reading segments
- In EC mode, the shard `put()` also writes metadata → HEAD no longer needs a shard fetch
- Blocking for `s3cmd sync` which relies on ETag+Size to detect changes

**EC fault tolerance test**
`EcRepairScanner` is implemented but tested only on the happy path.
Write an IT that stops a node, verifies that GETs reconstruct correctly,
restarts the node, and verifies the repair scanner pushes back the missing shards.
`RestartResilienceIT` already covers replication mode — same structure, adapt for EC.

---

### 🔧 Medium term (1–2 weeks)

**Prometheus `/metrics`**
```
q1_requests_total{method, status}
q1_request_duration_seconds{method}  (histogram)
q1_segment_count{partition}
q1_live_keys{partition}
q1_compaction_runs_total{partition, result}
q1_compaction_bytes_reclaimed_total
q1_raft_term
q1_raft_commit_index
q1_ec_repair_shards_reconstructed_total
```
Undertow has an `ExchangeCompletionListener` hook for latency and status capture.
Storage metrics come from RocksDB stats + `Partition.segmentCount()`.

**Dynamic cluster reconfiguration**
Ratis supports `RaftClient.admin().setConfiguration(peers)` to add/remove nodes without
restart. Wire it to an admin endpoint `PUT /internal/v1/cluster/peers`.
Prerequisite for elastic EC re-encoding.

**Merge compaction (multi-segment)**
Current compaction handles one segment at a time. After many passes, small segments
accumulate. A merge pass combines N segments → 1, reducing open file descriptors and
improving sequential locality. Same two-phase structure, N sources → 1 target.

---

### 🏗️ Long term

**Elastic EC re-encoding**
When a node joins or leaves, existing shards are not redistributed.
The ring changes → `computeShardPlacement` returns new nodes → old shards are "orphaned".
Solution: trigger background re-encoding (similar to the repair scanner) for all objects
whose placement has changed. Depends on dynamic membership.

**AWS Signature V4**
Validate `Authorization: AWS4-HMAC-SHA256` against a configured key list.
Allows integrating Q1 into existing pipelines without modifying clients.

**TLS**
Undertow supports HTTPS natively. Add `Q1_TLS_CERT` / `Q1_TLS_KEY`
and configure the `XnioSsl` channel listener.

**Multipart upload**
Required for objects > 5 GB. S3 three-step protocol:
`CreateMultipartUpload` → N × `UploadPart` → `CompleteMultipartUpload`.
Intermediate part storage in an internal bucket `__q1_mpu__`.

---

## Out of scope

- ListObjectsV2 pagination edge cases with delimiter and heavy CommonPrefixes collapsing
- S3 object versioning
- S3 Select / Tagging / ACLs
- io_uring (Panama FFI) — NIO FileChannel is sufficient for 128 KB workloads
