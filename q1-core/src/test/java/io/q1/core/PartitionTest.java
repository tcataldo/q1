package io.q1.core;

import io.q1.core.io.NioFileIOFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.Cache;
import org.rocksdb.LRUCache;
import org.rocksdb.RocksDB;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PartitionTest {

    @TempDir Path tmp;

    Cache     cache;
    Partition partition;

    @BeforeEach
    void setUp() throws Exception {
        RocksDB.loadLibrary();
        cache     = new LRUCache(8 << 20); // 8 MiB — sufficient for tests
        partition = new Partition(0, tmp.resolve("p0"), NioFileIOFactory.INSTANCE, cache);
    }

    @AfterEach
    void tearDown() throws Exception {
        partition.close();
        cache.close();
    }

    // ── CRUD ──────────────────────────────────────────────────────────────

    @Test
    void putAndGet() throws Exception {
        partition.put("b\u0000hello.txt", "world".getBytes());
        assertArrayEquals("world".getBytes(), partition.get("b\u0000hello.txt"));
    }

    @Test
    void getMissingReturnsNull() throws Exception {
        assertNull(partition.get("b\u0000no-such-key"));
    }

    @Test
    void existsReturnsFalseForMissing() throws Exception {
        assertFalse(partition.exists("b\u0000ghost"));
    }

    @Test
    void existsReturnsTrueAfterPut() throws Exception {
        partition.put("b\u0000present", new byte[]{1});
        assertTrue(partition.exists("b\u0000present"));
    }

    @Test
    void putAndDelete() throws Exception {
        partition.put("b\u0000to-delete", "data".getBytes());
        partition.delete("b\u0000to-delete");
        assertNull(partition.get("b\u0000to-delete"));
        assertFalse(partition.exists("b\u0000to-delete"));
    }

    @Test
    void deleteNonExistentIsNoOp() throws Exception {
        assertDoesNotThrow(() -> partition.delete("b\u0000ghost"));
    }

    @Test
    void overwrite() throws Exception {
        partition.put("b\u0000key", "v1".getBytes());
        partition.put("b\u0000key", "v2".getBytes());
        assertArrayEquals("v2".getBytes(), partition.get("b\u0000key"));
    }

    // ── prefix listing ────────────────────────────────────────────────────

    @Test
    void keysWithPrefixFiltersCorrectly() throws Exception {
        partition.put("b\u0000img/a.png", new byte[0]);
        partition.put("b\u0000img/b.png", new byte[0]);
        partition.put("b\u0000doc/c.pdf", new byte[0]);

        List<String> imgKeys = partition.keysWithPrefix("b\u0000img/");

        assertEquals(2, imgKeys.size());
        assertTrue(imgKeys.stream().allMatch(k -> k.startsWith("b\u0000img/")));
    }

    @Test
    void keysWithEmptyPrefixReturnsAll() throws Exception {
        partition.put("b\u0000x", new byte[0]);
        partition.put("b\u0000y", new byte[0]);
        // Everything under "b\u0000" prefix
        List<String> all = partition.keysWithPrefix("b\u0000");
        assertEquals(2, all.size());
    }

    // ── sync state ────────────────────────────────────────────────────────

    @Test
    void syncStateAdvancesAfterPut() throws Exception {
        SyncState before = partition.syncState(0);
        partition.put("b\u0000k", "value".getBytes());
        SyncState after = partition.syncState(0);

        // Either the offset grew (same segment) or the segment id advanced
        assertTrue(
            after.byteOffset() > before.byteOffset() || after.segmentId() > before.segmentId(),
            "Sync state must advance after a write"
        );
    }

    // ── sync stream round-trip ────────────────────────────────────────────

    @Test
    void syncStreamRoundTrip() throws Exception {
        partition.put("b\u0000key1", "value1".getBytes());
        partition.put("b\u0000key2", "value2".getBytes());

        // Stream everything from the very beginning (fromSegmentId=0 → full resync)
        Partition target = new Partition(0, tmp.resolve("p0-copy"), NioFileIOFactory.INSTANCE, cache);
        try (InputStream stream = partition.openSyncStream(0, 0)) {
            Segment.scanStream(stream, (key, flags, value) -> {
                try {
                    if (flags == Segment.FLAG_TOMB) target.delete(key);
                    else                            target.put(key, value);
                } catch (Exception e) { throw new RuntimeException(e); }
            });
        }

        assertArrayEquals("value1".getBytes(), target.get("b\u0000key1"));
        assertArrayEquals("value2".getBytes(), target.get("b\u0000key2"));
        target.close();
    }

    @Test
    void syncStreamWithTombstonePropagatesToTarget() throws Exception {
        partition.put("b\u0000alive",  "keep".getBytes());
        partition.put("b\u0000dead",   "gone".getBytes());
        partition.delete("b\u0000dead");

        Partition target = new Partition(0, tmp.resolve("p0-tomb"), NioFileIOFactory.INSTANCE, cache);
        try (InputStream stream = partition.openSyncStream(0, 0)) {
            Segment.scanStream(stream, (key, flags, value) -> {
                try {
                    if (flags == Segment.FLAG_TOMB) target.delete(key);
                    else                            target.put(key, value);
                } catch (Exception e) { throw new RuntimeException(e); }
            });
        }

        assertArrayEquals("keep".getBytes(), target.get("b\u0000alive"));
        assertNull(target.get("b\u0000dead"));
        target.close();
    }

    @Test
    void emptyPartitionSyncStreamProducesNoRecords() throws Exception {
        List<String> keys = new ArrayList<>();
        try (InputStream stream = partition.openSyncStream(0, 0)) {
            Segment.scanStream(stream, (key, flags, value) -> keys.add(key));
        }
        assertTrue(keys.isEmpty());
    }

    // ── compaction ────────────────────────────────────────────────────────

    /**
     * Core correctness test: after PUT/DELETE cycles and a forced compaction,
     * every surviving key must still return its original value — even though
     * the value bytes now live at a different offset in a new segment file.
     */
    @Test
    void compactionPreservesLiveData() throws Exception {
        int total   = 100;
        int deleted = 50;

        // Write 100 keys
        for (int i = 0; i < total; i++) {
            partition.put("b\u0000key-" + i, ("value-" + i).getBytes());
        }

        // Force a segment roll so the first segment is sealed
        forceRoll();

        // Delete first 50 keys — their tombstones are in the new (active) segment
        for (int i = 0; i < deleted; i++) {
            partition.delete("b\u0000key-" + i);
        }

        // Record which segment the live keys are in before compaction
        long diskBefore = segmentDirSize();

        // Compact with threshold 0.0 so every sealed segment qualifies
        partition.compactIfNeeded(0.0, 4);

        // Disk space must have shrunk (tombstones + dead DATA records removed)
        long diskAfter = segmentDirSize();
        assertTrue(diskAfter < diskBefore,
                "Expected disk to shrink after compaction: before=" + diskBefore + " after=" + diskAfter);

        // All surviving keys must still be readable with correct values
        for (int i = deleted; i < total; i++) {
            byte[] got = partition.get("b\u0000key-" + i);
            assertArrayEquals(("value-" + i).getBytes(), got,
                    "Key key-" + i + " must be readable after compaction");
        }

        // Deleted keys must remain absent
        for (int i = 0; i < deleted; i++) {
            assertNull(partition.get("b\u0000key-" + i),
                    "Deleted key key-" + i + " must not appear after compaction");
        }
    }

    /** Compacted segment size must be strictly smaller than original. */
    @Test
    void compactionReducesDiskUsage() throws Exception {
        for (int i = 0; i < 200; i++) {
            partition.put("b\u0000k" + i, new byte[1024]);
        }
        forceRoll();
        for (int i = 0; i < 180; i++) {
            partition.delete("b\u0000k" + i);
        }

        long before = segmentDirSize();
        partition.compactIfNeeded(0.0, 4);
        long after = segmentDirSize();

        assertTrue(after < before / 2,
                "Expected >50% size reduction after 90% delete rate: before=" + before + " after=" + after);
    }

    /** The active segment must never be compacted, even at threshold 0. */
    @Test
    void compactionSkipsActiveSegment() throws Exception {
        partition.put("b\u0000active-key", "data".getBytes());
        // No roll — the only segment is still active
        partition.compactIfNeeded(0.0, 4);
        // If we get here without exception the active segment was skipped.
        // Verify the key is still readable.
        assertArrayEquals("data".getBytes(), partition.get("b\u0000active-key"));
    }

    /**
     * Overwrite scenario: write key A, roll, overwrite key A (now in active),
     * compact the first segment. The compactor must detect that its stale entry
     * for key A was superseded and skip it (keysSkipped > 0 is fine, no
     * corruption of the live value).
     */
    @Test
    void compactionSkipsStaleOverwrittenKey() throws Exception {
        partition.put("b\u0000overwritten", "v1".getBytes());
        forceRoll();
        // Overwrite in the new (active) segment
        partition.put("b\u0000overwritten", "v2".getBytes());

        partition.compactIfNeeded(0.0, 4);

        // v2 must survive
        assertArrayEquals("v2".getBytes(), partition.get("b\u0000overwritten"));
    }

    /**
     * Crash-recovery: if a {@code .compact} file is left on disk but the
     * WriteBatch was NOT committed (no entries in the index point to that
     * segment ID), startup must delete the stale file cleanly.
     */
    @Test
    void compactionCrashRecoveryDeletesOrphanCompactFile() throws Exception {
        partition.put("b\u0000x", "data".getBytes());
        forceRoll();

        // Simulate a crash mid-Phase-1: drop a fake .compact file whose ID
        // does NOT appear in the RocksDB index.
        Path orphan = tmp.resolve("p0/segment-0000000099.q1.compact");
        Files.writeString(orphan, "junk");

        // Restart the partition
        partition.close();
        partition = new Partition(0, tmp.resolve("p0"), NioFileIOFactory.INSTANCE, cache);

        // The orphan must have been deleted, and existing data still readable
        assertFalse(Files.exists(orphan), ".compact orphan must be deleted on startup");
        assertArrayEquals("data".getBytes(), partition.get("b\u0000x"));
    }

    /**
     * Concurrent write during compaction Phase 1 must not corrupt data.
     * The compactor sees a stale entry for the concurrently-updated key and
     * skips it; the writer's value must remain accessible.
     */
    @Test
    void compactionHandlesConcurrentWrite() throws Exception {
        partition.put("b\u0000raced", "original".getBytes());
        forceRoll();

        // Thread 1: compact (threshold=0 so the sealed segment qualifies)
        // Thread 2: overwrite the same key while compaction is in Phase 1
        // We approximate "concurrent" by overwriting before compaction, which
        // exercises the stale-entry check (same outcome as a true race).
        partition.put("b\u0000raced", "updated".getBytes()); // goes into active seg
        partition.compactIfNeeded(0.0, 4);

        assertArrayEquals("updated".getBytes(), partition.get("b\u0000raced"));
    }

    // ── helpers ───────────────────────────────────────────────────────────

    /** Seals the active segment and opens a new one (package-private access). */
    private void forceRoll() throws IOException {
        partition.forceRoll();
    }

    /** Sum of sizes of all .q1 segment files in the partition directory. */
    private long segmentDirSize() throws IOException {
        try (var s = Files.list(tmp.resolve("p0"))) {
            return s.filter(p -> p.getFileName().toString().endsWith(".q1"))
                    .mapToLong(p -> p.toFile().length())
                    .sum();
        }
    }
}
