package io.q1.core;

import io.q1.core.io.FailingFileIOFactory;
import io.q1.core.io.NioFileIOFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.Cache;
import org.rocksdb.LRUCache;
import org.rocksdb.RocksDB;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that I/O failures (e.g. disk full / ENOSPC) do not corrupt data.
 *
 * <p>Key invariants under test:
 * <ul>
 *   <li>{@link Segment#writeFully} only advances {@code writePos} after a
 *       successful write — a mid-write failure leaves {@code writePos} at the
 *       record boundary so the next append safely overwrites partial garbage.</li>
 *   <li>{@link Partition#put} updates the RocksDB index only after a successful
 *       segment write — a failed PUT leaves the index consistent.</li>
 *   <li>{@link Partition#delete} updates the index only after a successful
 *       tombstone write — a failed DELETE leaves the key readable.</li>
 *   <li>A crash during compaction Phase 1 leaves the original segment intact;
 *       the orphaned {@code .compact} file is cleaned up on the next restart.</li>
 * </ul>
 */
class DiskFullTest {

    @TempDir Path tmp;

    Cache                cache;
    FailingFileIOFactory ioFactory;
    Partition            partition;
    Path                 partDir;

    @BeforeEach
    void setUp() throws Exception {
        RocksDB.loadLibrary();
        cache     = new LRUCache(8 << 20);
        ioFactory = new FailingFileIOFactory(NioFileIOFactory.INSTANCE);
        partDir   = tmp.resolve("p0");
        partition = new Partition(0, partDir, ioFactory, cache);
    }

    @AfterEach
    void tearDown() throws Exception {
        ioFactory.setFailWrites(false); // ensure partition can close cleanly
        if (partition != null) partition.close();
        cache.close();
    }

    // ── PUT failure ────────────────────────────────────────────────────────

    @Test
    void putThrowsOnDiskFull() {
        ioFactory.setFailWrites(true);
        assertThrows(IOException.class,
                () -> partition.put("b\u0000key", "value".getBytes()));
    }

    @Test
    void indexRemainsConsistentAfterPutFailure() throws Exception {
        partition.put("b\u0000existing", "data".getBytes());

        ioFactory.setFailWrites(true);
        assertThrows(IOException.class,
                () -> partition.put("b\u0000newkey", "value".getBytes()));
        ioFactory.setFailWrites(false);

        // Previously stored key still readable
        assertArrayEquals("data".getBytes(), partition.get("b\u0000existing"));

        // Failed key must not appear in the index
        assertNull(partition.get("b\u0000newkey"));
        assertFalse(partition.exists("b\u0000newkey"));
    }

    @Test
    void subsequentWriteSucceedsAfterTransientFailure() throws Exception {
        partition.put("b\u0000before", "v1".getBytes());

        ioFactory.setFailWrites(true);
        assertThrows(IOException.class,
                () -> partition.put("b\u0000transient", "bad".getBytes()));
        ioFactory.setFailWrites(false);

        // Next write starts cleanly at the un-advanced writePos
        partition.put("b\u0000after", "v2".getBytes());

        assertArrayEquals("v1".getBytes(), partition.get("b\u0000before"));
        assertNull(partition.get("b\u0000transient"));
        assertArrayEquals("v2".getBytes(), partition.get("b\u0000after"));
    }

    // ── DELETE failure ─────────────────────────────────────────────────────

    @Test
    void deleteThrowsOnDiskFull() throws Exception {
        partition.put("b\u0000key", "value".getBytes());

        ioFactory.setFailWrites(true);
        assertThrows(IOException.class,
                () -> partition.delete("b\u0000key"));
        ioFactory.setFailWrites(false);

        // The tombstone write failed, so the index was not modified
        assertArrayEquals("value".getBytes(), partition.get("b\u0000key"));
        assertTrue(partition.exists("b\u0000key"));
    }

    // ── Compaction failure ─────────────────────────────────────────────────

    @Test
    void compactionPhase1FailurePreservesAllData() throws Exception {
        partition.put("b\u0000k1", "v1".getBytes());
        partition.put("b\u0000k2", "v2".getBytes());
        partition.delete("b\u0000k1"); // introduce dead bytes so the segment qualifies
        partition.forceRoll();         // seal the segment

        // Disk full during Phase 1 (writing the .compact temp file)
        ioFactory.setFailWrites(true);
        assertThrows(IOException.class,
                () -> partition.compactIfNeeded(0.0, 4));
        ioFactory.setFailWrites(false);

        // Original segment is untouched; index is unchanged
        assertNull(partition.get("b\u0000k1")); // was deleted before compaction
        assertArrayEquals("v2".getBytes(), partition.get("b\u0000k2"));
    }

    // ── Crash recovery ─────────────────────────────────────────────────────

    @Test
    void orphanedCompactFileCleanedOnRestart() throws Exception {
        partition.put("b\u0000k", "v".getBytes());
        partition.close();
        partition = null;

        // Simulate a crash that left a .compact file whose WriteBatch was
        // never committed — use a segment ID (999) with no index entries.
        Path orphan = partDir.resolve("segment-0000000999.q1.compact");
        Files.writeString(orphan, "garbage");

        // Restart: openSegments() should delete the orphan (no index entries for seg 999)
        partition = new Partition(0, partDir, ioFactory, cache);

        assertFalse(Files.exists(orphan), ".compact file should be cleaned up on restart");
        // Existing data must survive
        assertArrayEquals("v".getBytes(), partition.get("b\u0000k"));
    }
}
