package io.q1.cluster;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Replicates writes to followers using the standard S3 HTTP endpoint,
 * marked with the {@value #REPLICA_HEADER} header so followers do not
 * re-replicate.
 *
 * <p>Uses JDK {@link HttpClient} backed by a virtual-thread executor.
 * Fan-out is parallel; the call blocks until {@code replicationFactor - 1}
 * followers have acknowledged (or the timeout fires).
 */
public final class HttpReplicator implements Replicator {

    private static final Logger log = LoggerFactory.getLogger(HttpReplicator.class);

    /** HTTP header that marks a request as an internal replica write. */
    public static final String REPLICA_HEADER = "X-Q1-Replica-Write";

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final PartitionRouter router;
    private final int             replicationFactor;
    private final HttpClient      http;

    public HttpReplicator(PartitionRouter router, int replicationFactor) {
        this.router            = router;
        this.replicationFactor = replicationFactor;
        this.http = HttpClient.newBuilder()
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    @Override
    public void replicateWrite(String bucket, String key, byte[] value) throws IOException {
        List<NodeId> targets = targetFollowers(bucket, key);
        if (targets.isEmpty()) return;

        List<CompletableFuture<Void>> futs = targets.stream()
                .map(node -> sendPut(node, bucket, key, value))
                .toList();

        await(futs, bucket, key, "PUT");
    }

    @Override
    public void replicateDelete(String bucket, String key) throws IOException {
        List<NodeId> targets = targetFollowers(bucket, key);
        if (targets.isEmpty()) return;

        List<CompletableFuture<Void>> futs = targets.stream()
                .map(node -> sendDelete(node, bucket, key))
                .toList();

        await(futs, bucket, key, "DELETE");
    }

    // ── private ───────────────────────────────────────────────────────────

    /**
     * How many followers we must reach: RF - 1 (leader already has the data).
     * We pick the first N available followers.
     */
    private List<NodeId> targetFollowers(String bucket, String key) {
        int needed = replicationFactor - 1;   // leader counts as 1
        if (needed <= 0) return List.of();
        List<NodeId> all = router.followersFor(bucket, key);
        return all.size() <= needed ? all : all.subList(0, needed);
    }

    private CompletableFuture<Void> sendPut(NodeId node, String bucket, String key, byte[] value) {
        URI uri = URI.create(node.httpBase() + "/" + bucket + "/" + key);
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(TIMEOUT)
                .header(REPLICA_HEADER, "true")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(value))
                .build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.discarding())
                .thenAccept(resp -> {
                    if (resp.statusCode() >= 300) {
                        throw new RuntimeException(
                                "Replica PUT to " + node + " returned HTTP " + resp.statusCode());
                    }
                    log.debug("Replicated PUT s3://{}/{} → {}", bucket, key, node);
                });
    }

    private CompletableFuture<Void> sendDelete(NodeId node, String bucket, String key) {
        URI uri = URI.create(node.httpBase() + "/" + bucket + "/" + key);
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(TIMEOUT)
                .header(REPLICA_HEADER, "true")
                .DELETE()
                .build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.discarding())
                .thenAccept(resp -> {
                    if (resp.statusCode() >= 300 && resp.statusCode() != 404) {
                        throw new RuntimeException(
                                "Replica DELETE to " + node + " returned HTTP " + resp.statusCode());
                    }
                    log.debug("Replicated DELETE s3://{}/{} → {}", bucket, key, node);
                });
    }

    private static void await(List<CompletableFuture<Void>> futs,
                               String bucket, String key, String op) throws IOException {
        try {
            CompletableFuture.allOf(futs.toArray(new CompletableFuture[0]))
                    .get((long) TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new IOException("Replication timeout for " + op + " s3://" + bucket + "/" + key);
        } catch (ExecutionException e) {
            throw new IOException("Replication failed for " + op + " s3://" + bucket + "/" + key,
                    e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Replication interrupted", e);
        }
    }
}
