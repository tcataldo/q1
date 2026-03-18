# Q1 — On-Disk Format

This document is the authoritative reference for every file and directory that Q1
writes to disk.  It covers the storage engine (segments, RocksDB index), the bucket
registry, the Raft log and snapshots, and the transient compaction artefacts.

---

## 1. Directory layout

```
$Q1_DATA_DIR/                        (default: q1-data)
  buckets.properties                 bucket registry
  p00/                               partition 0
    segment-0000000001.q1            sealed segment
    segment-0000000002.q1            active segment (last one)
    segment-0000000003.q1.compact    transient — compaction Phase 1 in progress
    segment-0000000002.q1.dead       transient — compaction rename in progress
    keyindex/                        RocksDB database (persistent index)
  p01/ … p15/                        partitions 1–15 (same structure)
  raft/                              Apache Ratis data (P+1 Raft groups)
    <metaGroupId>/                   metadata group — bucket ops, RF=N
      current/
        raft-meta.conf               current Raft term + voted-for peer
        log_inprogress_<n>/          open Raft log segment
        log_<start>-<end>            closed Raft log segment
        snapshot/
          state_machine/
            snapshot.<term>_<index>  empty marker file (see §6)
    <partGroupId-0>/                 partition 0 group (RF replicas)
    <partGroupId-1>/                 partition 1 group
    …                                one directory per hosted partition group
```

**Partition routing:**
`partitionIndex = Math.abs((bucket + '\u0000' + objectKey).hashCode()) % numPartitions`
Default: 16 partitions (`p00`–`p15`).

---

## 2. Bucket registry — `buckets.properties`

Java `Properties` file format (ISO-8859-1, `#` comment lines):

```
# Q1 bucket registry — do not edit manually
# Tue Mar 17 22:00:00 UTC 2026
photos=1710000000000
emails=1710000001234
```

Each entry is `bucketName=creationEpochMillis`.
Managed by `BucketRegistry`; written atomically (truncate + rewrite) on every
create/delete.  Not replicated via Raft in the current version — each node has its
own copy.

---

## 3. Segment files — `segment-{0000000001}.q1`

### 3.1 Naming

Segments are numbered from 1, zero-padded to 10 digits:

```
segment-0000000001.q1
segment-0000000002.q1
…
```

The last segment in the directory is the **active** one (accepts writes).
All others are **sealed** (read-only).

**Rollover threshold:** 1 GiB (`Partition.DEFAULT_MAX_SEGMENT_SIZE = 1 << 30`).
A new segment is created when the active segment reaches this size.

### 3.2 Record format (MAGIC `0x51310002`)

Records are packed sequentially from offset 0 with no padding.

```
Offset  Size  Field
──────  ────  ──────────────────────────────────────────────────────────────
0       4     MAGIC        0x51310002  big-endian int
4       1     FLAGS        0x00 = DATA | 0x01 = TOMBSTONE
5       2     KEY_LEN      unsigned short, big-endian (0–65535)
7       8     VAL_LEN      long, big-endian (0 for tombstones)
15      4     HEADER_CRC   CRC32, big-endian
               covers: FLAGS(1 byte) + key bytes + value bytes
19      KEY_LEN  key       UTF-8 bytes of the internal key (bucket\x00objectKey)
19+KEY_LEN  VAL_LEN  value  raw object bytes (absent for tombstones)
19+KEY_LEN+VAL_LEN  4  FOOTER_CRC  same CRC32 value repeated
```

**Header size:** 19 bytes (`HEADER_SIZE = 4 + 1 + 2 + 8 + 4`).
**Footer size:** 4 bytes (`FOOTER_SIZE = 4`).
**Total overhead per record:** 23 bytes + KEY_LEN + VAL_LEN.

The footer repeats the header CRC at a fixed position relative to the start of the
value bytes.  If VAL_LEN is corrupted (e.g. a partial write), the footer lands at
the wrong file offset and the CRC comparison fails before the bad data is delivered
to the index — this is the sole purpose of the footer.

### 3.3 Internal key format

```
bucket + '\u0000' + objectKey
```

The null byte (`0x00`) is the separator.  It cannot appear in a valid S3 key, so the
concatenation is unambiguous.  The full string is encoded as UTF-8.

### 3.4 Index offset stored in RocksDB

The value stored in the RocksDB index is the **byte offset where value bytes start**
inside the segment file:

```
valueOffset = recordStart + HEADER_SIZE + KEY_LEN
            = recordStart + 19 + KEY_LEN
```

This allows a direct pread without re-parsing the header on the GET path.

### 3.5 Tombstone semantics

