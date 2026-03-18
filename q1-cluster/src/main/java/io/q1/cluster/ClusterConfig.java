package io.q1.cluster;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Immutable cluster configuration.
 *
 * <pre>{@code
 * ClusterConfig cfg = ClusterConfig.builder()
 *     .self(new NodeId("node-1", "10.0.0.1", 9000, 6000))
 *     .peers(List.of(
 *         new NodeId("node-1", "10.0.0.1", 9000, 6000),
 *         new NodeId("node-2", "10.0.0.2", 9000, 6000),
 *         new NodeId("node-3", "10.0.0.3", 9000, 6000)))
 *     .build();
 * }</pre>
 */
public record ClusterConfig(
        NodeId       self,
        List<NodeId> peers,
        int          numPartitions,
        String       raftDataDir,
        EcConfig     ecConfig,
        String       raftGroupId,
        int          rf,
        int          maxObjectSizeMb) {

    public ClusterConfig {
        Objects.requireNonNull(self,        "self");
        Objects.requireNonNull(peers,       "peers");
        Objects.requireNonNull(raftDataDir, "raftDataDir");
        Objects.requireNonNull(ecConfig,    "ecConfig");
        if (peers.isEmpty())        throw new IllegalArgumentException("at least one peer required");
        if (numPartitions < 1)      throw new IllegalArgumentException("numPartitions >= 1");
        if (rf < 1)                 throw new IllegalArgumentException("rf >= 1");
        if (maxObjectSizeMb < 1)    throw new IllegalArgumentException("maxObjectSizeMb >= 1");
        peers = List.copyOf(peers);
    }

    /**
     * Returns the RF replica nodes for the given partition, chosen
     * deterministically by round-robin over the sorted peer list.
     *
     * <p>The effective RF is {@code min(rf, peers.size())}.
     */
    public List<NodeId> replicas(int partitionId) {
        List<NodeId> sorted = peers.stream()
                .sorted(Comparator.comparing(NodeId::id))
                .toList();
        int n         = sorted.size();
        int effective = Math.min(rf, n);
        List<NodeId> result = new ArrayList<>(effective);
        for (int i = 0; i < effective; i++) {
            result.add(sorted.get((partitionId + i) % n));
        }
        return result;
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private NodeId       self;
        private List<NodeId> peers         = List.of();
        private int          numPartitions = 16;
        private String       raftDataDir   = "q1-data/raft";
        private EcConfig     ecConfig      = EcConfig.disabled();
        private String       raftGroupId   = null;
        /** Sentinel: use all peers as replicas (RF = N). */
        private int          rf               = Integer.MAX_VALUE;
        private int          maxObjectSizeMb  = 32;

        public Builder self(NodeId self)                  { this.self = self;                 return this; }
        public Builder peers(List<NodeId> peers)          { this.peers = peers;               return this; }
        public Builder numPartitions(int n)               { this.numPartitions = n;           return this; }
        public Builder raftDataDir(String dir)            { this.raftDataDir = dir;           return this; }
        public Builder ecConfig(EcConfig ec)              { this.ecConfig = ec;               return this; }
        /** Override the Raft group namespace UUID (tests use this to isolate groups). */
        public Builder raftGroupId(String id)             { this.raftGroupId = id;            return this; }
        /**
         * Replication factor per partition (default: all peers).
         * Set {@code Q1_RF} in production to enable write distribution.
         */
        public Builder rf(int rf)                         { this.rf = rf;                     return this; }
        /**
         * Maximum object size in MB. Drives Raft {@code appenderBufferByteLimit} and the
         * gRPC message size cap. Objects larger than this value cannot be written.
         * Default: 32 MB (covers all real-world email attachments).
         */
        public Builder maxObjectSizeMb(int mb)            { this.maxObjectSizeMb = mb;        return this; }

        public ClusterConfig build() {
            Objects.requireNonNull(self, "self node is required");
            int resolvedRf = Math.min(rf, Math.max(1, peers.size()));
            return new ClusterConfig(self, peers, numPartitions, raftDataDir, ecConfig,
                    raftGroupId, resolvedRf, maxObjectSizeMb);
        }
    }
}
