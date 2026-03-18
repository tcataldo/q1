# Q1 — Replication Architecture

---

## Overview

Q1 uses **Apache Ratis** (embedded Raft) for replication. There is no external
coordinator. A single `RaftServer` per JVM hosts multiple Raft groups: one per
partition plus one metadata group for bucket operations. Each group elects its
own leader independently, distributing write load across all nodes.

---

## Groups

### Partition groups

Each of the P partitions has its own `RaftGroupId` and its own set of RF
replicas drawn round-robin from the sorted node list:

```
replicas(p) = [ sorted(nodes)[(p + i) % N]  for i in 0..RF-1 ]
```

Example — 3 nodes, RF = 2, 16 partitions:

```
sorted nodes: [q1-01, q1-02, q1-03]

p00 → [q1-01, q1-02]   p08 → [q1-03, q1-01]
p01 → [q1-02, q1-03]   p09 → [q1-01, q1-02]
p02 → [q1-03, q1-01]   p10 → [q1-02, q1-03]
p03 → [q1-01, q1-02]   p11 → [q1-03, q1-01]
p04 → [q1-02, q1-03]   p12 → [q1-01, q1-02]
p05 → [q1-03, q1-01]   p13 → [q1-02, q1-03]
p06 → [q1-01, q1-02]   p14 → [q1-03, q1-01]
p07 → [q1-02, q1-03]   p15 → [q1-01, q1-02]
```

Each node hosts RF × P / N ≈ 10–11 partitions and leads P / N ≈ 5 of them.
Writes are balanced across all nodes.

### Metadata group

Bucket `CREATE` / `DELETE` are replicated via a separate metadata group whose
members are **all nodes** (RF = N). This keeps bucket state consistent
cluster-wide without tying it to any specific partition.

### RF default

`Q1_RF` env var (default: all peers, i.e. RF = N). Setting `Q1_RF=2` on a 3-node
cluster enables 2/3 coverage and distributes leadership without losing majority
tolerance.

---

## Group ID derivation

All IDs are derived from a **namespace UUID** (`ClusterConfig.raftGroupId` /
`Q1_RAFT_GROUP_ID` env var). Nodes agree on group IDs without coordination:

```java
long msb = namespace.getMostSignificantBits();

partitionGroup(p) = UUID(msb, (long) p)    // LSB = partition index
metaGroup         = UUID(msb, -1L)          // LSB = all 1s
```

Default namespace: `51310001-0001-0000-0000-000000000000`

Test classes each use a different namespace UUID so their background Ratis
gRPC threads do not interfere with each other in the same JVM.

---

## Write path

```
Client PUT /bucket/key
  → S3Router on any node
    → partitionId = abs(fullKey.hashCode()) % P
    → PartitionRouter.leaderHttpBaseUrl(partitionId)
    → if this node is the partition leader:
        cluster.submit(RatisCommand.put(...))
          → committed by quorum
          → Q1StateMachine.applyTransaction() on all RF replicas
    → if not the partition leader:
        transparent HTTP proxy to the partition leader
        client receives 200 directly (no 307 exposed)
```

Bucket operations follow the same pattern via the metadata group.

## Read path

`GET` / `HEAD` are served directly from the local `StorageEngine`. Raft commit
is synchronous, so any replica that returned 200 on the write is immediately
readable. Reads do not go through Raft.

---

## Snapshots

Each partition group and the metadata group take snapshots independently.
`Q1StateMachine.takeSnapshot()` writes an **empty marker file** whose filename
encodes `term_index` (e.g. `snapshot.3_10000`). The actual data is already
durable in the `StorageEngine` on disk; `engine.sync()` is called before the
snapshot so Ratis can safely purge the preceding log entries.

Auto-trigger threshold: 10 000 committed entries per group.

On restart, Ratis loads the latest snapshot and replays only the log entries
that follow it — restart time is bounded regardless of cluster history.

---

## Cluster readiness

A node is considered ready when **every group it manages** (meta + assigned
partitions) has a known leader. This is stricter than waiting for just the meta
leader: it prevents a window where writes succeed on the partition leader but
the local node hasn't identified that leader yet and would serve a phantom 404
on a subsequent read.

---

## Limitations / open questions

| Topic | Status |
|-------|--------|
| Dynamic RF change | Requires Ratis `setConfiguration`; not planned |
| Partition rebalance on node join/leave | Static assignment only; needs `replicas()` recalc + data migration |
| `StorageEngine` partial init | All nodes open all partitions; non-replica partitions receive no writes but consume file handles |
| Read-from-follower staleness | Safe for immutable content-addressed objects; not a concern in current workload |
| Snapshot isolation per group | Each group snapshots independently — naturally smaller and more frequent than a single global snapshot |