A TOMBSTONE record has VAL_LEN = 0 and no value bytes.  It is written by
`Partition.delete()` and causes the key to be removed from the RocksDB index.
The tombstone itself persists in the segment until the segment is compacted away.

---

## 4. RocksDB persistent index — `keyindex/`

One embedded RocksDB database per partition, located at `p{N}/keyindex/`.

### 4.1 Key encoding

RocksDB key = raw UTF-8 bytes of the internal key (`bucket\x00objectKey`).
RocksDB's default unsigned-byte lexicographic order matches S3 key order for
ASCII bucket names.

**Special key — EC repair checkpoint:**
Bytes `{0x00, 'r', 'c', 'h', 'k'}` (hex: `00 72 63 68 6B`).
Sorts before all bucket names (which cannot start with `\x00`).
Value: UTF-8 string of the last shard key processed by the EC repair scanner.

### 4.2 Value encoding — 22 bytes, big-endian

```
Offset  Size  Field
──────  ────  ──────────────────────────────────────────────────────────────
0       4     segmentId    int — which segment file holds this value
4       8     valueOffset  long — byte offset in that segment where value bytes start
12      8     valueLength  long — number of value bytes
20      2     keyLen       unsigned short — UTF-8 byte length of the internal key
```

`keyLen` is used to reconstruct `headerOffset = valueOffset - HEADER_SIZE - keyLen`
when CRC-verifying a direct GET.

**Legacy entries (20 bytes, no keyLen):** written before the CRC-on-GET feature was
added.  Recognised by `raw.length < 22`; `keyLen` is treated as `-1` and CRC
verification is skipped for those entries.

### 4.3 RocksDB tuning

| Parameter | Value | Rationale |
|---|---|---|
| Bloom filter | 10 bits/key | Avoids disk reads for absent-key lookups |
| Block cache | 256 MiB shared across all partitions | Controlled memory usage |
| Level compaction | dynamic level | Bounds space amplification at scale |
| createIfMissing | true | Fresh partition directory creates a new empty DB |

### 4.4 Startup / rebuild

On `Partition` open:

1. If `keyindex/` **does not exist** — RocksDB creates a fresh empty DB; the index
   is rebuilt from all segment files (see §4.5).
2. If `keyindex/` **exists but RocksDB throws `IOException`** (e.g. corrupt MANIFEST)
   — the directory is deleted, a fresh empty DB is created, and the index is rebuilt.
3. Normal case — RocksDB opens successfully; no segment scan is performed.

### 4.5 Index rebuild algorithm

Segments are scanned in creation order (segment ID ascending).  For each record:
- `FLAG_DATA` → `index.put(key, Entry(segId, valueOffset, valueLen, keyLen))`
- `FLAG_TOMB` → `index.remove(key)`

Because records are processed in append order, the last write for any key wins.
The rebuild is crash-safe: if it is interrupted, the partition simply rebuilds again
on the next startup.

---

## 5. Compaction temporary files

Compaction uses a two-phase algorithm to avoid data loss on crash.

### 5.1 File names

| Suffix | Meaning |
|---|---|
| `.q1` | Normal segment (sealed or active) |
| `.q1.compact` | Phase 1 output — new segment being written (live keys only) |
| `.q1.dead` | Phase 2 intermediate — old segment renamed away, pending delete |

### 5.2 Phase 1 — Scan (no lock held)

1. Walk the RocksDB index to find all live keys pointing to the target segment.
2. Read each value from the old segment.
3. Write them to `segment-{N}.q1.compact` (same segment ID, new file).

### 5.3 Phase 2 — Commit (write lock, ≈ ms)

1. Verify that each key's RocksDB entry has not changed since Phase 1.
2. Build a RocksDB `WriteBatch` with updated offsets pointing to `.compact`.
3. **Commit point:** `index.applyBatch(batch)` — atomic RocksDB write.
4. Rename `segment-{N}.q1` → `segment-{N}.q1.dead` (atomic).
5. Rename `segment-{N}.q1.compact` → `segment-{N}.q1` (atomic).
6. Delete `segment-{N}.q1.dead`.

### 5.4 Crash recovery at startup (`Partition.openSegments`)

| File found at startup | Action |
|---|---|
| `*.q1.dead` | Delete immediately — old segment was already superseded by the `.compact` rename |
| `*.q1.compact` with RocksDB entries for its segment ID | Rename to `.q1` — Phase 2 rename was interrupted after the WriteBatch commit |
| `*.q1.compact` with no RocksDB entries for its segment ID | Delete — Phase 1 was interrupted before the WriteBatch commit |

### 5.5 Fully dead segments

