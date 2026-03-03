package io.q1.core;

/**
 * Callback used when scanning a {@link Segment} to (re)build the in-memory index.
 * Called once per record in file order.
 */
@FunctionalInterface
public interface RecordVisitor {

    /**
     * @param key         the object key (bucket\0objectKey internally)
     * @param flags       {@link Segment#FLAG_DATA} or {@link Segment#FLAG_TOMB}
     * @param segmentId   which segment file contains this record
     * @param valueOffset byte offset within the segment where value bytes start
     * @param valueLength number of value bytes (0 for tombstones)
     */
    void visit(String key, byte flags, int segmentId, long valueOffset, long valueLength);
}
