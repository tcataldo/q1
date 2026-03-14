# q1-cluster — Cluster Coordination

Depends only on `q1-core`. No dependency on `q1-api`.

## Responsibilities

- Single-group Raft consensus via Apache Ratis (embedded, no external coordinator)
- Global leader election (one leader for all 16 partitions)
- Write replication through the Raft log (gRPC, inter-node)
- Request routing: non-leader nodes proxy writes transparently to the leader (client sees 200, not 307)

## Architecture: 1 global Raft group

One Raft group for all 16 partitions. A single leader absorbs all writes cluster-wide.
Quorum = ⌊N/2⌋+1.

## Key classes

| File | Role |
|---|---|
| `RatisCluster.java` | Lifecycle of `RaftServer` + `RaftClient`; `submit()`, `isLocalLeader()`, `leaderHttpBaseUrl()` |
| `Q1StateMachine.java` | `BaseStateMachine` impl — applies Raft log entries to `StorageEngine`; snapshots |
| `RatisCommand.java` | Binary encoding of mutations (PUT, DELETE, CREATE_BUCKET, DELETE_BUCKET) |
| `PartitionRouter.java` | `leaderBaseUrl(bucket, key)` → delegates to `RatisCluster.leaderHttpBaseUrl()` |
| `NodeId.java` | `record(id, host, httpPort, raftPort)` |
| `ClusterConfig.java` | Immutable config: `self`, `peers`, `numPartitions`, `raftDataDir`, `ecConfig`, `raftGroupId` |

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
  ├── if not leader → proxyToLeader() [transparent HTTP forward, client sees 200]
  └── if leader → cluster.submit(RatisCommand.put(...))
                    → Raft commit (majority ACK via gRPC)
                    → Q1StateMachine.applyTransaction() on all nodes
                    → engine.put(bucket, key, value) on each
```

No HTTP fan-out, no `X-Q1-Replica-Write` header. Raft handles replication.

## Snapshots

`Q1StateMachine` implements `takeSnapshot()` using `SimpleStateMachineStorage`:
- Snapshot = empty marker file; term+index encoded in filename (e.g. `snapshot.3_10000`)
- `engine.sync()` called before snapshot to guarantee all applied writes are on disk
- On restart, `initialize()` fast-forwards to the snapshot's (term, index); only subsequent
  log entries are replayed
- Auto-trigger configured in `RatisCluster`: every 10 000 committed entries

## Startup

```
RatisCluster cluster = new RatisCluster(cfg, stateMachine);
cluster.start();   // RaftServer starts, election runs automatically
server.start();    // HTTP open once Raft is ready
```

On restart, Ratis loads the latest snapshot then replays only the entries that follow it.

## Environment variables

| Variable | Default | Description |
|---|---|---|
| `Q1_NODE_ID` | `node-{random8}` | Unique node identifier (must match a peer ID in `Q1_PEERS`) |
| `Q1_HOST` | `localhost` | Advertised HTTP hostname/IP |
| `Q1_PORT` | `9000` | HTTP port |
| `Q1_RAFT_PORT` | `6000` | Raft gRPC port |
| `Q1_RAFT_GROUP_ID` | _(hardcoded UUID)_ | Override Raft group UUID (tests use `ClusterConfig.raftGroupId`) |
| `Q1_PEERS` | _(empty = standalone)_ | `id\|host\|httpPort\|raftPort` per node, comma-separated |
| `Q1_PARTITIONS` | `16` | Number of partitions |

Example `Q1_PEERS`:
```
node1|10.0.0.1|9000|6000,node2|10.0.0.2|9000|6000,node3|10.0.0.3|9000|6000
```

## Ratis internals

- `RaftGroupId`: UUID from `ClusterConfig.raftGroupId()`, then `Q1_RAFT_GROUP_ID` env, then hardcoded default
- `RaftPeerId`: `nodeId` (string)
- `RaftPeer`: `(id, host:raftPort)` — gRPC transport
- Raft log + snapshots stored under `raftDataDir` (configurable per node)
- `RECOVER` startup option when the raft directory already exists; `FORMAT` on first start

## TODO

- [ ] Dynamic membership (`setConfiguration`) for elastic cluster resize
- [ ] Metrics: Raft term, commit index, replication lag exposed in `/metrics`