If no live key points to a segment, it is removed in one step (no `.compact` file
needed): rename to `.dead`, remove from memory, delete.

---

## 6. Raft data — `raft/`

### 6.1 Location

`$Q1_DATA_DIR/raft/` — created by `RatisCluster` via `Files.createDirectories()`.

### 6.2 Structure

Apache Ratis manages this directory internally.  Q1 does not write to it directly.

```
raft/
  <metaGroupId>/           UUID(msb, -1L)  — metadata group (bucket ops, RF=N)
    current/
      raft-meta.conf       current term + voted-for peer ID (text format)
      log_inprogress_<n>/  open Raft log segment (WAL, Protobuf records)
      log_<a>-<b>          closed Raft log segment spanning indices a–b
      snapshot/
        state_machine/
          snapshot.<term>_<index>   empty marker file (see §6.4)
  <partGroupId-0>/         UUID(msb, 0)    — partition 0 group
  <partGroupId-1>/         UUID(msb, 1)    — partition 1 group
  …                        one directory per hosted partition group
```

One `RaftServer` hosts P+1 Raft groups: one group per partition plus one metadata group.
All group UUIDs are derived from the namespace UUID configured via `Q1_RAFT_GROUP_ID`:
`metaGroup = UUID(msb, -1L)`, `partGroup(p) = UUID(msb, p)`.
With the default of 16 partitions, a node hosts at most `ceil(RF × 16 / N) + 1` groups
(the metadata group is always present on every node).

### 6.3 Raft log entry payload — `RatisCommand` binary encoding

Each `StateMachineLogEntry.logData` is a `RatisCommand`:

```
Offset  Size  Field
──────  ────  ──────────────────────────────────────────────────────────────
0       1     type         0x01=PUT  0x02=DELETE  0x03=CREATE_BUCKET  0x04=DELETE_BUCKET
1       2     bucketLen    unsigned short
3       bucketLen  bucket  UTF-8
3+bucketLen  2  keyLen     unsigned short (0 for CREATE/DELETE_BUCKET)
…       keyLen  key        UTF-8 (absent for CREATE/DELETE_BUCKET)
…       8     valueLen     long (PUT only)
…       valueLen  value    raw bytes (PUT only)
```

### 6.4 State machine snapshots

**The snapshot file is empty.**

Q1's storage state (segment files + RocksDB index) is already durable on disk as
part of the `StorageEngine`.  A snapshot therefore only records the `(term, index)`
at which the engine is consistent.  That boundary is encoded in the filename by
Ratis's `SimpleStateMachineStorage`:

```
snapshot.{term}_{index}     e.g. snapshot.3_10000
```

**Before writing the snapshot**, `StorageEngine.sync()` is called — this calls
`fsync` on the active segment of every partition.  This guarantees that all applied
log entries are durable before Ratis purges them from the log.

Auto-trigger threshold: every **10 000 committed log entries**.

### 6.5 Startup replay

On restart:
1. Ratis loads the latest snapshot file and fast-forwards the state machine to its
   `(term, index)`.
2. Only the log entries **after** the snapshot index are replayed by
   `Q1StateMachine.applyTransaction()`.

Because the `StorageEngine` state is already on disk, replaying a `PUT` that was
already applied is idempotent (it overwrites the same bytes at the same key).

---

## 7. EC shard storage

When `Q1_EC_K > 0`, erasure-coded shards are stored in the same segment/index
system using a reserved internal bucket:

```
bucket  = "__q1_ec_shards__"
key     = "{userBucket}/{objectKey}/{shardIndex:02d}"
```

The shard **payload** is self-describing:

```
Offset  Size  Field
──────  ────  ──────────────────────────────────────────────────────────────
0       8     originalSize   long, big-endian — size of the original object
8       N     shardData      raw Reed-Solomon shard bytes
```

No Raft metadata is stored for EC objects.  The repair checkpoint for the EC
background scanner is stored as a special RocksDB key (see §4.1).

---

## 8. Invariants and safety properties

| Property | Guarantee |
|---|---|
| Segment files are append-only | Existing byte ranges are never modified in-place |
| CRC covers flags + key + value | Detects bit-rot and partial writes at scan / scanStream time |
| Footer CRC | Independently detects VAL_LEN corruption before data is delivered to the index |
| RocksDB WriteBatch = compaction commit | Crash between Phase 1 and Phase 2 commit leaves data readable from the original segment |
| `StorageEngine.sync()` before snapshot | No applied entry is lost when Ratis purges the log after a snapshot |
| Index auto-rebuild on corruption | A missing or corrupt `keyindex/` is rebuilt from segments; no data loss as long as segment files are intact |
