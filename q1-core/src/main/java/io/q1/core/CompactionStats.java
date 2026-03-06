package io.q1.core;

/**
 * Result of compacting one segment.
 *
 * @param segmentId     the segment that was compacted (reuses same ID)
 * @param originalSize  byte size of the old segment file
 * @param liveBytes     byte size of the new (compacted) segment file
 * @param keysCompacted number of keys successfully moved to the new segment
 * @param keysSkipped   keys whose index entry changed between scan and commit
 */
public record CompactionStats(
        int  segmentId,
        long originalSize,
        long liveBytes,
        int  keysCompacted,
        int  keysSkipped) {}
