# Segment Compaction — Design Plan

## Problem

Segment files are append-only. Two events produce "dead bytes":

- **Tombstone**: a DELETE writes a TOMBSTONE record but leaves the old DATA value in place.
- **Overwrite**: a PUT on an existing key writes a new DATA record; the old one remains in its segment.

Without compaction, segments grow indefinitely. For 60M emails with a realistic 20% deletion
rate, hundreds of GiB of dead bytes can accumulate. Compaction reclaims that space and
improves read locality.

---

## Goals

1. **Correctness** — no loss of live data, no index corruption.
2. **Concurrency** — reads and writes are not blocked (except for a brief commit window).
3. **Crash-safety** — a crash at any step leaves the system in a consistent state, with no
   full rescan needed on restart.
4. **Minimality** — only compact segments worth compacting (configurable threshold).

---

## Scope

- **Sealed segments only**: the active segment is receiving ongoing writes; never touch it.
- **One partition at a time**: each `Partition` has its own compactor; all 16 partitions
  can run in parallel.
- **Background thread**: compaction runs in a `ScheduledExecutorService` (virtual-thread
  compatible) without blocking the request pool.

---

## Key design decisions

### Computing the dead-byte ratio

For each sealed segment:

```
live_bytes(segmentId) = Σ entry.valueLength() for all RocksDB entries
                        where entry.segmentId() == segmentId
dead_ratio = 1 - live_bytes / segment.size()
```

The RocksDB index iteration runs **outside the writeLock** (read-only; RocksDB is
thread-safe). The result is an approximate snapshot — it will be re-checked at commit.

This iteration costs O(live_keys) with ~20 bytes/key read from the RocksDB cache.
For 60M keys → ~1.2 GB of index data: a few seconds, acceptable for a background process.

### Snapshot of sealed segments (preliminary step, under writeLock)

`candidates()` must first obtain the list of sealed segments safely.
`segments` (ArrayList) is mutated by `maybeRoll()` under `writeLock`; reading its size
without a lock exposes a race on the `active` reference. The correct sequence:

```java
List<Integer> sealedIds;
writeLock.lock();
try {
    // segments.getLast() == active → exclude it
    sealedIds = segments.subList(0, segments.size() - 1)
                        .stream().map(Segment::id).toList();
} finally {
    writeLock.unlock();  // released before any I/O
}
// Entire Phase 1 runs outside the lock: RocksDB iteration + segment reads
```

The lock is held only long enough to copy a list of integers (~µs) — no I/O under lock.
Guarantees the active segment is never included, even if a rollover occurs during the pass.

### Candidate selection

Among the sealed segments snapshotted above, a segment is a candidate if
`dead_ratio > Q1_COMPACT_THRESHOLD` (default: **0.5**).
Candidates are sorted by descending `dead_ratio` (most degraded first) and capped at
`Q1_COMPACT_MAX_SEGMENTS` per pass (default: **4**).

### Compaction algorithm for one segment

```
Phase 1 — Scan (no lock)
  1. Iterate RocksDB to collect all (key, Entry) pairs pointing to this segment.
  2. For each live key, read the value from the old segment.
  3. Write to a temp file segment-NNNNNNNNNN.q1.compact
     (same binary format as normal segments).
  4. Accumulate a list of moves: (key, old_entry, new_entry).

Phase 2 — Commit (under writeLock, duration ~ms)
  5. For each move:
     - Verify index.get(key).equals(old_entry) — the key may have been
       updated or deleted between Phase 1 and here.
     - If valid: add (key → new_entry) to a RocksDB WriteBatch.
  6. Apply the WriteBatch atomically.
  7. Rename segment-NNNNNNNNNN.q1.compact → segment-NNNNNNNNNN.q1
     (the new ID is chosen before Phase 1).
  8. Mark the old segment as dead:
     rename old file to segment-NNNNNNNNNN.q1.dead
  9. Remove the old segment from `segments` and `byId`.
 10. Add the new segment to `segments` and `byId`.
```

### Crash safety

| Crash point | On-disk state | State on restart |
|---|---|---|
| Phase 1 (writing .compact) | partial .compact | `openSegments()` ignores `*.compact` → deleted |
| Between WriteBatch and rename (step 7) | complete .compact, index up to date | missing rename → .compact detected, renamed |
| Between rename and .dead (step 8) | old .q1 + new .q1 | index points to new; old = 0 live → compacted next pass |
| After .dead, before deletion | .dead on disk | `openSegments()` ignores `*.dead` → deleted |

Golden rule: **the RocksDB WriteBatch is the commit operation**. Once applied, the index
is the source of truth. All orphaned files are cleaned up on startup.

