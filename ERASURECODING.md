# Erasure Coding in Q1

## Motivation

With pure replication (RF=3), storage overhead is ×3. Reed-Solomon erasure coding
delivers the same fault tolerance at much lower cost:

| Config | Nodes | Tolerated failures | Overhead |
|---|---|---|---|
| RF=3 (replication) | 3 | 1 | ×3.0 |
| k=2, m=1 | 3 | 1 | ×1.5 |
| k=4, m=2 | 6 | 2 | ×1.5 |
| k=6, m=3 | 9 | 3 | ×1.5 |

The library used is [Backblaze JavaReedSolomon](https://github.com/Backblaze/JavaReedSolomon)
(`com.backblaze.erasure:erasure:1.0.3`), which operates on `byte[][]` arrays and is
purely in-heap with no native dependencies.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `Q1_EC_K` | `0` (disabled) | Number of data shards |
| `Q1_EC_M` | `2` | Number of parity shards |
| `Q1_EC_MIN_SIZE` | `0` | Objects below this size use plain replication instead |

When `Q1_EC_K=0`, the cluster falls back to standard `Q1_RF`-based replication and is
fully backward-compatible with pre-EC data.

**Requirement:** `k + m` active cluster nodes must be present at startup.

## Architecture overview

```
                  ┌───────────────────────────────────────────┐
Client PUT ──────►│ Leader node (EcObjectHandler)             │
                  │  1. encode(body) → k data + m parity shards│
                  │  2. fan-out each shard to its target node   │
                  │  3. write EcMetadata to etcd               │
                  │  4. return 200 OK                          │
                  └───────────────────────────────────────────┘
                         │shard0   │shard1   │shard2
                         ▼         ▼         ▼
                       node0     node1     node2
                     (ShardHandler stores locally via StorageEngine)

Client GET ──────► Any node (EcObjectHandler)
                  1. read EcMetadata from etcd → ShardPlacement
                  2. fetch k+ shards in parallel (timeout per shard)
                  3. ReedSolomon.decodeMissing() if any absent
                  4. return reconstructed original bytes
```

## Components

### `EcConfig` (`q1-cluster`)
Immutable record: `dataShards (k)`, `parityShards (m)`, `minObjectSize`.
`enabled()` returns true when `k > 0`.

### `ErasureCoder` (`q1-cluster`)
Wraps `ReedSolomon.create(k, m)`.
- `encode(byte[])` → `byte[k+m][shardSize]` — pads data to a multiple of k, then
  calls `encodeParity` in-place on the k+m shard array.
- `decode(byte[][], long originalSize)` → `byte[]` — allocates missing shards, calls
  `decodeMissing`, then stitches data shards back together and removes padding.

### `ShardPlacement` (`q1-cluster`)
Record holding an ordered list of `k+m` `NodeId`s: `nodeForShard(i)` → the node that
stores shard `i`. Computed deterministically by `EtcdCluster.computeShardPlacement()`.

**Algorithm:** sort all active nodes by `nodeId` (lexicographic) to form a ring; pick
the anchor as `Math.abs((bucket + '\0' + key).hashCode()) % N`; take `k+m` consecutive
nodes clockwise from the anchor (wrapping). Result is stable for a fixed cluster
topology and distributes load evenly.

### `EcMetadata` (`q1-cluster`)
Stores per-object EC parameters:
```
k | m | originalSize | shardSize | nodeId0,nodeId1,...
```
Persisted in etcd at `/q1/ec-meta/{bucket}/{objectKey}` as a `|`-delimited wire string.
Node IDs use the existing `id:host:port` wire format, separated by `,`.

### `EcMetadataStore` (`q1-cluster`)
etcd CRUD for `EcMetadata`.  Reuses the jetcd `Client` from `EtcdCluster` (exposed via
`EtcdCluster.ecMetadataStore()`).

### `HttpShardClient` (`q1-cluster`)
JDK `HttpClient` (virtual-thread executor) for shard fan-out:
- `PUT  /internal/v1/shard/{idx}/{bucket}/{key}` — body = raw shard bytes
- `GET  /internal/v1/shard/{idx}/{bucket}/{key}` — returns raw shard bytes
- `DELETE /internal/v1/shard/{idx}/{bucket}/{key}` — removes the shard

### `ShardHandler` (`q1-api`)
Undertow handler mounted at `/internal/v1/shard/`. Stores/retrieves shards via the
local `StorageEngine` under the internal bucket `__q1_ec_shards__` with key
`{userBucket}/{objectKey}/{shardIndex:02d}`. Bypasses the user-bucket existence check
(internal endpoint).

### `EcObjectHandler` (`q1-api`)
Replaces `ObjectHandler` when EC is enabled:
- **PUT:** encode → distribute shards → write metadata → 200
- **GET:** read metadata → parallel shard fetch → decode → 200 (or 503 if < k shards)
- **DELETE:** read metadata → parallel shard delete (best-effort) → delete metadata → 204

Falls back to `StorageEngine.get()`/`delete()` for objects written before EC was
enabled (no etcd metadata found).

## Write path detail

```
EcObjectHandler.put(bucket, key, body):
  if body.length < ecConfig.minObjectSize():
      // small object: plain replication via existing ObjectHandler path
      return

  placement = cluster.computeShardPlacement(bucket, key)
  shards    = erasureCoder.encode(body)          // byte[k+m][shardSize]
  metadata  = new EcMetadata(k, m, body.length, shards[0].length, placement.nodeIds())

  // fan-out shards in parallel (virtual threads)
  for i in 0..(k+m-1):
      node = placement.nodeForShard(i)
      if node == self:
          engine.put("__q1_ec_shards__", shardKey(bucket, key, i), shards[i])
      else:
          httpShardClient.putShard(node, i, bucket, key, shards[i])
  // await all acks (5s timeout)

  metadataStore.put(bucket, key, metadata)  // written last: atomically makes object visible
```

**Atomicity note:** shards are written before metadata. A write that crashes between the
two steps leaves orphaned shards (invisible to readers) that can be garbage-collected
offline. Metadata written last is the commit point.

## Read path detail

```
EcObjectHandler.get(bucket, key):
  meta = metadataStore.get(bucket, key)
  if meta == null:
      return engine.get(bucket, key)   // backward-compat: pre-EC object

  shards = new byte[k+m][]
  // fetch all shards in parallel, mark missing as null on timeout/error
  ...
  if count(non-null shards) < k:
      return 503 ServiceUnavailable
  return erasureCoder.decode(shards, meta.originalSize())
```

## Delete path detail

```
EcObjectHandler.delete(bucket, key):
  meta = metadataStore.get(bucket, key)
  if meta == null:
      engine.delete(bucket, key)   // backward-compat
      return

  // fan-out delete shards (best-effort, ignore 404)
  metadataStore.delete(bucket, key)
  return 204
```

## Cluster readiness in EC mode

`EtcdCluster.isClusterReady()` returns `true` when `activeNodes.size() >= k`.
This allows reads to proceed when `k` nodes are up. If a write fan-out fails because
fewer than `k+m` nodes are reachable, the error is propagated to the client (500).

## Shard storage layout

Shards are stored in the regular `StorageEngine` using a hidden internal bucket that
never appears in `BucketRegistry` (never user-created, filtered from bucket listings):

```
Bucket:  __q1_ec_shards__
Key:     {userBucket}/{objectKey}/{shardIndex:02d}
```

Example for `s3://emails/msg-12345`, shard 0:
```
Bucket:  __q1_ec_shards__
Key:     emails/msg-12345/00
Full internal key:  __q1_ec_shards__\x00emails/msg-12345/00
```

This reuses all existing segment/index infrastructure without format changes.

## Compatibility with existing data

Objects written in plain replication mode have no EC metadata in etcd.
`EcObjectHandler` detects the absence of metadata and falls back to the normal local
`StorageEngine.get()` path, preserving backward compatibility.

## Limitations (initial version)

- Node count is **static**: `k+m` must be ≤ active nodes at cluster startup. Elastic
  re-encoding when nodes join/leave is not yet implemented.
- Objects smaller than `Q1_EC_MIN_SIZE` use plain replication (default: all objects
  use EC when `Q1_EC_K > 0`).
- No background repair: if a node holding shards is permanently lost, objects that
  relied on reconstruction will degrade over time. A repair pass (re-encode missing
  shards to a new node) is a future work item.
- Bucket create/delete is still not replicated (same limitation as replication mode).
