# q1-cluster — Cluster Coordination

Depends only on `q1-core`. No dependency on `q1-api`.

## Responsibilities

- Single-group Raft consensus via Apache Ratis (embedded, no external coordinator)
- Global leader election (one leader for all 16 partitions)
- Write replication through the Raft log (gRPC, inter-node)
- Request routing: non-leader nodes return 307 to the leader

## Architecture: 1 global Raft group

One Raft group for all 16 partitions. A single leader absorbs all writes cluster-wide
(equivalent to the old per-partition model since writes were already routed to the
partition leader). This is simpler: one election, one gRPC connection, no
`partitionLeaders` / `partitionReplicas` maps.

Quorum = ⌊N/2⌋+1 (replaces `Q1_RF`).

## Key classes

| File | Role |
|---|---|
| `RatisCluster.java` | Lifecycle of `RaftServer` + `RaftClient`; `submit()`, `isLocalLeader()`, `leaderHttpBaseUrl()` |
| `Q1StateMachine.java` | `BaseStateMachine` impl — applies Raft log entries to `StorageEngine` |
| `RatisCommand.java` | Binary encoding of mutations (PUT, DELETE, CREATE_BUCKET, DELETE_BUCKET) |
| `PartitionRouter.java` | `leaderBaseUrl(bucket, key)` → delegates to `RatisCluster.leaderHttpBaseUrl()` |
| `NodeId.java` | `record(id, host, httpPort, raftPort)` |
| `ClusterConfig.java` | Immutable config: `self`, `peers`, `numPartitions`, `raftPort`, `ecConfig` |

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
  ├── if not leader → 307 to leaderHttpBaseUrl
  └── if leader → cluster.submit(RatisCommand.put(...))
                    → Raft commit (majority ACK via gRPC)
                    → Q1StateMachine.applyTransaction() on all nodes
                    → engine.put(bucket, key, value) on each
```

No HTTP fan-out, no `X-Q1-Replica-Write` header. Raft handles replication.

## Startup

```
RatisCluster cluster = new RatisCluster(cfg, stateMachine);
cluster.start();   // RaftServer starts, election runs automatically
server.start();    // HTTP open once Raft is ready
```

A restarted node replays the Raft log to recover state (no manual catchup needed).

## Environment variables

| Variable | Default | Description |
|---|---|---|
| `Q1_NODE_ID` | `node-{random8}` | Unique node identifier (must match a peer ID in `Q1_PEERS`) |
| `Q1_HOST` | `localhost` | Advertised HTTP hostname/IP |
| `Q1_PORT` | `9000` | HTTP port |
| `Q1_RAFT_PORT` | `6000` | Raft gRPC port |
| `Q1_PEERS` | _(empty = standalone)_ | `id\|host\|httpPort\|raftPort` per node, comma-separated |
| `Q1_PARTITIONS` | `16` | Number of partitions |

Example `Q1_PEERS`:
```
node1|10.0.0.1|9000|6000,node2|10.0.0.2|9000|6000,node3|10.0.0.3|9000|6000
```

## Ratis internals

- `RaftGroupId`: fixed UUID, identical on all nodes (from config)
- `RaftPeerId`: `nodeId` (string)
- `RaftPeer`: `(id, host:raftPort)` — gRPC transport
- Raft log stored under `$Q1_DATA_DIR/raft/`
- Snapshots: not implemented yet (log replayed from start on restart)

## TODO

- [ ] Raft snapshots (`takeSnapshot` / `loadSnapshot`) to bound startup replay time
- [ ] Dynamic membership (`setConfiguration`) for elastic cluster resize
- [ ] Bucket operations replicated via Raft (CREATE_BUCKET / DELETE_BUCKET wired in BucketHandler)
- [ ] Metrics: Raft term, commit index, replication lag
