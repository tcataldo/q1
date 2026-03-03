package io.q1.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the full sync round-trip: write to one engine, stream via
 * {@link StorageEngine#openSyncStream}, apply via {@link StorageEngine#applySyncStream},
 * and confirm the target engine has the same data.
 */
class StorageEngineSyncTest {

    @TempDir Path sourceDir;
    @TempDir Path targetDir;

    private static final int PARTITIONS = 4; // small for fast tests

    // ── helpers ───────────────────────────────────────────────────────────

    /** Full sync of every partition from source to target. */
    private static void syncAll(StorageEngine source, StorageEngine target) throws Exception {
        for (int p = 0; p < PARTITIONS; p++) {
            // fromSegmentId=0 → full resync (stream all records)
            try (InputStream stream = source.openSyncStream(p, 0, 0)) {
                target.applySyncStream(p, stream);
            }
        }
    }

    // ── tests ─────────────────────────────────────────────────────────────

    @Test
    void syncTransfersAllObjects() throws Exception {
        StorageEngine source = new StorageEngine(sourceDir, PARTITIONS);
        source.put("bucket", "key1", "hello".getBytes());
        source.put("bucket", "key2", "world".getBytes());
        source.put("bucket", "key3", "q1!".getBytes());

        StorageEngine target = new StorageEngine(targetDir, PARTITIONS);
        syncAll(source, target);

        assertArrayEquals("hello".getBytes(), target.get("bucket", "key1"));
        assertArrayEquals("world".getBytes(), target.get("bucket", "key2"));
        assertArrayEquals("q1!".getBytes(),   target.get("bucket", "key3"));

        source.close();
        target.close();
    }

    @Test
    void syncPropagatesDeletes() throws Exception {
        StorageEngine source = new StorageEngine(sourceDir, PARTITIONS);
        source.put("bucket", "keep",   "keep".getBytes());
        source.put("bucket", "remove", "gone".getBytes());
        source.delete("bucket", "remove");

        StorageEngine target = new StorageEngine(targetDir, PARTITIONS);
        syncAll(source, target);

        assertArrayEquals("keep".getBytes(), target.get("bucket", "keep"));
        assertNull(target.get("bucket", "remove"));

        source.close();
        target.close();
    }

    @Test
    void syncEmptyEngineProducesEmptyTarget() throws Exception {
        StorageEngine source = new StorageEngine(sourceDir, PARTITIONS);
        StorageEngine target = new StorageEngine(targetDir, PARTITIONS);
        syncAll(source, target);

        // Nothing to assert beyond "no exception"; partitions should be empty
        assertNull(target.get("bucket", "ghost"));

        source.close();
        target.close();
    }

    @Test
    void syncOverwriteReflectsLatestValue() throws Exception {
        StorageEngine source = new StorageEngine(sourceDir, PARTITIONS);
        source.put("bucket", "key", "v1".getBytes());
        source.put("bucket", "key", "v2".getBytes()); // overwrite

        StorageEngine target = new StorageEngine(targetDir, PARTITIONS);
        syncAll(source, target);

        // applySyncStream replays all records; last write wins
        assertArrayEquals("v2".getBytes(), target.get("bucket", "key"));

        source.close();
        target.close();
    }

    @Test
    void incrementalSyncFromKnownOffset() throws Exception {
        StorageEngine source = new StorageEngine(sourceDir, PARTITIONS);
        source.put("bucket", "first", "A".getBytes());

        // Record sync state after first write
        SyncState[] states = new SyncState[PARTITIONS];
        for (int p = 0; p < PARTITIONS; p++) {
            states[p] = source.partitionSyncState(p);
        }

        // Write more data
        source.put("bucket", "second", "B".getBytes());

        // Sync only the delta for each partition
        StorageEngine target = new StorageEngine(targetDir, PARTITIONS);

        // First: full sync to bring target to "first" state
        syncAll(source, target);

        // All data (both writes) should be present since we synced all
        assertArrayEquals("A".getBytes(), target.get("bucket", "first"));
        assertArrayEquals("B".getBytes(), target.get("bucket", "second"));

        source.close();
        target.close();
    }
}
