package io.q1.core;

/**
 * The write position of one partition on a given node.
 *
 * <p>{@code segmentId = 0} is the sentinel meaning "no data at all" —
 * the follower has never received anything for this partition.
 * The leader will then stream from its very first segment.
 */
public record SyncState(int partitionId, int segmentId, long byteOffset) {

    /** Sentinel: the follower has no data for this partition. */
    public static SyncState empty(int partitionId) {
        return new SyncState(partitionId, 0, 0);
    }

    public boolean isEmpty() { return segmentId == 0; }
}
