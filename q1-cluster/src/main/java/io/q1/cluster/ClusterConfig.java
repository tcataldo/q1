package io.q1.cluster;

import java.util.List;
import java.util.Objects;

/**
 * Immutable cluster configuration.
 *
 * <pre>{@code
 * ClusterConfig cfg = ClusterConfig.builder()
 *     .self(new NodeId("node-1", "10.0.0.1", 9000))
 *     .etcdEndpoints(List.of("http://etcd:2379"))
 *     .replicationFactor(2)
 *     .build();
 * }</pre>
 */
public record ClusterConfig(
        NodeId       self,
        List<String> etcdEndpoints,
        int          replicationFactor,
        int          numPartitions,
        int          leaseTtlSeconds,
        EcConfig     ecConfig) {

    public ClusterConfig {
        Objects.requireNonNull(self, "self");
        Objects.requireNonNull(etcdEndpoints, "etcdEndpoints");
        Objects.requireNonNull(ecConfig, "ecConfig");
        if (etcdEndpoints.isEmpty()) throw new IllegalArgumentException("at least one etcd endpoint required");
        if (replicationFactor < 1)  throw new IllegalArgumentException("replicationFactor >= 1");
        if (numPartitions < 1)      throw new IllegalArgumentException("numPartitions >= 1");
        if (leaseTtlSeconds < 2)    throw new IllegalArgumentException("leaseTtlSeconds >= 2");
        etcdEndpoints = List.copyOf(etcdEndpoints);
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private NodeId       self;
        private List<String> etcdEndpoints  = List.of("http://localhost:2379");
        private int          replicationFactor = 1;
        private int          numPartitions  = 16;
        private int          leaseTtlSeconds = 10;
        private EcConfig     ecConfig        = EcConfig.disabled();

        public Builder self(NodeId self)                     { this.self = self; return this; }
        public Builder etcdEndpoints(List<String> endpoints) { this.etcdEndpoints = endpoints; return this; }
        public Builder replicationFactor(int rf)             { this.replicationFactor = rf; return this; }
        public Builder numPartitions(int n)                  { this.numPartitions = n; return this; }
        public Builder leaseTtlSeconds(int ttl)              { this.leaseTtlSeconds = ttl; return this; }
        public Builder ecConfig(EcConfig ec)                 { this.ecConfig = ec; return this; }

        public ClusterConfig build() {
            Objects.requireNonNull(self, "self node is required");
            return new ClusterConfig(self, etcdEndpoints, replicationFactor, numPartitions,
                    leaseTtlSeconds, ecConfig);
        }
    }
}
