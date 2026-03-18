# Q1 — S3-compatible Object Store

> S3 is Amazon's, R2 is Cloudflare's, Q1 is ours.

## Workload Target

- 60 million email messages; ~80% under 128 KB
- Focus on efficient I/O: 128 KB is the sweet spot (not 4 KB)
- Operations: GET, PUT, HEAD, DELETE on objects + bucket CRUD (list/create/delete)
- Keys may contain `/` but it has no filesystem meaning

## Module Structure

```
q1/
  q1-core/     # Storage engine: segments, partitions, index, sync API
  q1-cluster/  # Raft consensus (Apache Ratis) + request routing
  q1-api/      # Undertow HTTP server (S3-compatible surface)
  q1-tests/    # Integration & cluster tests (AWS SDK compliance)
```

## Core Storage Design

### Segment files

Append-only files at `dataDir/p{N}/segment-{0000000001}.q1`. Roll over at 1 GiB.

Record layout (19-byte fixed header + variable body):
```
[4B] MAGIC    0x51310002
[1B] FLAGS    0x00=DATA | 0x01=TOMBSTONE
[2B] KEY_LEN  unsigned short
[8B] VAL_LEN  long (0 for tombstones)
[4B] CRC32    covers flags + key bytes + value bytes  (header CRC)
[KEY_LEN B]   key (UTF-8)
[VAL_LEN B]   value bytes (absent for tombstones)
[4B] CRC32    same value repeated                     (footer CRC)
```

The in-memory `SegmentIndex` maps each live key to `(segmentId, valueOffset, valueLength)`.
Deletes write a tombstone and remove the index entry; the dead bytes are reclaimed at compaction.

### Internal key format

`bucket\x00objectKey` — the null byte is the separator (cannot appear in valid S3 keys, so the
concatenation is unambiguous).

### Partitioning

16 partitions by default (configurable via `Q1_PARTITIONS`).
Routing: `Math.abs(fullKey.hashCode()) % numPartitions`.
The `StorageEngine` owns `N` `Partition` objects and routes every operation to the right one.

### I/O abstraction

`FileIO` / `FileIOFactory` interfaces allow swapping the I/O backend transparently.
The default implementation is `NioFileIOFactory` (Java NIO `FileChannel`).
The factory is injected at `StorageEngine` construction; `Partition` passes it down to each
new `Segment`.

## Cluster Design

### Leader election (Apache Ratis / Raft)

- One `RaftServer` per JVM hosts P partition groups + 1 metadata group
- Each group independently elects its own leader — writes distributed across nodes
- RF replicas per partition group (default: all peers); metadata group always RF=N
- Quorum per group = ⌊RF/2⌋+1
- Raft log stored under `$Q1_DATA_DIR/raft/<groupId>/`; replayed on restart (no manual catchup)

### Write path (Raft replication)

On any PUT/DELETE reaching the leader:
1. `cluster.submit(RatisCommand)` — blocks until committed by a quorum
2. `Q1StateMachine.applyTransaction()` runs on every node, writing to the local `StorageEngine`

Non-leader nodes **proxy writes transparently** to the leader via an internal HTTP forward.
The client always sees 200 directly — no 307 is exposed.

### Standalone mode

`Q1_PEERS` absent → no cluster logic, all requests served locally (single-node default).

## Environment Variables

| Variable             | Default              | Description                                                        |
|----------------------|----------------------|--------------------------------------------------------------------|
| `Q1_NODE_ID`         | `node-{random8}`     | Unique node name (must match an ID in `Q1_PEERS`)                  |
| `Q1_HOST`            | `localhost`          | Advertised HTTP hostname/IP                                        |
| `Q1_PORT`            | `9000`               | HTTP listen port                                                   |
| `Q1_DATA_DIR`        | `q1-data`            | Data directory                                                     |
| `Q1_PEERS`           | _(empty=standalone)_ | `id\|host\|httpPort\|raftPort\|grpcPort` per node, comma-separated |
| `Q1_RAFT_PORT`       | `6000`               | Raft gRPC port (inter-node)                                        |
| `Q1_GRPC_PORT`       | `7000`               | Internal gRPC API port                                             |
| `Q1_PARTITIONS`      | `16`                 | Number of partitions (= number of partition Raft groups)           |
| `Q1_RF`              | _(all peers)_        | Replication factor per partition group                             |
| `Q1_MAX_OBJECT_SIZE` | `32MB`               | Max object size; drives Raft buffer limits and gRPC message cap    |

## Data Directory Layout

```
dataDir/
  buckets.properties       # bucket registry (creation timestamps)
  p00/
    segment-0000000001.q1
    segment-0000000002.q1
    …
  p01/  …
  p15/
```

## Build & Run

```bash
# Build (skip tests)
mvn package -DskipTests

# Run standalone
java --enable-preview --enable-native-access=ALL-UNNAMED \
     -jar q1-api/target/q1-api-*.jar

# S3 compliance tests (standalone, in-process)
mvn verify -pl q1-tests

# All tests including cluster (Ratis in-process, no Docker required)
mvn verify -pl q1-tests -Pcluster-tests
```

## Test Plan

### Unit tests — `q1-core/src/test`

| Class | What it covers |
|---|---|
| `SegmentTest` | `append`/`read` round-trip, tombstone scan, `scanStream` from file, truncated-stream safety |
| `PartitionTest` | CRUD, overwrite, prefix listing, sync state tracking, sync stream round-trip |
| `StorageEngineSyncTest` | Full `openSyncStream → applySyncStream` across two engines, including deletes |

### S3 compliance — `q1-tests/src/test` (`*IT` via Failsafe)

| Class | What it covers |
|---|---|
| `S3CompatibilityIT` | AWS SDK v2 driving all supported ops against an in-process standalone server |
| `ClusterIT` | 2-node Ratis cluster: replication, transparent proxy, delete propagation |
| `ClusterReplicaIT` | 3-node Ratis cluster: distributed leaders, writes to any node, delete replication |
| `RestartResilienceIT` | Follower restart, writes-while-down catch-up, leader failover, snapshot recovery |
| `EcClusterIT` | 3-node EC(2+1): encode/decode, single-shard loss reconstruction |
| `HealthzIT` | `/healthz` in standalone and cluster mode |

## TODO / Roadmap

- [x] Segment compaction (two-phase, crash-safe; see COMPACTION.md)
- [x] CRC verification on reads (verified in scan() and scanStream())
- [x] Erasure coding (Reed-Solomon k+m, repair scanner; see ERASURECODING.md)
- [x] `ListObjectsV2` with continuation tokens, delimiter, CommonPrefixes
- [x] Raft snapshots (takeSnapshot + auto-trigger; bounded restart replay)
- [x] `/healthz` endpoint (JSON, 200/503)
- [ ] CRC32 on direct GET path (`Segment.read()`)
- [ ] Persistent object metadata (ETag, Content-Type, Last-Modified, Size)
- [ ] Metrics / observability endpoint (`/metrics` Prometheus)
- [ ] Multi-part upload (for objects > 5 GB)
- [ ] AWS Signature V4 validation
- [ ] Dynamic cluster membership (Ratis `setConfiguration`)
