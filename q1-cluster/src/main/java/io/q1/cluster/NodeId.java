package io.q1.cluster;

import java.util.Objects;

/**
 * Immutable identity of a cluster node.
 *
 * <p>Wire format: {@code id:host:httpPort:raftPort},
 * e.g. {@code node-1:10.0.0.1:9000:6000}.
 */
public record NodeId(String id, String host, int port, int raftPort) {

    public NodeId {
        Objects.requireNonNull(id,   "id");
        Objects.requireNonNull(host, "host");
        if (port     < 1 || port     > 65535) throw new IllegalArgumentException("invalid port: " + port);
        if (raftPort < 1 || raftPort > 65535) throw new IllegalArgumentException("invalid raftPort: " + raftPort);
    }

    /** Human-readable {@code host:httpPort}. */
    public String address() { return host + ":" + port; }

    /** Base URL for S3 HTTP calls to this node. */
    public String httpBase() { return "http://" + host + ":" + port; }

    /** {@code host:raftPort} used as the gRPC address for Raft RPC. */
    public String raftAddress() { return host + ":" + raftPort; }

    /** Serialise to wire format {@code id:host:httpPort:raftPort}. */
    public String toWire() { return id + ":" + host + ":" + port + ":" + raftPort; }

    /** Parse from wire format {@code id:host:httpPort:raftPort}. */
    public static NodeId fromWire(String s) {
        String[] p = s.split(":", 4);
        if (p.length != 4) throw new IllegalArgumentException("Bad NodeId wire format: " + s);
        return new NodeId(p[0], p[1], Integer.parseInt(p[2]), Integer.parseInt(p[3]));
    }

    @Override public String toString() { return id + "@" + address(); }
}