### Commit scheduling

The **Partition writeLock** is acquired only for steps 5–10.
Reads (`get`, `exists`) and sync streams (`openSyncStream`) are concurrent — they access
`byId` via ConcurrentHashMap, which remains consistent throughout the transition.

### Key freshness check (step 5)

```java
RocksDbIndex.Entry current = index.get(key);
if (current == null)               continue; // deleted
if (!current.equals(oldEntry))     continue; // moved / overwritten
// ↑ covers: same segmentId but different offset (overwrite in-place is impossible
//            in append-only, but defense in depth)
batch.put(key, newEntry);
```

---

## New environment variables

| Variable | Default | Description |
|---|---|---|
| `Q1_COMPACT_THRESHOLD` | `0.5` | Minimum dead_ratio to trigger compaction |
| `Q1_COMPACT_INTERVAL_S` | `300` | Check interval in seconds |
| `Q1_COMPACT_MAX_SEGMENTS` | `4` | Max segments compacted per partition per pass |

---

## Implementation plan

### New files

#### `q1-core/src/main/java/io/q1/core/CompactionStats.java`
```
record CompactionStats(
    int segmentId,
    long originalSize,
    long liveBytes,
    int keysCompacted,
    int keysSkipped   // keys invalidated between scan and commit
)
```

#### `q1-core/src/main/java/io/q1/core/Compactor.java`
Package-private class internal to `Partition`. Responsibilities:
- `List<Integer> candidates(double threshold)` — returns candidate segmentIds sorted by dead_ratio
- `CompactionStats compact(int segmentId)` — executes both phases
- Manages `.compact` and `.dead` files

### Changes to `Partition.java`

- Add `Compactor compactor` (initialized in the constructor).
- Expose `compactSegment(int segmentId)` (delegates to `Compactor`).
- `openSegments()`: ignore `*.compact` and delete `*.dead` on startup.
- Startup recovery: if a `.compact` file exists whose ID matches an already-committed
  WriteBatch (index points to it), rename it; otherwise delete it. See below.

#### `.compact` recovery on startup

```
For each segment-NNN.q1.compact found in the directory:
  Open for reading, verify the first record is valid (MAGIC ok).
  Check in RocksDB if any entries point to this ID.
  → Yes: rename to segment-NNN.q1 (commit succeeded but rename was missed).
  → No:  delete (Phase 1 incomplete or WriteBatch not committed).
```

### Changes to `StorageEngine.java`

- Add `ScheduledExecutorService compactionScheduler` (virtual thread pool).
- On startup: schedule `runCompaction()` every `Q1_COMPACT_INTERVAL_S` seconds.
- `runCompaction()`: for each partition, call `partition.compactIfNeeded(threshold, maxSegments)`.
- In `close()`: `compactionScheduler.shutdownNow()`.

### Changes to `CLAUDE.md`

Remove the "Segment compaction" TODO from the roadmap section, add to the existing section.

---

## Edge cases

| Case | Expected behavior |
|---|---|
| Active segment | Never selected (explicitly excluded) |
| Segment with 0 live keys | dead_ratio = 1.0 → top candidate; Phase 2: empty WriteBatch + direct deletion |
| Concurrent compaction of two segments sharing keys | Impossible — a key can only belong to one segment at a time in the index |
| Key moved to a segment being compacted | Index points to the active segment (more recent) → freshness check skips it |
| Crash mid Phase 1 | Partial `.compact` file → deleted on startup |
| Compacting an already-compacted segment | `candidates()` finds it with dead_ratio = 0 → not a candidate |

---

## Test strategy

### Unit tests (`PartitionTest`)

- `compactionRemovesTombstones`: PUT + DELETE × N, verify compaction reduces on-disk size.
- `compactionPreservesLiveData`: PUT 100 keys, DELETE 50, compact, verify the 50 remaining are readable.
- `compactionHandlesConcurrentWrite`: concurrent compaction + PUT on the same key → no loss or corruption.
- `compactionSkipsActiveSegment`: active segment is never compacted even if `dead_ratio` > threshold.
- `compactionCrashRecovery`: simulate a crash after Phase 1 (`.compact` file on disk) → clean restart.

### Integration tests (`S3CompatibilityIT`)

Verify all 12 existing tests pass after N forced compaction cycles.

---

## Out of scope (initial version)

- **Multi-segment compaction**: merging several small segments into one large one (merge
  compaction). Useful for reducing the number of open files, but adds complexity.
- **Tiered compaction** (LSM-style): not needed here because segments are written in
  chronological order, not by level.
- **Erasure coding**: post-RF, unrelated to local compaction.
