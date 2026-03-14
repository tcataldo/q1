# Erasure Coding in Q1

## Motivation

With pure replication (RF=3), storage overhead is ×3. Reed-Solomon:

| Config | Nodes | Fault tolerance | Overhead |
|---|---|---|---|
| RF=3 (replication) | 3 | 1 | ×3.0 |
| k=2, m=1 | 3 | 1 | ×1.5 |
| k=3, m=2 | 5 | 2 | ×1.67 |
| k=4, m=2 | 6 | 2 | ×1.5 |

The RS codec is vendored in `io.q1.cluster.erasure.{Galois,Matrix,ReedSolomon}`,
compatible with Backblaze JavaReedSolomon, no external Maven dependency.

---

## Configuration

| Variable | Default | Description |
|---|---|---|
| `Q1_EC_K` | `0` (disabled) | Number of data shards |
| `Q1_EC_M` | `2` | Number of parity shards |
| `Q1_EC_MIN_SIZE` | `0` | Objects below this threshold → local plain write (no EC) |
| `Q1_REPAIR_INTERVAL_S` | `60` | Repair scanner interval (seconds) |
| `Q1_REPAIR_BATCH_SIZE` | `200` | Keys inspected per repair pass |

**Prerequisite:** `k + m` active nodes at startup (`isClusterReady()` blocks otherwise).

---

## Architecture

```
Client PUT → any node  (EcObjectHandler)
  1. encode(body) → k data shards + m parity shards
  2. buildPayload: prepend 8B originalSize (big-endian long)
  3. fan-out each payload to the corresponding ring node
     → local:  engine.put("__q1_ec_shards__", shardKey, payload)
     → remote: httpShardClient.putShard(node, idx, bucket, key, payload)
  4. await all ACKs (5s timeout)
  5. 200 OK — nothing written to the Raft log

Client GET → any node  (EcObjectHandler)
  1. computeShardPlacement (deterministic ring, no Raft lookup)
  2. fetch k+m payloads in parallel (null on missing/error)
  3. if all null → fallback to engine.get() (pre-EC plain replication object)
  4. if < k payloads present → 503 ServiceUnavailable
  5. ReedSolomon.decodeMissing() on available shards
  6. truncate to originalSize from header → return to client
```

---

## Shard payload format

Each stored shard is self-describing:

```
[8B]  originalSize  (big-endian long)
[…]   RS shard data (zero-padded to shardSize)
```

Neither etcd nor Raft are consulted to resolve the EC schema.
The original size is embedded in each shard; placement is computed locally.

---

## Deterministic placement

`RatisCluster.computeShardPlacement(bucket, key)`:

1. Sort peers by `nodeId` (lexicographic) → ring
2. `anchor = Math.abs((bucket + '\0' + key).hashCode()) % N`
3. Select `k+m` consecutive nodes from `anchor` (wrapping)

Result is stable for a fixed topology. Distributes shards evenly across the cluster.
Every node computes the same placement independently, without coordination.

---

## Shard storage

```
Internal bucket : __q1_ec_shards__    (never in BucketRegistry)
Key             : {userBucket}/{objectKey}/{shardIndex:02d}
Internal key    : __q1_ec_shards__\x00{userBucket}/{objectKey}/{shardIndex:02d}
```

Example: `s3://emails/msg-42`, shard 1:
```
__q1_ec_shards__\x00emails/msg-42/01
```

Reuses the existing segment/RocksDB infrastructure without format changes.

---

## Repair scanner (EcRepairScanner)

Background scanner in `q1-api`, started at boot when EC is enabled.

**Algorithm:**
- Round-robin over 16 partitions
- For each key in `__q1_ec_shards__`: extract bucket/key/shardIdx
- Check each shard's existence via HEAD on the corresponding ring node
- If a shard is missing: reconstruct from the k available ones → push to the target node
- Checkpoint persisted in RocksDB (key `0x00rchk`) for resumption after restart

**Variables:**
- `Q1_REPAIR_INTERVAL_S` (default 60s) — delay between passes
- `Q1_REPAIR_BATCH_SIZE` (default 200) — keys per batch

> **Limitation:** tested on the happy path only. See NEXT.md for planned fault-tolerance tests.

---

## Object listing

`StorageEngine.listPaginated()` scans `__q1_ec_shards__` with prefix `{bucket}/`
and extracts unique object keys (strips `{bucket}/` + `/{shardIdx}`).
EC objects appear in normal S3 listings with no extra configuration.

---

## Compatibility

Objects written before EC was enabled have no shards on any node.
`EcObjectHandler` detects this (all fetches return null/404) and falls back to
`StorageEngine.get()`. Transparent to the client.

---

## Limitations

- **Static cluster**: `k+m` must be ≤ active nodes at startup.
  Elastic re-encoding (add/remove nodes) is not implemented.
- **Objects < `Q1_EC_MIN_SIZE`**: plain local write, no EC and no replication.
- **Size in listings**: always 0 for EC objects. The original size is in each shard
  header but is not extracted during listing (too costly). Planned fix via persistent
  metadata (see NEXT.md).
- **Repair scanner**: not validated against permanent node loss.
