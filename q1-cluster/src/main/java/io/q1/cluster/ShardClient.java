package io.q1.cluster;

import java.io.IOException;

/**
 * Transport-agnostic interface for EC shard fan-out operations.
 *
 * <p>The default implementation is {@link HttpShardClient} (plain HTTP).
 * The gRPC implementation ({@code GrpcShardClient} in q1-api-grpc) is a drop-in
 * replacement for inter-node calls.
 */
public interface ShardClient {

    /**
     * Store {@code shardData} on {@code node} for shard {@code shardIndex} of
     * the given object.
     */
    void putShard(NodeId node, int shardIndex, String bucket, String key,
                  byte[] shardData) throws IOException;

    /**
     * Retrieve shard {@code shardIndex} for the given object from {@code node}.
     *
     * @return the raw shard bytes, or {@code null} if the shard is not found.
     */
    byte[] getShard(NodeId node, int shardIndex, String bucket, String key) throws IOException;

    /**
     * Returns {@code true} if the remote node has shard {@code shardIndex} for
     * the given object, {@code false} if it is absent.
     */
    boolean shardExists(NodeId node, int shardIndex, String bucket, String key) throws IOException;

    /**
     * Delete shard {@code shardIndex} for the given object from {@code node}.
     * Implementations must treat a missing shard as success (idempotent).
     */
    void deleteShard(NodeId node, int shardIndex, String bucket, String key) throws IOException;
}
