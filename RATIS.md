# Raft in Q1 — Current Architecture

Apache Ratis (embedded JVM Raft) replaced etcd since the Q1 v0.1 migration.
This document describes the current state, not the historical migration.

---

## What Raft does in Q1

### Replication mode (EC disabled)

Raft is **on the critical write path**.

```
PUT /bucket/key
  ├── follower → proxyToLeader()  [transparent HTTP proxy, client sees 200]
  └── leader  → cluster.submit(RatisCommand.put/delete)
                  → Raft commit (quorum gRPC)
                  → Q1StateMachine.applyTransaction() on every node
                  → engine.put() locally on every node
```

What Raft provides in replication mode:
- **Replication**: the log replaces the old `HttpReplicator` (manual HTTP fan-out)
- **Consensus**: a single leader absorbs all writes, guarantees linearizability
- **Restart catch-up**: replays the log from the last applied snapshot
  (replaces `CatchupManager` + `/internal/v1/sync/` endpoint)
- **Split-brain prevention**: writes are impossible without quorum (⌊N/2⌋+1)

### EC mode (Q1_EC_K > 0)

Raft is **off the data path**.

```
PUT /bucket/key  (any node)
  → encode(body) → k data shards + m parity shards  (local, pure CPU)
  → HTTP fan-out to each ring node via HttpShardClient
  → no cluster.submit(), nothing written to the Raft log
```

Raft provides only:
- **Topology**: peer list (`config.peers()`) for `computeShardPlacement`
- **Bucket operations**: `CREATE_BUCKET` / `DELETE_BUCKET` go through the log
  (the only `RatisCommand` types used in EC mode)
- **`isClusterReady()`**: checks that `activeNodes >= k`

The notion of "leader" is irrelevant for objects in EC mode.
Any node can receive and handle a PUT or DELETE.

---

## What Raft does NOT do in Q1

| Feature | Status | Alternative |
|---|---|---|
| Dynamic reconfiguration | ❌ static cluster | `Q1_PEERS` fixed at startup |
| Per-partition election | N/A | 1 global group, 1 single leader |
| Per-partition routing | N/A | `PartitionRouter` delegates to global `isLocalLeader()` |

---

## Raft group

- **1 global group** for all 16 partitions (no per-partition group)
- `RaftGroupId`: UUID from `ClusterConfig.raftGroupId()`, then `Q1_RAFT_GROUP_ID` env, then hardcoded default
- `RaftPeerId`: `Q1_NODE_ID`
- `RaftPeer`: `host:raftPort` (gRPC)
- Log + snapshots stored under `raftDataDir` (configurable, typically `$Q1_DATA_DIR/raft/`)
- Quorum: ⌊N/2⌋+1 (e.g. 3 nodes → quorum 2, 5 nodes → quorum 3)

---

## `RatisCommand` — binary format

The only mutation channel passing through the log.

```
[1B]  type    0x01=PUT  0x02=DELETE  0x03=CREATE_BUCKET  0x04=DELETE_BUCKET
[2B]  bucket length (unsigned short)
[N B] bucket (UTF-8)
[2B]  key length (unsigned short)     — absent for CREATE/DELETE_BUCKET
[N B] key (UTF-8)                     — absent for CREATE/DELETE_BUCKET
[8B]  value length (long)             — PUT only
[N B] value bytes                     — PUT only
```

In EC mode, only `CREATE_BUCKET` and `DELETE_BUCKET` use this channel.
Objects never pass through the Raft log in EC mode.

---

## `Q1StateMachine` — state machine

```java
applyTransaction(trx):
  switch cmd.type():
    PUT           → engine.put(bucket, key, value)
    DELETE        → engine.delete(bucket, key)
    CREATE_BUCKET → engine.createBucket(bucket)
    DELETE_BUCKET → engine.deleteBucket(bucket)
```

### Snapshots

Implemented via `SimpleStateMachineStorage`:

- `takeSnapshot()`:
  1. Calls `engine.sync()` (fsync all active segments via `Partition.force()`)
  2. Creates an empty marker file via `snapshotStorage.getSnapshotFile(term, index)`
     (term+index encoded in filename, e.g. `snapshot.3_10000`)
  3. Updates `snapshotStorage.updateLatestSnapshot()`
  4. Returns the snapshot index (Ratis can purge the log up to this index)

- `initialize()`:
  1. Calls `snapshotStorage.init(storage)`
  2. If a snapshot exists, calls `updateLastAppliedTermIndex(term, index)` to
     fast-forward to the right point — Ratis only replays entries after it

- Auto-trigger: every 10 000 committed entries (configurable via
  `RaftServerConfigKeys.Snapshot.setAutoTriggerThreshold`)

Why the marker file is empty: the `StorageEngine` state (segments + RocksDB) is already
durably persisted on disk independently of Raft. The snapshot only tells Ratis at which
(term, index) that state is consistent.

---

## Startup

```
RatisCluster.start()
  → RaftServer starts (RECOVER if raftDir non-empty, FORMAT on first boot)
  → automatic leader election (no explicit waitForLeader call)
  → first leader elected in < 1s on a local network

Q1Server.start()
  → HTTP port opened as soon as Ratis is ready
  → isClusterReady() blocks client requests if not enough active nodes
```

A restarted follower loads the latest snapshot, then replays only the Raft entries that
follow it — no `CatchupManager`, no `/internal/v1/sync/` endpoint.

---

## Environment variables

| Variable | Default | Description |
|---|---|---|
| `Q1_PEERS` | _(absent = standalone)_ | `id\|host\|httpPort\|raftPort` per node, comma-separated |
| `Q1_RAFT_PORT` | `6000` | Raft gRPC inter-node port |
| `Q1_NODE_ID` | `node-{random8}` | Must match an ID in `Q1_PEERS` |
| `Q1_RAFT_GROUP_ID` | _(hardcoded UUID)_ | Override the Raft group UUID |

---

## Known limitations

### Static cluster
`Q1_PEERS` is read at startup and does not change at runtime.
Ratis supports `setConfiguration()` to add/remove nodes live, but this is not wired yet.

### No Raft metrics exposed
Current Raft term, commit index, and replication lag are not surfaced in `/metrics`
or `/healthz`.
