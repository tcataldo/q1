# Q1 — Replication Architecture

This document describes the current replication model, its limitations, and the
target architecture for per-partition Raft leadership.

---

## 1. Current state (single global Raft group)

One `RaftGroupId` covers all 16 partitions.  Every write — regardless of which
partition it targets — is serialised through the single elected leader.

```
nodes = [q1-01, q1-02, q1-03]   RF = 3 (whole cluster)

q1-01 ──leader──► q1-01/p00…p15  ← all writes
                  q1-02/p00…p15  ← all followers
                  q1-03/p00…p15  ← all followers
```

Consequences:

| Problem | Impact |
|---|---|
| All writes go to one node | Network and CPU hotspot on leader |
| Follower disks are idle for writes | No horizontal write scaling |
| RF = total nodes | Can't choose RF < N |
| Leader failover = full cluster pause | No partition-level isolation |

---

## 2. Target architecture — per-partition Raft groups

Each of the P partitions gets its own `RaftGroupId` and its own set of RF
replicas drawn deterministically from the sorted node list.

### 2.1 Replica assignment

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

Each node hosts RF * P / N ≈ 10–11 partitions and is **initial leader** for
P / N ≈ 5 partitions.  Writes are distributed evenly across all nodes.

### 2.2 Raft group IDs

Deterministic, derived from the partition index — no coordination required:

```
groupId(p) = 51310001-0000-0000-0000-{p:012d}
```

Examples:
```
p00  →  51310001-0000-0000-0000-000000000000
p01  →  51310001-0000-0000-0000-000000000001
p15  →  51310001-0000-0000-0000-000000000015
```

### 2.3 Metadata group

Bucket `CREATE` / `DELETE` operations are replicated to **all nodes** via a
separate metadata `RaftGroupId`:

```
metaGroupId = 51310001-ffff-ffff-ffff-ffffffffffff
replicas    = all nodes (RF = N)
```

This keeps bucket state consistent cluster-wide without coupling it to any
specific partition.

---

## 3. Routing

### 3.1 Write path

```
Client PUT /bucket/key
  → S3Router
    → partitionId = abs(fullKey.hashCode()) % P
    → PartitionRouter.leaderFor(partitionId)
    → if local: submit to RaftGroup(partitionId)
    → if remote: proxy to leaderHost:httpPort
```

Each `PUT` / `DELETE` is submitted to the Raft group for its own partition only.

### 3.2 Read path

Reads (`GET`, `HEAD`) can be served by any replica that holds the partition —
no routing to leader required.

### 3.3 Bucket operations

`CreateBucket` / `DeleteBucket` are submitted to the metadata group.  The
metadata leader may differ from any partition leader.

---

## 4. Implementation plan

### Phase 1 — `ClusterConfig` + environment

**Files:** `q1-cluster/src/main/java/io/q1/cluster/ClusterConfig.java`

- Add `int rf` field (default 2).
- Read from env var `Q1_RF` (parse int, validate `1 ≤ rf ≤ nodeCount`).
- Expose `replicas(int partitionId, List<NodeConfig> sortedNodes)` helper.

### Phase 2 — `RatisCluster` multi-group refactor

**Files:** `q1-cluster/src/main/java/io/q1/cluster/RatisCluster.java`

- Replace single `RaftGroupId` / `RaftGroup` with a map:
  ```java
  Map<Integer, RaftGroup>   partitionGroups;   // partitionId → RaftGroup
  RaftGroup                 metaGroup;
  ```
- One `RaftServer` still, but `RaftServer.start()` receives all groups at once.
- `submit(int partitionId, RatisCommand cmd)` — submits to correct group.
- `isLocalLeader(int partitionId)` — checks division leader.
- `leaderId(int partitionId)` — returns `Optional<String>` for that group.
- `isLocalReplica(int partitionId)` — true if this node is in `replicas(p)`.

### Phase 3 — `StorageEngine` partial init

**Files:** `q1-core/src/main/java/io/q1/core/StorageEngine.java`

- Accept a `Set<Integer> assignedPartitions` (from `RatisCluster`).
- Skip opening `Partition` for unassigned partitions (leave slot `null`).
- Operations on unassigned partitions throw `IllegalStateException`.

### Phase 4 — Routing layer

**Files:**
- `q1-cluster/src/main/java/io/q1/cluster/PartitionRouter.java` (new)
- `q1-api/src/main/java/io/q1/api/S3Router.java`

`PartitionRouter`:
```java
String leaderHttpUrl(int partitionId);
boolean isLocalLeader(int partitionId);
```

`S3Router` — replace single `cluster.isLocalLeader()` check:
```java
int pid = partition(bucket, key);
if (!router.isLocalLeader(pid)) {
    proxyTo(router.leaderHttpUrl(pid), request, response);
    return;
}
cluster.submit(pid, cmd);
```

### Phase 5 — Admin API update

**Files:** `q1-api-grpc/src/main/java/io/q1/grpc/AdminServiceImpl.java`

`listPartitions()` — for each partition, call `cluster.leaderId(p)` to get the
partition-specific leader instead of the global `cluster.leaderId()`.

`PartitionReplica.is_leader` becomes per-partition accurate.

---

## 5. Migration

Per-partition groups use different `RaftGroupId`s than the current single group.
The stored Raft log under `$Q1_DATA_DIR/raft/` is incompatible.

**Required:** run `purge-and-deploy.yml` to wipe all data before deploying the
new version.  No in-place migration path is planned.

---

## 6. Open questions

| Question | Notes |
|---|---|
| Dynamic RF change | Requires Ratis `setConfiguration`; not planned |
| Partition rebalance when nodes join/leave | Static assignment only for now; needs `replicas()` recalc + data migration |
| Metadata group leader election | First node alphabetically will tend to win; no pinning |
| Read-from-follower consistency | Safe for immutable email objects (content-addressed); add option later |
| Snapshot isolation per group | Each partition group takes its own snapshot independently — smaller, more frequent |
