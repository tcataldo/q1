package io.q1.cluster;

import java.util.List;
import java.util.Optional;

/**
 * Translates an S3 {@code (bucket, key)} pair into cluster routing decisions.
 *
 * <p>Uses the same hash function as {@link io.q1.core.StorageEngine} so that
 * every node in the cluster agrees on which partition owns a given key.
 */
public final class PartitionRouter {

    private final EtcdCluster cluster;
    private final int         numPartitions;

    public PartitionRouter(EtcdCluster cluster) {
        this.cluster       = cluster;
        this.numPartitions = cluster.config().numPartitions();
    }

    /** True if this node is the current leader for the partition owning {@code key}. */
    public boolean isLocalLeader(String bucket, String key) {
        return cluster.isLocalLeader(partitionFor(bucket, key));
    }

    /**
     * The HTTP base URL of the leader for this key, or {@link Optional#empty()} if:
     * <ul>
     *   <li>this node is already the leader, or</li>
     *   <li>no leader has been elected yet (election in progress).</li>
     * </ul>
     */
    public Optional<String> leaderBaseUrl(String bucket, String key) {
        int partId = partitionFor(bucket, key);
        return cluster.leaderFor(partId)
                .filter(n -> !n.equals(cluster.self()))
                .map(NodeId::httpBase);
    }

    /**
     * Followers for the partition owning {@code key}.
     * The leader calls this to know where to replicate.
     */
    public List<NodeId> followersFor(String bucket, String key) {
        return cluster.followersFor(partitionFor(bucket, key));
    }

    /**
     * True if the cluster has at least RF active nodes, meaning all data
     * should be available.  Returns false when the cluster is under-replicated
     * and the node should reject client requests with 503.
     */
    public boolean isClusterReady() {
        return cluster.isClusterReady();
    }

    // ── internal ──────────────────────────────────────────────────────────

    /**
     * Must match {@code StorageEngine.partition(bucket, key)} exactly.
     * Both use {@code Math.abs((bucket + '\x00' + key).hashCode()) % numPartitions}.
     */
    static int partitionFor(String bucket, String key, int numPartitions) {
        String fullKey = bucket + '\u0000' + key;
        return Math.abs(fullKey.hashCode()) % numPartitions;
    }

    private int partitionFor(String bucket, String key) {
        return partitionFor(bucket, key, numPartitions);
    }
}
