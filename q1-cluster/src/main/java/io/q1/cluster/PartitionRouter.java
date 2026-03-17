package io.q1.cluster;

import java.util.Optional;

/**
 * Translates an S3 {@code (bucket, key)} pair into cluster routing decisions.
 *
 * <p>Uses the same hash function as {@link io.q1.core.StorageEngine} so that
 * every node in the cluster agrees on which partition owns a given key.
 *
 * <h3>Per-partition Raft groups</h3>
 * With per-partition groups each partition independently elects its own leader.
 * Writes for key K are routed to the leader of the partition group that owns K.
 * Bucket-level operations (empty key) are routed to the metadata-group leader.
 *
 * <p>When this node is not a replica for the target partition, the request is
 * forwarded to the first configured replica for that partition; it handles
 * further routing from there.
 */
public final class PartitionRouter {

    private final RatisCluster cluster;

    public PartitionRouter(RatisCluster cluster) {
        this.cluster = cluster;
    }

    /**
     * HTTP base URL of the leader for the given {@code (bucket, key)}, or
     * {@link Optional#empty()} when this node is already the correct leader.
     *
     * <ul>
     *   <li>Empty key (bucket-level ops) → metadata group leader.</li>
     *   <li>Non-empty key (object ops) → partition-specific leader.</li>
     * </ul>
     */
    public Optional<String> leaderBaseUrl(String bucket, String key) {
        if (key == null || key.isEmpty()) {
            return cluster.leaderHttpBaseUrl();
        }
        int p = partitionFor(bucket, key, cluster.config().numPartitions());
        return cluster.leaderHttpBaseUrl(p);
    }

    /**
     * True when the cluster can serve requests (metadata group has a leader
     * and, in EC mode, has enough data nodes).
     */
    public boolean isClusterReady() {
        return cluster.isClusterReady();
    }

    // ── package-visible helper (used by tests) ────────────────────────────

    static int partitionFor(String bucket, String key, int numPartitions) {
        String fullKey = bucket + '\u0000' + key;
        return Math.abs(fullKey.hashCode()) % numPartitions;
    }
}
