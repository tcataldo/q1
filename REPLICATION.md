# Replication in Q1

> **Note:** This document describes the current Raft architecture (post-etcd).
> The old architecture (etcd + HttpReplicator + CatchupManager + SyncHandler)
> has been removed. See git log for history.

---

## Replication mode (EC disabled)

### Write path

```
Client PUT /bucket/key
  │
  ├── follower node → proxyToLeader()  [transparent HTTP proxy, body forwarded]
  │                                    client sees the leader's response directly (200)
  │
  └── leader → cluster.submit(RatisCommand.put(bucket, key, value))
                 → Raft commit (quorum gRPC: ⌊N/2⌋+1 ACKs required)
                 → Q1StateMachine.applyTransaction() on every node
                 → engine.put(bucket, key, value) locally on every node
                 → 200 OK to client
```

**Guarantees:**
- Client receives 200 only after commit on the quorum
- No split-brain possible (Raft guarantees leader uniqueness)
- The follower does not re-replicate (no `X-Q1-Replica-Write` header)
- Client never sees a 307 — the proxy is fully transparent

### Read path

GET and HEAD are served locally by any node without consulting the leader.
**Eventually consistent**: a lagging node may return 404 or a stale version
for a very recent write.

### Startup / catch-up

A restarted node loads its latest Raft snapshot, then replays only the log entries that
follow the snapshot index. There is no `CatchupManager` and no `/internal/v1/sync/`
endpoint — Ratis handles catch-up automatically.

The snapshot is an empty marker file (term+index in the filename) created by
`Q1StateMachine.takeSnapshot()`. The actual state (segments + RocksDB) is already durable
on disk independently of the log. `engine.sync()` is called just before the snapshot to
guarantee that all applied writes are flushed before Ratis purges the log.

---

## EC mode (Q1_EC_K > 0)

The Raft log is **not** on the object data path. See ERASURECODING.md.

The only `RatisCommand` types emitted in EC mode are `CREATE_BUCKET` and `DELETE_BUCKET`.

---

## Environment variables

| Variable | Description |
|---|---|
| `Q1_PEERS` | `id\|host\|httpPort\|raftPort` per node, comma-separated |
| `Q1_RAFT_PORT` | Raft gRPC port (default 6000) |
| `Q1_NODE_ID` | Must match an ID in `Q1_PEERS` |

Absent `Q1_PEERS` → standalone mode, all requests served locally.
