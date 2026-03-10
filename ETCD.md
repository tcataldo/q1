# etcd Usage in Q1

etcd is a distributed key-value store with strong consensus (Raft). Q1 uses it
exclusively as a **cluster coordinator** — never as a user data store. All S3 data
stays in the append-only segments.

---

## What etcd does today

### 1. Membership — `/q1/nodes/{nodeId}`

Each node writes its presence key at startup, **tied to its lease** (TTL 10s, renewed
by keepalive). If the node disappears, the lease expires and the key is automatically
deleted by etcd. This is the canonical list of active nodes.

```
/q1/nodes/node0  →  "node0:localhost:9000"   (ephemeral)
/q1/nodes/node1  →  "node1:localhost:9001"   (ephemeral)
```

All nodes watch this prefix. As soon as a node joins or leaves, each node updates its
local `activeNodes` map.

### 2. Per-partition leader election — `/q1/partitions/{id}/leader`

Conditional transaction: `PUT IF VERSION == 0` (key does not yet exist).
Only one node can write; others wait for the key to disappear before campaigning again.
The key is ephemeral (tied to the leader's lease): if the leader goes down, etcd deletes
it automatically and a new election starts.

```
/q1/partitions/0/leader  →  "node0:localhost:9000"   (ephemeral, node0 lease)
/q1/partitions/1/leader  →  "node1:localhost:9001"   (ephemeral, node1 lease)
```

Guarantee: **at most one leader per partition at any instant** (split-brain impossible).

### 3. Replica assignment — `/q1/partitions/{id}/replicas`

After winning election, the leader computes its RF-1 followers via a ring over
`activeNodes` sorted by `nodeId`. It writes the list, also tied to its lease.

```
/q1/partitions/0/replicas  →  "node1:localhost:9001"   (ephemeral, node0 lease)
```

Used by:
- `HttpReplicator` to know which nodes to fan-out synchronous writes to
- `CatchupManager` to know which partitions to sync on startup
- `rebalance()` to redistribute when a node joins

---

## Critique of the initial EC design — and why the simplified design is better

### The initial design (removed)

> Store a shard under the same key on each node, fetch the blobs from live nodes,
> reconstruct via EC.

This is simpler and **it is correct**. The initial design used etcd to store per-object
EC metadata (`EcMetadataStore`). Here is why that was unnecessary.

### What the initial design added unnecessarily

The etcd entry for EC contained three types of information:

| Info | Necessary in etcd? | Alternative |
|---|---|---|
| Which shard is on which node | **No** | The deterministic ring gives the same answer |
| Original size (to remove padding) | **No** | 8 bytes in the shard header |
| k and m | **No** | Global cluster config (fixed at startup) |

In other words: **the etcd entry was redundant**. It encoded information that can
either be computed (ring) or embedded directly in the payload.

### Consequences of the initial design

1. **etcd on the critical path of every EC GET and PUT** — one extra network round-trip,
   and if etcd is overloaded or temporarily unavailable, EC reads fail even though the
   data nodes are perfectly healthy.

2. **Metadata leak risk** — a crash between writing the shards and writing to etcd leaves
   orphaned shards (no metadata → object invisible). A crash after writing to etcd but
   before all shards are complete makes the object present but unreadable. Both are
   manageable but add complexity.

3. **etcd overhead** — for 60M emails, 60M additional etcd keys. etcd is not designed
   for tens of millions of persistent keys.

### The simplified design (shard header)

Each shard is stored with a **self-describing 8-byte header**:

```
[8B]  original object size (big-endian long)
[…]   shard data (zero-padded if necessary)
```

k and m are known globally (config `Q1_EC_K` / `Q1_EC_M`).

**Write** (identical to before, minus the last step):
1. `computeShardPlacement(bucket, key)` → ring → k+m nodes
2. Encode → k+m shards
3. Fan-out: `PUT /internal/v1/shard/{bucket}/{key}` on each target node
4. **No etcd write.** That's it.

**Read** (from any node):
1. `computeShardPlacement(bucket, key)` → same ring → same k+m nodes
2. `GET /internal/v1/shard/{bucket}/{key}` on the k+m nodes in parallel
3. Each response contains the header → original size
4. Reconstruct with at least k present shards
5. **No etcd read.** That's it.

The ring being **deterministic and independently computable by any node** from the
active node list (read once from etcd at startup, then maintained by watch), the data
path no longer needs etcd.

### Design comparison

| | Initial design (etcd metadata) | Simplified design (shard header) |
|---|---|---|
| etcd read on GET | Yes (1 round-trip) | No |
| etcd write on PUT | Yes (commit point) | No |
| Resilience if etcd ↓ | EC reads/writes fail | EC reads/writes continue |
| Orphaned shard after crash | Possible (manual cleanup) | Impossible (no metadata to clean) |
| etcd overhead | O(EC objects) — potentially 60M keys | O(1) — only elections/nodes/replicas |
| Changing k/m | Per-object (each metadata has k and m) | Global config only |
| Code complexity | EcMetadataStore + 60M etcd keys | 8B header in ShardHandler |

### The only real trade-off

The simplified design loses the **flexibility to change k and m on the fly** for
individual objects. All objects use the same k/m. But the use case explicitly requires
"nodes static and fixed at cluster init" — this constraint makes per-object k/m changes
out of scope anyway.

### Conclusion

The rule is: etcd for **coordination** (who is the leader? who is alive?), segments for
**data** (the shards themselves). `EcMetadataStore` and the `/q1/ec-meta/` keys have
been removed. **Migration complete.**

---

## etcd key layout post-migration

```
/q1/nodes/{nodeId}              →  "id:host:port"        ephemeral, lease
/q1/partitions/{id}/leader      →  "id:host:port"        ephemeral, lease
/q1/partitions/{id}/replicas    →  "id:host:port,…"      ephemeral, lease
```

That's all. EC adds no persistent keys to etcd.
