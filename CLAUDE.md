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
  q1-uring/    # Optional io_uring backend via Panama FFI
  q1-cluster/  # etcd-based leader election + HTTP replication
  q1-api/      # Undertow HTTP server (S3-compatible surface)
  q1-tests/    # Integration & cluster tests (AWS SDK compliance)
```

## Core Storage Design

### Segment files

Append-only files at `dataDir/p{N}/segment-{0000000001}.q1`. Roll over at 1 GiB.

Record layout (19-byte fixed header + variable body):
```
[4B] MAGIC    0x51310001
[1B] FLAGS    0x00=DATA | 0x01=TOMBSTONE
[2B] KEY_LEN  unsigned short
[8B] VAL_LEN  long (0 for tombstones)
[4B] CRC32    covers flags + key bytes + value bytes
[KEY_LEN B]   key (UTF-8)
[VAL_LEN B]   value bytes (absent for tombstones)
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

`FileIO` / `FileIOFactory` interfaces allow swapping the I/O backend transparently:
- `NioFileIOFactory` (default) — Java NIO `FileChannel`
- `UringFileIOFactory` (`q1-uring`) — Panama FFI → liburing

The factory is injected at `StorageEngine` construction; `Partition` passes it down to each
new `Segment`.

## Cluster Design

### Leader election (etcd)

- One lease per node (TTL 10s, auto-renewed via keepalive)
- Each partition has an etcd key `/q1/partitions/{id}/leader` (value = `nodeId:host:port`)
- Election: conditional `PUT IF VERSION == 0` — only one node wins, preventing split-brain
- etcd watches keep each node's in-memory `partitionLeaders` map current
- Node registration: `/q1/nodes/{nodeId}` (ephemeral, cleaned up on lease expiry)

### Write path (synchronous replication)

On a leader PUT/DELETE:
1. Write locally (segment append)
2. Fan out to RF-1 followers in parallel via HTTP with header `X-Q1-Replica-Write: true`
3. Await all acks before responding to the client (strong durability)

The replica header prevents followers from re-replicating on receipt.

Non-leader nodes return **307 Temporary Redirect** (preserves HTTP method) pointing to
`leaderBaseUrl + path`, so clients retry on the correct node.

### Follower catchup on start

Before accepting traffic a newly started node syncs lagging partitions:
1. Wait up to 3 s for leader elections to settle
2. For each non-leader partition: `GET /internal/v1/sync/{partitionId}?segment={s}&offset={o}`
3. Leader streams raw segment-record bytes (200) or signals "already current" (204)
4. Follower parses via `Segment.scanStream()` and applies each record with `put`/`delete`

Failed catchups are logged and skipped — the node still starts, live replication keeps it current.

### Standalone mode

`Q1_ETCD` absent → no cluster logic, all requests served locally (single-node default).

## Environment Variables

| Variable        | Default                | Description                    |
|-----------------|------------------------|--------------------------------|
| `Q1_NODE_ID`    | `node-{random8}`       | Unique node name               |
| `Q1_HOST`       | `localhost`            | Advertised hostname/IP         |
| `Q1_PORT`       | `9000`                 | HTTP listen port               |
| `Q1_DATA_DIR`   | `q1-data`              | Data directory                 |
| `Q1_ETCD`       | _(empty=standalone)_   | Comma-separated etcd endpoints |
| `Q1_RF`         | `1`                    | Replication factor             |
| `Q1_PARTITIONS` | `16`                   | Number of partitions           |

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

# All tests including cluster (requires Docker for etcd container)
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
| `ClusterIT` | 2-node cluster via Testcontainers (bitnami/etcd): replication, 307 redirect, delete propagation |

## TODO / Roadmap

- [ ] Segment compaction (configurable tombstone-ratio threshold)
- [ ] CRC verification on reads (header already stores CRC, parsing skips it today)
- [ ] io_uring integration tests (`UringFileIO`)
- [ ] Erasure coding (optional, post-RF work)
- [ ] `ListObjectsV2` pagination with continuation tokens
- [ ] Metrics / observability endpoint
- [ ] Multi-part upload (for objects > 5 GB)
