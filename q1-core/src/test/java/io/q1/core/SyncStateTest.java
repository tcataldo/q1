package io.q1.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SyncStateTest {

    @Test
    void emptyHasSegmentIdZero() {
        assertEquals(0, SyncState.empty(3).segmentId());
    }

    @Test
    void emptyIsEmpty() {
        assertTrue(SyncState.empty(7).isEmpty());
    }

    @Test
    void emptyPreservesPartitionId() {
        assertEquals(5, SyncState.empty(5).partitionId());
    }

    @Test
    void emptyHasZeroByteOffset() {
        assertEquals(0L, SyncState.empty(0).byteOffset());
    }

    @Test
    void nonZeroSegmentIdIsNotEmpty() {
        assertFalse(new SyncState(0, 1, 100).isEmpty());
    }

    @Test
    void segmentIdZeroIsAlwaysEmpty() {
        // isEmpty() depends only on segmentId, not partitionId or byteOffset
        assertTrue(new SyncState(2, 0, 999).isEmpty());
    }

    @Test
    void recordEquality() {
        assertEquals(new SyncState(1, 2, 300), new SyncState(1, 2, 300));
        assertNotEquals(new SyncState(1, 2, 300), new SyncState(1, 2, 301));
        assertNotEquals(new SyncState(1, 2, 300), new SyncState(1, 3, 300));
    }

    @Test
    void emptyAcrossPartitions() {
        for (int p = 0; p < 16; p++) {
            SyncState s = SyncState.empty(p);
            assertEquals(p, s.partitionId());
            assertTrue(s.isEmpty());
        }
    }
}
