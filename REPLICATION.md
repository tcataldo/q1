# Replication in Q1

This document explains how writes propagate between nodes, what each node sees on reads,
and how startup catchup works.

---

## 1. Anatomy of a Q1 cluster

Each node holds **all N partitions locally** (16 by default). There is no "partition that
belongs to a node" as in Kafka. The distinction is at the **per-partition leader** level:

```
3-node cluster, 16 partitions, RF=2

          Partition 0   Partition 1   Partition 2   …
Node A      LEADER        follower      —
Node B      —             LEADER        LEADER
Node C      follower      —             follower    ← RF=2, C is assigned replica of P2
```

The leader is elected via etcd (conditional `PUT IF VERSION == 0` transaction). The key
`/q1/partitions/{id}/leader` is ephemeral: it disappears if the node's lease expires,
triggering a new election.

---

## 2. Deterministic replica assignment

### Problem solved

Without explicit assignment, the choice of RF-1 followers would be made at write time from
a `ConcurrentHashMap` with no guaranteed ordering. Result: the "same" follower would not
necessarily receive all writes for the same partition.

### Solution: ring written to etcd

Active nodes are sorted by `nodeId` to form a ring. Immediately after winning leadership for
a partition, the leader takes the RF-1 nodes **immediately following in the ring** (wrapping
around) and writes the list to `/q1/partitions/{id}/replicas` (tied to its lease).

```
Sorted ring: [node-A, node-B, node-C]

  node-A leads P → replica = node-B  (next in ring)
  node-B leads P → replica = node-C
  node-C leads P → replica = node-A  (wraps around)
```

```
/q1/partitions/2/leader   → "node-B:10.0.0.2:9000"
/q1/partitions/2/replicas → "node-C:10.0.0.3:9000"
```

### Why ring instead of "first sorted"

With "first sorted non-leader", `node-A` is always chosen first: it becomes a replica of
every partition it does not lead, while `node-C` (last) is never a replica. With 3 nodes RF=2:

```
"First sorted" (before)       Ring (now)
  node-A  100% of data          node-A  ~67% of data
  node-B   67% of data          node-B  ~67% of data
  node-C   33% of data          node-C  ~67% of data
```

The ring guarantees each node stores exactly `RF/N` of all data,
i.e. `2/3 ≈ 67%` for RF=2, N=3.

All nodes observe this key via a single watch on the `/q1/partitions/` prefix and keep a
local `partitionReplicas` map current.

### Assignment lifecycle

The `/replicas` key is tied to the leader's lease — it disappears automatically if the
leader goes down. The new leader recomputes and rewrites the assignment after its election.
During the gap (a few hundred ms), `followersFor()` falls back to an identical deterministic
computation.

---

## 3. Write path (PUT / DELETE)

```
Client
  │
  ▼
Node C  ──── not the leader of partition 2 ────►  307 Temporary Redirect
                                                       │ Location: http://NodeB/…
                                                       ▼
                                                   Node B (leader of P2)
                                                     │
                                                     ├─ 1. writes locally (segment append)
                                                     │
                                                     ├─ 2. reads partitionReplicas[2] → [node-A]
                                                     │      parallel fan-out, header X-Q1-Replica-Write: true
                                                     │
                                                     └─ 3. waits for all ACKs (5s max)
                                                            then responds 200 to client
```

Key points:

- **307 preserves the method**: a redirected DELETE remains a DELETE.
- **`X-Q1-Replica-Write: true`**: prevents the follower from re-replicating in turn.
- **Strong durability**: the client only receives 200 when the leader _and_ all RF-1
  followers have confirmed. If a follower times out (5s), the leader returns 500 to the client.
- **Stable list**: `HttpReplicator` reads `partitionReplicas` maintained by the watch —
  no on-the-fly computation, no randomness.

---

## 4. Read path (GET / HEAD)

```java
// S3Router.java — reads are never redirected
case "GET"  -> objectHandler.get(exchange, pp.bucket(), pp.key());
case "HEAD" -> objectHandler.head(exchange, pp.bucket(), pp.key());
```

**Any node responds locally**, without consulting the leader. This is an **eventually
consistent** read: a node not in the assigned replica list may return 404 (or a stale
version) for a recently written object.

For strong reads (read from the leader), applying the same 307 as for writes would
suffice — the mechanism is already in place in `S3Router`. It is not enabled by default
to preserve read performance.

---

## 5. Startup catchup

