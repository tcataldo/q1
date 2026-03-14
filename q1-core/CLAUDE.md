# q1-core — Storage Engine

Self-contained module: no internal Q1 dependencies.

## Responsibilities

Everything related to physical data storage:
- Object write / read / delete
- Append-only segment files with RocksDB persistent index (no rescan on startup)
- Compaction: two-phase crash-safe tombstone cleanup
- I/O abstraction (`FileIO` / `FileIOFactory`, NIO by default)
- `sync()` / `force()` API for fsync before Raft snapshot

## Segment format

```
[4B] MAGIC    0x51310001
[1B] FLAGS    0x00=DATA | 0x01=TOMBSTONE
[2B] KEY_LEN  unsigned short
[8B] VAL_LEN  long (0 for tombstones)
[4B] CRC32    covers flags + key bytes + value bytes
[KEY_LEN B]   key (UTF-8)
[VAL_LEN B]   value (absent for tombstones)
```

- Rollover at **1 GiB** (`Partition.MAX_SEGMENT_SIZE`)
- Naming: `segment-0000000001.q1`, `segment-0000000002.q1`, …
- CRC32 verified on `scan()` and `scanStream()` — **not on direct `Segment.read()`** (GET path)

## Internal key

`bucket + '\x00' + objectKey` — null byte is the separator (cannot appear in a valid S3 key).

## Partitioning

`Math.abs(fullKey.hashCode()) % numPartitions` — consistent with `PartitionRouter` in q1-cluster.

## Compaction

Two-phase crash-safe (see COMPACTION.md):
1. Write a new segment with only live keys (no tombstones)
2. Atomically replace via filesystem rename + RocksDB update
3. Delete the old segment

Triggered per-partition when dead-byte ratio exceeds a configurable threshold.
A summary INFO log is emitted at the end of each compaction pass.

## Key files

| File | Role |
|---|---|
| `StorageEngine.java` | Entry point: routes operations to the correct partition; `sync()` for fsync |
| `Partition.java` | Manages a directory of segments + RocksDB index; `force()` for fsync |
| `Segment.java` | Read/write of a single segment file; `scanStream()` for sync; `force()` for fsync |
| `RocksDbIndex.java` | Persistent index: `Entry(segmentId, valueOffset, valueLength)`, 20 bytes big-endian |
| `BucketRegistry.java` | Bucket registry, persisted in `buckets.properties` |
| `io/FileIO.java` | I/O interface (read, write, size, force, close) |
| `io/NioFileIOFactory.java` | Default NIO implementation |

## Unit tests

```bash
mvn test -pl q1-core
# 38 tests: SegmentTest (9), PartitionTest (24), StorageEngineSyncTest (5)
```

## TODO

- [ ] CRC32 verification on direct GET path (`Segment.read()`)
  — requires storing `keyLen` in `RocksDbIndex.Entry` (20 → 22 bytes)
  to compute `headerOffset = valueOffset - HEADER_SIZE - keyLen`
- [ ] Metrics: compaction rate, segment count, live key count per partition
