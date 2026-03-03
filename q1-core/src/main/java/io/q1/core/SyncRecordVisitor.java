package io.q1.core;

/**
 * Callback used when parsing a sync stream (leader → follower catchup).
 * Unlike {@link RecordVisitor}, this variant also receives the value bytes
 * because the data is coming from a network stream, not a local file.
 */
@FunctionalInterface
public interface SyncRecordVisitor {
    void visit(String key, byte flags, byte[] value);
}
