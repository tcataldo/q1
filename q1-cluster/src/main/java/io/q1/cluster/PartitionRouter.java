package io.q1.cluster;

import java.util.Optional;

/**
 * Translates an S3 {@code (bucket, key)} pair into cluster routing decisions.
 *
 * <p>Uses the same hash function as {@link io.q1.core.StorageEngine} so that
 * every node in the cluster agrees on which partition owns a given key.
 *
 * <p>With a single global Raft group there is one leader for the whole cluster,
 * so {@link #isLocalLeader} and {@link #leaderBaseUrl} delegate to the
 * {@link RatisCluster} without per-partition logic.
 */
public final class PartitionRouter {

    private final RatisCluster cluster;

    public PartitionRouter(RatisCluster cluster) {
        this.cluster = cluster;
    }

    /** True if this node is the current Raft leader (handles all writes). */
    public boolean isLocalLeader(String bucket, String key) {
        return cluster.isLocalLeader();
    }

    /**
     * HTTP base URL of the Raft leader, or {@link Optional#empty()} when this
     * node is already the leader or leadership is unknown.
     */
    public Optional<String> leaderBaseUrl(String bucket, String key) {
        return cluster.leaderHttpBaseUrl();
    }

    /**
     * True when the cluster can serve requests (has a leader and, in EC mode,
     * has enough nodes).
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
