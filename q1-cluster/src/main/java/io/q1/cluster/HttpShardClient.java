package io.q1.cluster;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;

/**
 * HTTP client for shard fan-out operations.
 *
 * <p>Communicates with {@code ShardHandler} on remote nodes via the internal
 * endpoint {@code /internal/v1/shard/{index}/{bucket}/{key}}.
 *
 * <p>Uses JDK {@link HttpClient} backed by a virtual-thread executor —
 * callers are expected to fan-out multiple shard operations in parallel.
 */
public final class HttpShardClient {

    private static final Logger log = LoggerFactory.getLogger(HttpShardClient.class);

    public static final String SHARD_PATH_PREFIX = "/internal/v1/shard/";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final HttpClient http;

    public HttpShardClient() {
        this.http = HttpClient.newBuilder()
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    /**
     * Store {@code shardData} on {@code node} for shard {@code shardIndex} of
     * the given object.
     */
    public void putShard(NodeId node, int shardIndex, String bucket, String key,
                         byte[] shardData) throws IOException {
        URI uri = shardUri(node, shardIndex, bucket, key);
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(TIMEOUT)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(shardData))
                .build();
        HttpResponse<Void> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while putting shard " + shardIndex + " to " + node, e);
        }
        if (resp.statusCode() >= 300) {
            throw new IOException("PUT shard " + shardIndex + " to " + node
                    + " returned HTTP " + resp.statusCode());
        }
        log.debug("PUT shard {}/{}/{}/{:02d} → {}", bucket, key, shardIndex, node);
    }

    /**
     * Retrieve shard {@code shardIndex} for the given object from {@code node}.
     *
     * @return the raw shard bytes, or {@code null} if the shard is not found (404).
     */
    public byte[] getShard(NodeId node, int shardIndex, String bucket,
                           String key) throws IOException {
        URI uri = shardUri(node, shardIndex, bucket, key);
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(TIMEOUT)
                .GET()
                .build();
        HttpResponse<byte[]> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while getting shard " + shardIndex + " from " + node, e);
        }
        if (resp.statusCode() == 404) return null;
        if (resp.statusCode() >= 300) {
            throw new IOException("GET shard " + shardIndex + " from " + node
                    + " returned HTTP " + resp.statusCode());
        }
        return resp.body();
    }

    /**
     * Delete shard {@code shardIndex} for the given object from {@code node}.
     * 404 is treated as success (idempotent).
     */
    public void deleteShard(NodeId node, int shardIndex, String bucket,
                            String key) throws IOException {
        URI uri = shardUri(node, shardIndex, bucket, key);
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(TIMEOUT)
                .DELETE()
                .build();
        HttpResponse<Void> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while deleting shard " + shardIndex + " from " + node, e);
        }
        if (resp.statusCode() >= 300 && resp.statusCode() != 404) {
            throw new IOException("DELETE shard " + shardIndex + " from " + node
                    + " returned HTTP " + resp.statusCode());
        }
        log.debug("DELETE shard {}/{}/{:02d} → {}", bucket, key, shardIndex, node);
    }

    private static URI shardUri(NodeId node, int shardIndex, String bucket, String key) {
        return URI.create(node.httpBase() + SHARD_PATH_PREFIX
                + shardIndex + "/" + bucket + "/" + key);
    }
}
