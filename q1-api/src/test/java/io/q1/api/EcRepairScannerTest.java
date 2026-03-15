package io.q1.api;

import io.q1.api.handler.ShardHandler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the static parsing helpers of {@link EcRepairScanner}.
 */
class EcRepairScannerTest {

    /** Convenience prefix: {@code "__q1_ec_shards__\x00"} */
    private static final String PREFIX = ShardHandler.SHARD_BUCKET + "\u0000";

    @Test
    void objectIdFromInternalKey_validSingleComponentKey() {
        String key = PREFIX + "mybucket/mykey/00";
        assertEquals("mybucket/mykey", EcRepairScanner.objectIdFromInternalKey(key));
    }

    @Test
    void objectIdFromInternalKey_keyWithNestedPath() {
        String key = PREFIX + "mybucket/path/to/object/01";
        assertEquals("mybucket/path/to/object",
                EcRepairScanner.objectIdFromInternalKey(key));
    }

    @Test
    void objectIdFromInternalKey_shardIndex02() {
        String key = PREFIX + "bucket/key/02";
        assertEquals("bucket/key", EcRepairScanner.objectIdFromInternalKey(key));
    }

    @Test
    void objectIdFromInternalKey_wrongBucket_returnsNull() {
        // Not starting with the shard-bucket prefix
        String key = "otherbucket\u0000mybucket/key/00";
        assertNull(EcRepairScanner.objectIdFromInternalKey(key));
    }

    @Test
    void objectIdFromInternalKey_noSlashAfterPrefix_returnsNull() {
        // shardObjectKey has no slash — invalid shard key format
        String key = PREFIX + "noslash";
        assertNull(EcRepairScanner.objectIdFromInternalKey(key));
    }

    @Test
    void objectIdFromInternalKey_emptyAfterPrefix_returnsNull() {
        assertNull(EcRepairScanner.objectIdFromInternalKey(PREFIX));
    }

    @Test
    void objectIdFromInternalKey_completelyUnrelated_returnsNull() {
        assertNull(EcRepairScanner.objectIdFromInternalKey("bucket\u0000key"));
    }
}
