# q1-cluster — Cluster Coordination

Depends only on `q1-core`. No dependency on `q1-api`.

## Responsibilities

- Per-partition Raft consensus via Apache Ratis (embedded, no external coordinator)
- Distributed leadership: each partition group independently elects its own leader
- Write replication through the Raft log (gRPC, inter-node)
- Request routing: non-leader replicas proxy writes transparently to the partition leader (client sees 200, not 307)

## Architecture: per-partition Raft groups

One `RaftServer` per JVM hosts P+1 Raft groups:

- **P partition groups** — each covers one partition; RF replicas chosen round-robin
  over the sorted peer list; each elects its own leader independently.
- **1 metadata group** — RF=N (all nodes); handles bucket CREATE/DELETE.

With RF < N, writes are balanced: each node leads P/N partitions on average.
Quorum per group = ⌊RF/2⌋+1.

## Key classes

| File | Role |
|---|---|
| `RatisCluster.java` | One `RaftServer` + per-group `RaftClient` map; `submit()`, `isLocalLeader(p)`, `leaderHttpBaseUrl(p)` |
| `Q1StateMachine.java` | `BaseStateMachine` impl — applies Raft log entries to `StorageEngine`; snapshots |
| `RatisCommand.java` | Binary encoding of mutations (PUT, DELETE, CREATE_BUCKET, DELETE_BUCKET) |
| `PartitionRouter.java` | Routes to partition-group leader for object ops, meta-group leader for bucket ops |
| `NodeId.java` | `record(id, host, httpPort, raftPort, grpcPort)` |
| `ClusterConfig.java` | Immutable config: `self`, `peers`, `numPartitions`, `raftDataDir`, `ecConfig`, `raftGroupId`, `rf`, `maxObjectSizeMb` |

## RatisCommand binary format

```
[1B]  type  0x01=PUT  0x02=DELETE  0x03=CREATE_BUCKET  0x04=DELETE_BUCKET
[2B]  bucket length (unsigned short)
[N B] bucket (UTF-8)
[2B]  key length (unsigned short)     — absent for CREATE/DELETE_BUCKET
[N B] key (UTF-8)                     — absent for CREATE/DELETE_BUCKET
[8B]  value length (long)             — PUT only
[N B] value bytes                     — PUT only
```

## Write path

```
PUT /bucket/key (any node)
  ├── partitionId = abs(fullKey.hashCode()) % P
  ├── if not partition leader → proxyToLeader(partitionId) [transparent HTTP forward, client sees 200]
  └── if partition leader → cluster.submit(RatisCommand.put(...))
                              → Raft commit (quorum ACK via gRPC)
                              → Q1StateMachine.applyTransaction() on all RF replicas
                              → engine.put(bucket, key, value) on each

Bucket ops (CREATE/DELETE) follow the same pattern via the metadata group.
```

No HTTP fan-out, no `X-Q1-Replica-Write` header. Raft handles replication.

## Snapshots

`Q1StateMachine` implements `takeSnapshot()` using `SimpleStateMachineStorage`:
- Snapshot = empty marker file; term+index encoded in filename (e.g. `snapshot.3_10000`)
- `engine.sync()` called before snapshot to guarantee all applied writes are on disk
- On restart, `initialize()` fast-forwards to the snapshot's (term, index); only subsequent
  log entries are replayed
- Auto-trigger configured in `RatisCluster`: every 10 000 committed entries per group

## Startup

```java
RatisCluster cluster = new RatisCluster(cfg, engine);  // creates per-group state machines internally
cluster.start();   // RaftServer starts; groups added via groupManagement(); elections run
server.start();    // HTTP port opened once isClusterReady() (all groups have a known leader)
```

On restart, Ratis loads the latest snapshot per group then replays only subsequent entries.
Groups already recovered from disk are skipped in `groupManagement` (checked via `server.getGroupIds()`).

## Environment variables

| Variable | Default | Description |
|---|---|---|
| `Q1_NODE_ID` | `node-{random8}` | Unique node identifier (must match a peer ID in `Q1_PEERS`) |
| `Q1_HOST` | `localhost` | Advertised HTTP hostname/IP |
| `Q1_PORT` | `9000` | HTTP port |
| `Q1_RAFT_PORT` | `6000` | Raft gRPC port |
| `Q1_RAFT_GROUP_ID` | _(hardcoded UUID)_ | Namespace UUID for deriving all group IDs (tests use `ClusterConfig.raftGroupId`) |
| `Q1_PEERS` | _(empty = standalone)_ | `id\|host\|httpPort\|raftPort\|grpcPort` per node, comma-separated |
| `Q1_PARTITIONS` | `16` | Number of partitions (= number of partition Raft groups) |
| `Q1_RF` | _(all peers)_ | Replication factor per partition; set < N to distribute leadership |
| `Q1_MAX_OBJECT_SIZE` | `32MB` | Max object size; drives Raft `appenderBufferByteLimit` and gRPC message cap |

Example `Q1_PEERS`:
```
node1|10.0.0.1|9000|6000|7000,node2|10.0.0.2|9000|6000|7000,node3|10.0.0.3|9000|6000|7000
```

## Ratis internals

- Group IDs derived from namespace UUID: `partGroup(p) = UUID(msb, p)`, `metaGroup = UUID(msb, -1L)`
- `RaftPeerId`: `nodeId` (string)
- `RaftPeer`: `(id, host:raftPort)` — gRPC transport
- Raft log + snapshots stored under `raftDataDir/<groupId>/` (one sub-directory per group)
- `RECOVER` startup option loads existing groups; `FORMAT` only for explicit reset

## TODO

- [ ] Dynamic membership (`setConfiguration`) for elastic cluster resize
- [ ] Metrics: Raft term, commit index, replication lag exposed in `/metrics`
