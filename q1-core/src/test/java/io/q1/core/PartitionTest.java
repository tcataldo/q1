package io.q1.core;

import io.q1.core.io.NioFileIOFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.Cache;
import org.rocksdb.LRUCache;
import org.rocksdb.RocksDB;

import java.io.InputStream;
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
}
