package io.q1.cluster;

/**
 * Shared shard-key constants used by both the HTTP shard endpoint (ShardHandler in q1-api)
 * and the gRPC shard service (ShardServiceImpl in q1-api-grpc).
 *
 * <p>Centralised here in q1-cluster so that both modules can reference them without
 * creating a mutual dependency.
 */
public final class ShardStorage {

    /** Internal StorageEngine bucket for all EC shards (never user-visible). */
    public static final String SHARD_BUCKET = "__q1_ec_shards__";

    private ShardStorage() {}

    /**
     * Internal storage key for shard {@code index} of object {@code bucket/key}.
     * Format: {@code {bucket}/{key}/{index:02d}}
     */
    public static String shardKey(String bucket, String key, int index) {
        return bucket + "/" + key + "/" + String.format("%02d", index);
    }
}
