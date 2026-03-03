package io.q1.cluster;

import java.util.Objects;

/**
 * Immutable identity of a cluster node.
 *
 * <p>The wire format stored in etcd is {@code id:host:port},
 * e.g. {@code node-7f3a:10.0.0.1:9000}.
 */
public record NodeId(String id, String host, int port) {

    public NodeId {
        Objects.requireNonNull(id,   "id");
        Objects.requireNonNull(host, "host");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("invalid port: " + port);
    }

    /** Human-readable {@code host:port}. */
    public String address() { return host + ":" + port; }

    /** Base URL for internal HTTP calls to this node. */
    public String httpBase() { return "http://" + host + ":" + port; }

    /** Serialise to the etcd wire format {@code id:host:port}. */
    public String toWire() { return id + ":" + host + ":" + port; }

    /** Parse from the etcd wire format {@code id:host:port}. */
    public static NodeId fromWire(String s) {
        String[] parts = s.split(":", 3);
        if (parts.length != 3) throw new IllegalArgumentException("Bad NodeId wire format: " + s);
        return new NodeId(parts[0], parts[1], Integer.parseInt(parts[2]));
    }

    @Override public String toString() { return id + "@" + address(); }
}
