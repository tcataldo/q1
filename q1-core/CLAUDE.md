# q1-core — Storage Engine

Self-contained module: no internal Q1 dependencies.

## Responsibilities

Everything related to physical data storage:
- Object write / read / delete
- Append-only segment files with in-memory index
- I/O abstraction (`FileIO` / `FileIOFactory`, NIO by default)
- Synchronization API for cluster catchup

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

- Rollover at **1 GiB** (`Partition.MAX_SEGMENT_SIZE` constant)
- Naming: `segment-0000000001.q1`, `segment-0000000002.q1`, …

## Internal key

`bucket + '\x00' + objectKey` — null byte is the separator (cannot appear in a valid S3 key).

## Partitioning

`Math.abs(fullKey.hashCode()) % numPartitions` — consistent with `PartitionRouter` in q1-cluster.

## Key files

| File | Role |
|---|---|
| `StorageEngine.java` | Entry point: routes operations to the correct partition |
| `Partition.java` | Manages a directory of segments + the in-memory index |
| `Segment.java` | Read/write of a single segment file; `scanStream()` for network sync |
| `SegmentIndex.java` | `ConcurrentHashMap<String, Entry(segId, valueOffset, valueLen)>` |
| `BucketRegistry.java` | Bucket registry, persisted in `buckets.properties` |
| `SyncState.java` | `record(partitionId, segmentId, byteOffset)` — replication position |
| `io/FileIO.java` | I/O interface (read, write, size, force, close) |
| `io/NioFileIOFactory.java` | Default NIO implementation |

## Unit tests

```bash
mvn test -pl q1-core
# 27 tests: SegmentTest (9), PartitionTest (13), StorageEngineSyncTest (5)
```

## TODO

- [ ] CRC32 verification on reads (stored in header but not yet checked)
- [ ] Compaction: remove tombstones when delete ratio exceeds a configurable threshold
- [ ] Metrics: compaction rate, segment sizes, key count per partition
- [ ] `listObjectsV2` pagination (continuation token)
