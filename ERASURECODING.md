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

The Reed-Solomon codec is vendored as `io.q1.cluster.erasure.{Galois,Matrix,ReedSolomon}`,
compatible with the Backblaze JavaReedSolomon API, with no external Maven dependency.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `Q1_EC_K` | `0` (disabled) | Number of data shards |
| `Q1_EC_M` | `2` | Number of parity shards |
| `Q1_EC_MIN_SIZE` | `0` | Objects below this size use plain local write instead |

When `Q1_EC_K=0`, the cluster falls back to standard `Q1_RF`-based replication and is
fully backward-compatible with pre-EC data.

**Requirement:** `k + m` active cluster nodes must be present at startup.

## Architecture overview

```
              ┌────────────────────────────────────────────┐
Client PUT ──►│ Any node (EcObjectHandler)                 │
              │  1. encode(body) → k data + m parity shards│
              │  2. buildPayload: prepend 8B originalSize  │
              │  3. fan-out each payload to ring node      │
              │  4. return 200 OK (no etcd write)          │
              └────────────────────────────────────────────┘
                     │payload0  │payload1  │payload2
                     ▼          ▼          ▼
                   node0      node1      node2
                 (ShardHandler stores via StorageEngine)

Client GET ──► Any node (EcObjectHandler)
              1. computeShardPlacement (ring, no etcd)
              2. fetch k+m payloads in parallel
              3. parse originalSize from 8B header of first present shard
              4. ReedSolomon.decodeMissing() if any absent
              5. return reconstructed original bytes
```

## Shard payload format

Every shard stored in the cluster carries a self-describing 8-byte header:

```
[8B]  original object size (big-endian long)
[…]   shard data (Reed-Solomon shard, zero-padded to shardSize)
```

This means **etcd is not on the data path at all**: neither reads nor writes
touch etcd to resolve EC metadata. The ring placement is fully deterministic
(computable from the live node list), and the original size is embedded in
each shard. See `ETCD.md` for the full rationale.

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

### `ReedSolomon` / `Matrix` / `Galois` (`q1-cluster/erasure`)
Vendored GF(2^8) codec. Systematic Vandermonde generator matrix: top k rows = identity
so data shards pass through unchanged. Parity rows derived from `G = V · V_top^-1`.
`decodeMissing` uses fresh output buffers to avoid aliasing between input and output shards.

### `ShardPlacement` (`q1-cluster`)
Record holding an ordered list of `k+m` `NodeId`s: `nodeForShard(i)` → the node that
stores shard `i`. Computed deterministically by `EtcdCluster.computeShardPlacement()`.

**Algorithm:** sort all active nodes by `nodeId` (lexicographic) to form a ring; pick
the anchor as `Math.abs((bucket + '\0' + key).hashCode()) % N`; take `k+m` consecutive
nodes clockwise from the anchor (wrapping). Result is stable for a fixed cluster
topology and distributes load evenly.

### `HttpShardClient` (`q1-cluster`)
JDK `HttpClient` (virtual-thread executor) for shard fan-out:
- `PUT  /internal/v1/shard/{idx}/{bucket}/{key}` — body = 8B header + raw shard bytes
- `GET  /internal/v1/shard/{idx}/{bucket}/{key}` — returns 8B header + raw shard bytes
- `DELETE /internal/v1/shard/{idx}/{bucket}/{key}` — removes the shard

### `ShardHandler` (`q1-api`)
Undertow handler mounted at `/internal/v1/shard/`. Stores/retrieves shard payloads via
the local `StorageEngine` under the internal bucket `__q1_ec_shards__` with key
`{userBucket}/{objectKey}/{shardIndex:02d}`. Transparent to payload format.

### `EcObjectHandler` (`q1-api`)
Replaces `ObjectHandler` when EC is enabled:
- **PUT:** encode → build payloads (prepend 8B header) → fan-out to ring → 200
- **GET:** parallel payload fetch → parse originalSize from first present header → decode → 200
- **HEAD:** fetch first available shard sequentially → read originalSize from header → 200
- **DELETE:** fan-out ring delete (best-effort) + `engine.delete` (covers pre-EC objects) → 204

Falls back to `StorageEngine.get()` for objects with no shards (pre-EC plain replication).

## Write path detail

```
EcObjectHandler.put(bucket, key, body):
  if body.length < ecConfig.minObjectSize():
      engine.put(bucket, key, body)   // small object: plain local write
      return

  placement = cluster.computeShardPlacement(bucket, key)
  shards    = erasureCoder.encode(body)             // byte[k+m][shardSize]

  // fan-out payloads in parallel (virtual threads)
  for i in 0..(k+m-1):
      payload = [8B: body.length][shards[i]]
      node    = placement.nodeForShard(i)
      if node == self:
          engine.put("__q1_ec_shards__", shardKey(bucket, key, i), payload)
      else:
          httpShardClient.putShard(node, i, bucket, key, payload)
  // await all acks (5s timeout)
  // no etcd write
```

## Read path detail

```
EcObjectHandler.get(bucket, key):
  placement = cluster.computeShardPlacement(bucket, key)

  // fetch all k+m payloads in parallel (null on error/404)
  payloads = fetchPayloads(placement, bucket, key)

  if all payloads are null:
      return engine.get(bucket, key)   // backward-compat: pre-EC object

  originalSize = parseOriginalSize(first non-null payload)
  if count(non-null payloads) < k:
      return 503 ServiceUnavailable

  shards = strip 8B header from each payload
  return erasureCoder.decode(shards, originalSize)
```

## Delete path detail

```
EcObjectHandler.delete(bucket, key):
  placement = cluster.computeShardPlacement(bucket, key)
  // fan-out shard deletes (best-effort, best shard availability wins)
  deleteShards(placement, bucket, key)
  // also delete from plain storage (covers pre-EC objects and min-size fallback)
  engine.delete(bucket, key)
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

Objects written in plain replication mode have no shards anywhere.  `EcObjectHandler`
detects this (all shard fetches return null/404) and falls back to
`StorageEngine.get()`, preserving backward compatibility transparently.

## Limitations (initial version)

- Node count is **static**: `k+m` must be ≤ active nodes at cluster startup. Elastic
  re-encoding when nodes join/leave is not yet implemented.
- Objects smaller than `Q1_EC_MIN_SIZE` use a plain local write (no EC, no replication).
- No background repair: if a node holding shards is permanently lost, a repair pass
  (re-encode missing shards to a new node) is a future work item.
- Bucket create/delete is still not replicated (same limitation as replication mode).