When a node restarts, `CatchupManager.catchUp()` runs **before** the HTTP port opens.

### Algorithm

```
For each partition p from 0 to N-1:
  if I am the leader of p      → skip (I already have the data)
  if I am not a replica        → skip (this partition is not my concern)
  otherwise:
    state = engine.partitionSyncState(p)   // local (segmentId, byteOffset)
    GET /internal/v1/sync/{p}?segment={s}&offset={o}  → leader of p

    204 → already up to date, nothing to do
    200 → binary stream of records from (s, o) → applySyncStream()
    error → logged, node starts anyway
```

### Incremental catchup

`SyncState(segmentId, byteOffset)` is the position of the last applied write.
The follower sends its local position; the leader responds only with the delta.
A node that had 90% of the data does not retransmit the 90%.

### Why nodes do not all end up with everything

With the stable assignment, a node **knows** which partitions it is a replica of.
It only syncs those. Node C (assigned replica of P2 but not P0 or P1) does not request
a sync from Node A or Node B for P0/P1 — its local data for those partitions stays as-is
(potentially empty if never assigned).

```
Node C restarts after downtime:

  isLocalLeader(P0) = false, isAssignedReplica(P0) = false → skip
  isLocalLeader(P1) = false, isAssignedReplica(P1) = false → skip
  isLocalLeader(P2) = false, isAssignedReplica(P2) = true  → sync from Node B
  …
```

### Full startup sequence in cluster mode

```
1. EtcdCluster.start()
     ├── grant lease (TTL 10s, keepalive in background)
     ├── registerSelf() → /q1/nodes/{nodeId}
     ├── loadExistingNodes() + loadExistingLeaders() + loadExistingReplicas()
     ├── watchNodes() + watchPartitions()   ← single watch on /q1/partitions/ prefix
     └── electionLoop(p) for each partition (virtual threads)
           └── if elected: writeReplicas(p) → /q1/partitions/{p}/replicas

2. CatchupManager.catchUp(engine)
     ├── waitForElections() — up to 3s for leaders + assignments to be known
     └── syncPartition() only for partitions where isAssignedReplica() = true

3. Q1Server.start()  ← HTTP port opens only here
```

---

## 6. Concrete scenario: 3 nodes, RF=2

```
Active nodes sorted: [node-A, node-B, node-C]
Partition 2 leader: node-B  → replica = [node-A]  (RF-1=1, first non-leader sorted)

Writing "emails/msg-42":
  → hashes to partition 2
  → node-C redirects 307 to node-B
  → node-B writes locally
  → node-B reads partitionReplicas[2] = [node-A]
  → node-B replicates to node-A
  → 200 OK to client

Read from node-C:
  → node-C reads its local index → 404
  → (the object exists on B and A)

node-C restarts after shutdown:
  → loadExistingReplicas(): partitionReplicas[2] = [node-A] ← node-C is not in it
  → isAssignedReplica(2) = false → skip
  → node-C does not request a sync for P2

node-A restarts (assigned replica of P2):
  → isAssignedReplica(2) = true
  → local SyncState = (1, 0) if never synced, or last known position
  → GET /internal/v1/sync/2?segment=1&offset=0 → node-B
  → node-B streams records from the origin
  → node-A: "emails/msg-42" in its index
  → node-A opens HTTP port → GET returns 200 OK
```

---

## 7. Current limitations and TODO

| Problem | Impact | Status |
|---|---|---|
| Reads not routed to the leader | A GET may return 404 while the object exists | known |
| Buckets not replicated | `createBucket` / `deleteBucket` not fanned out | TODO |
| No replication lag detection | Impossible to tell if a follower is behind | TODO |
| No back-pressure | If a follower is slow, the leader blocks for 5s then fails | TODO |

### Leadership rebalancing

After elections settle, `rebalance()` ensures no node leads more than `⌈P/N⌉` partitions:

1. Count how many partitions this node leads
2. If `count > ⌈P/N⌉`: delete the etcd leader key for the excess
3. A **cooldown** (2× lease TTL) prevents `electionLoop` from immediately re-campaigning
4. Other nodes see the DELETE via watch and win those partitions

`rebalance()` is called:
- By `CatchupManager` after elections have converged (on startup)
- By `watchNodes` when a new node joins (cluster rebalances live)

### Summary: join/leave

When a node joins or leaves, `watchNodes()` triggers two things:
- `refreshReplicasForLeadPartitions()` — updates replica lists
- `rebalance()` — rebalances leadership if necessary
