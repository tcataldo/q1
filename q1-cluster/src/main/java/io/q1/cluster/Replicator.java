package io.q1.cluster;

import java.io.IOException;

/**
 * Propagates writes to follower nodes after the leader has committed locally.
 *
 * <p>Implementations must be thread-safe; they will be called concurrently
 * from virtual threads handling S3 PUT and DELETE requests.
 */
public interface Replicator {

    /**
     * Replicate a PUT to all required followers and block until
     * {@code replicationFactor - 1} of them have acknowledged.
     *
     * @throws IOException if replication fails or times out
     */
    void replicateWrite(String bucket, String key, byte[] value) throws IOException;

    /**
     * Replicate a DELETE to all required followers.
     *
     * @throws IOException if replication fails or times out
     */
    void replicateDelete(String bucket, String key) throws IOException;
}
