package io.q1.cluster;

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
        EcConfig     ecConfig) {

    public ClusterConfig {
        Objects.requireNonNull(self,        "self");
        Objects.requireNonNull(peers,       "peers");
        Objects.requireNonNull(raftDataDir, "raftDataDir");
        Objects.requireNonNull(ecConfig,    "ecConfig");
        if (peers.isEmpty())   throw new IllegalArgumentException("at least one peer required");
        if (numPartitions < 1) throw new IllegalArgumentException("numPartitions >= 1");
        peers = List.copyOf(peers);
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private NodeId       self;
        private List<NodeId> peers         = List.of();
        private int          numPartitions = 16;
        private String       raftDataDir   = "q1-data/raft";
        private EcConfig     ecConfig      = EcConfig.disabled();

        public Builder self(NodeId self)                  { this.self = self;          return this; }
        public Builder peers(List<NodeId> peers)          { this.peers = peers;        return this; }
        public Builder numPartitions(int n)               { this.numPartitions = n;    return this; }
        public Builder raftDataDir(String dir)            { this.raftDataDir = dir;    return this; }
        public Builder ecConfig(EcConfig ec)              { this.ecConfig = ec;        return this; }

        public ClusterConfig build() {
            Objects.requireNonNull(self, "self node is required");
            return new ClusterConfig(self, peers, numPartitions, raftDataDir, ecConfig);
        }
    }
}
