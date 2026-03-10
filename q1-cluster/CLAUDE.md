# q1-cluster — Cluster Coordination

Depends only on `q1-core`. No dependency on `q1-api`.

## Responsibilities

- Per-partition leader election via etcd
- Active node registration (presence)
- Synchronous write replication to followers
- Request routing to the correct leader node

## etcd topology

```
/q1/nodes/{nodeId}              →  "id:host:port"            (ephemeral, tied to lease)
/q1/partitions/{id}/leader      →  "id:host:port"            (ephemeral, election winner)
/q1/partitions/{id}/replicas    →  "id:host:port,…"          (ephemeral, written by the leader)
```

## Per-partition election

Conditional transaction: `PUT IF VERSION == 0`. Only one node can write the leader key at a
time (the lease guarantees exclusivity). If the leader goes down, etcd deletes the key and
another node can win.

## Replica assignment (ring)

After winning election for a partition, the leader computes its RF-1 replicas via a
**ring** over active nodes sorted by `nodeId`: the RF-1 nodes immediately following in the
ring (wrapping around). Written to `/q1/partitions/{id}/replicas` tied to the lease.

```
Ring [A, B, C], RF=2:  A leads → replica B | B leads → replica C | C leads → replica A
```

Advantage: each node stores exactly `RF/N` of the data (balanced), unlike "first sorted"
which overloads the lexicographically smallest node.

When a node joins or leaves (`watchNodes`), the leader recomputes and rewrites the assignment
for all partitions it leads (`refreshReplicasForLeadPartitions`).

## Replication (HttpReplicator)

1. Leader writes locally
2. Parallel fan-out to the RF-1 nodes in `partitionReplicas` with header `X-Q1-Replica-Write: true`
3. Waits for all ACKs before responding to the client (strong durability)

The `X-Q1-Replica-Write` header prevents followers from re-replicating.
Non-leaders return **307 Temporary Redirect** to the leader.

## Startup catchup (CatchupManager)

1. Wait up to 3s for elections and assignments to settle
2. For each partition where `isAssignedReplica()` is true: `GET /internal/v1/sync/{p}?segment={s}&offset={o}`
3. 200 → raw byte stream to apply via `Segment.scanStream()` + `engine.applySyncStream()`
4. 204 → already up to date
5. Non-assigned partitions → skipped (this node should not have them)
6. Failure → logged, node starts anyway

## Key files

| File | Role |
|---|---|
| `EtcdCluster.java` | Lease, keepalive, election, watches for leader changes |
| `PartitionRouter.java` | Same hash as `StorageEngine`; `leaderBaseUrl()`, `followersFor()` |
| `HttpReplicator.java` | Async HTTP fan-out to followers, `X-Q1-Replica-Write` header |
| `CatchupManager.java` | Sync of lagging partitions on node startup |
| `NodeId.java` | `record(id, host, port)`; serialization `id:host:port` |
| `ClusterConfig.java` | Immutable config with builder |

## Environment variables (startup via Q1Server)

| Variable | Default | Description |
|---|---|---|
| `Q1_NODE_ID` | `node-{random8}` | Unique node identifier |
| `Q1_HOST` | `localhost` | Advertised hostname/IP |
| `Q1_PORT` | `9000` | HTTP port |
| `Q1_ETCD` | _(empty = standalone)_ | Comma-separated etcd endpoints |
| `Q1_RF` | `1` | Replication factor |
| `Q1_PARTITIONS` | `16` | Number of partitions |

## jetcd 0.7.7 API — gotchas

- `CmpTarget.version(0L)` — factory method, not a `VERSION` constant
- `keepAlive(long, StreamObserver<LeaseKeepAliveResponse>)` — takes a `StreamObserver`
- `CloseableClient` is in `io.etcd.jetcd.support` (not covered by `io.etcd.jetcd.*`)

## TODO

- [ ] Replication of bucket operations (create/delete bucket is not replicated)
- [ ] Optional async replication (eventually consistent mode with configurable RF)
- [ ] Back-pressure if followers are too slow
- [ ] Metrics: replication lag, number of elections won/lost
