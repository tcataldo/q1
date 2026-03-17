package io.q1.cluster;

import java.util.Objects;

/**
 * Immutable identity of a cluster node.
 *
 * <p>Wire format (5-segment): {@code id:host:httpPort:raftPort:grpcPort},
 * e.g. {@code node-1:10.0.0.1:9000:6000:7000}.
 *
 * <p>The legacy 4-segment format ({@code id:host:httpPort:raftPort}) is still
 * accepted by {@link #fromWire}; the gRPC port then defaults to
 * {@code raftPort + 1000}.
 */
public record NodeId(String id, String host, int port, int raftPort, int grpcPort) {

    public NodeId {
        Objects.requireNonNull(id,   "id");
        Objects.requireNonNull(host, "host");
        if (port     < 1 || port     > 65535) throw new IllegalArgumentException("invalid port: " + port);
        if (raftPort < 1 || raftPort > 65535) throw new IllegalArgumentException("invalid raftPort: " + raftPort);
        if (grpcPort < 1 || grpcPort > 65535) throw new IllegalArgumentException("invalid grpcPort: " + grpcPort);
    }

    /** Backward-compatible constructor; grpcPort defaults to {@code raftPort + 1000}. */
    public NodeId(String id, String host, int port, int raftPort) {
        this(id, host, port, raftPort, raftPort + 1000);
    }

    /** Human-readable {@code host:httpPort}. */
    public String address() { return host + ":" + port; }

    /** Base URL for S3 HTTP calls to this node. */
    public String httpBase() { return "http://" + host + ":" + port; }

    /** {@code host:raftPort} used as the gRPC address for Raft RPC. */
    public String raftAddress() { return host + ":" + raftPort; }

    /** {@code host:grpcPort} used as the address for Q1 internal gRPC calls. */
    public String grpcAddress() { return host + ":" + grpcPort; }

    /** Serialise to 5-segment wire format {@code id:host:httpPort:raftPort:grpcPort}. */
    public String toWire() { return id + ":" + host + ":" + port + ":" + raftPort + ":" + grpcPort; }

    /**
     * Parse from wire format.
     * Accepts both 5-segment ({@code id:host:httpPort:raftPort:grpcPort}) and
     * legacy 4-segment ({@code id:host:httpPort:raftPort}) formats.
     */
    public static NodeId fromWire(String s) {
        String[] p = s.split(":", 5);
        if (p.length == 4) {
            return new NodeId(p[0], p[1], Integer.parseInt(p[2]), Integer.parseInt(p[3]));
        }
        if (p.length == 5) {
            return new NodeId(p[0], p[1],
                    Integer.parseInt(p[2]), Integer.parseInt(p[3]), Integer.parseInt(p[4]));
        }
        throw new IllegalArgumentException("Bad NodeId wire format: " + s);
    }

    @Override public String toString() { return id + "@" + address(); }
}
