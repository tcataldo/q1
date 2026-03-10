package io.q1.api.handler;

import io.q1.cluster.EcConfig;
import io.q1.cluster.EcMetadata;
import io.q1.cluster.EcMetadataStore;
import io.q1.cluster.ErasureCoder;
import io.q1.cluster.EtcdCluster;
import io.q1.cluster.HttpShardClient;
import io.q1.cluster.NodeId;
import io.q1.cluster.ShardPlacement;
import io.q1.core.StorageEngine;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.StatusCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Handles object PUT / GET / DELETE using Reed-Solomon erasure coding.
 *
 * <p>Used instead of {@link ObjectHandler} when {@link EcConfig#enabled()} is true.
 *
 * <h3>PUT</h3>
 * Encodes the object into {@code k+m} shards, distributes each shard to its
 * designated node (local write or HTTP PUT to a remote node), then writes
 * {@link EcMetadata} to etcd as the atomic commit point.
 *
 * <h3>GET</h3>
 * Reads {@link EcMetadata} from etcd, fetches all {@code k+m} shards in
 * parallel (missing/failed ones are {@code null}), then reconstructs the
 * original bytes via {@link ErasureCoder#decode}.  Falls back to local
 * {@link StorageEngine#get} for objects written before EC was enabled.
 *
 * <h3>DELETE</h3>
 * Reads metadata, fans out shard deletes (best-effort), then removes metadata
 * from etcd.  Falls back to local delete for non-EC objects.
 */
public final class EcObjectHandler {

    private static final Logger log = LoggerFactory.getLogger(EcObjectHandler.class);

    private static final long SHARD_TIMEOUT_MS = 5_000;

    private final StorageEngine    engine;
    private final EtcdCluster      cluster;
    private final ErasureCoder     coder;
    private final EcMetadataStore  metaStore;
    private final HttpShardClient  shardClient;
    private final EcConfig         ecConfig;

    public EcObjectHandler(StorageEngine engine,
                           EtcdCluster cluster,
                           ErasureCoder coder,
                           EcMetadataStore metaStore,
                           HttpShardClient shardClient) {
        this.engine      = engine;
        this.cluster     = cluster;
        this.coder       = coder;
        this.metaStore   = metaStore;
        this.shardClient = shardClient;
        this.ecConfig    = cluster.config().ecConfig();
    }

    // ── PUT ───────────────────────────────────────────────────────────────

    public void put(HttpServerExchange exchange, String bucket, String key,
                    boolean isReplicaWrite) throws IOException {
        if (!engine.bucketExists(bucket)) {
            BucketHandler.sendError(exchange, StatusCodes.NOT_FOUND,
                    "NoSuchBucket", "The specified bucket does not exist.");
            return;
        }

        exchange.startBlocking();
        byte[] data = exchange.getInputStream().readAllBytes();

        if (data.length < ecConfig.minObjectSize()) {
            // Small object: plain local write (no replication in EC cluster mode)
            engine.put(bucket, key, data);
            respond200(exchange, data);
            log.debug("PUT s3://{}/{} {} bytes (below EC min-size, stored locally)",
                    bucket, key, data.length);
            return;
        }

        try {
            ecPut(bucket, key, data);
        } catch (Exception e) {
            throw new IOException("EC write failed for s3://" + bucket + "/" + key, e);
        }

        respond200(exchange, data);
        log.debug("PUT s3://{}/{} {} bytes (EC k={} m={})",
                bucket, key, data.length, ecConfig.dataShards(), ecConfig.parityShards());
    }

    // ── GET ───────────────────────────────────────────────────────────────

    public void get(HttpServerExchange exchange, String bucket, String key) throws IOException {
        if (!engine.bucketExists(bucket)) {
            BucketHandler.sendError(exchange, StatusCodes.NOT_FOUND,
                    "NoSuchBucket", "The specified bucket does not exist.");
            return;
        }

        Optional<EcMetadata> metaOpt;
        try {
            metaOpt = metaStore.get(bucket, key);
        } catch (Exception e) {
            throw new IOException("EC metadata read failed for s3://" + bucket + "/" + key, e);
        }

        if (metaOpt.isEmpty()) {
            // No EC metadata → fall back to plain local read (pre-EC object)
            byte[] plain = engine.get(bucket, key);
            if (plain == null) {
                BucketHandler.sendError(exchange, StatusCodes.NOT_FOUND,
                        "NoSuchKey", "The specified key does not exist.");
                return;
            }
            sendData(exchange, plain);
            return;
        }

        EcMetadata meta = metaOpt.get();
        byte[][] shards = fetchShards(meta, bucket, key);

        int presentCount = 0;
        for (byte[] s : shards) if (s != null) presentCount++;

        if (presentCount < meta.k()) {
            BucketHandler.sendError(exchange, StatusCodes.SERVICE_UNAVAILABLE,
                    "ServiceUnavailable",
                    "Not enough shards available (" + presentCount + "/" + meta.k() + ") to reconstruct the object.");
            return;
        }

        byte[] data;
        try {
            data = coder.decode(shards, meta.originalSize());
        } catch (IOException e) {
            throw new IOException("EC decode failed for s3://" + bucket + "/" + key, e);
        }

        sendData(exchange, data);
        log.debug("GET s3://{}/{} {} bytes (EC, {}/{} shards used)",
                bucket, key, data.length, presentCount, meta.k() + meta.m());
    }

    // ── HEAD ──────────────────────────────────────────────────────────────

    public void head(HttpServerExchange exchange, String bucket, String key) throws IOException {
        if (!engine.bucketExists(bucket)) {
            exchange.setStatusCode(StatusCodes.NOT_FOUND);
            exchange.endExchange();
            return;
        }

        Optional<EcMetadata> metaOpt;
        try {
            metaOpt = metaStore.get(bucket, key);
        } catch (Exception e) {
            throw new IOException("EC metadata read failed", e);
        }

        if (metaOpt.isPresent()) {
            EcMetadata meta = metaOpt.get();
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE,   "application/octet-stream");
            exchange.getResponseHeaders().put(Headers.CONTENT_LENGTH, meta.originalSize());
            exchange.setStatusCode(StatusCodes.OK);
            exchange.endExchange();
            return;
        }

        // Fallback: plain object
        byte[] data = engine.get(bucket, key);
        if (data == null) {
            exchange.setStatusCode(StatusCodes.NOT_FOUND);
            exchange.endExchange();
            return;
        }
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE,   "application/octet-stream");
        exchange.getResponseHeaders().put(Headers.CONTENT_LENGTH, data.length);
        exchange.setStatusCode(StatusCodes.OK);
        exchange.endExchange();
    }

    // ── DELETE ────────────────────────────────────────────────────────────

    public void delete(HttpServerExchange exchange, String bucket, String key,
                       boolean isReplicaWrite) throws IOException {
        if (!engine.bucketExists(bucket)) {
            BucketHandler.sendError(exchange, StatusCodes.NOT_FOUND,
                    "NoSuchBucket", "The specified bucket does not exist.");
            return;
        }

        Optional<EcMetadata> metaOpt;
        try {
            metaOpt = metaStore.get(bucket, key);
        } catch (Exception e) {
            throw new IOException("EC metadata read failed for delete s3://" + bucket + "/" + key, e);
        }

        if (metaOpt.isEmpty()) {
            // Plain object fallback
            engine.delete(bucket, key);
            exchange.setStatusCode(StatusCodes.NO_CONTENT);
            exchange.endExchange();
            return;
        }

        EcMetadata meta = metaOpt.get();
        deleteShards(meta, bucket, key);   // best-effort, errors are logged not thrown

        try {
            metaStore.delete(bucket, key);
        } catch (Exception e) {
            throw new IOException("EC metadata delete failed for s3://" + bucket + "/" + key, e);
        }

        exchange.setStatusCode(StatusCodes.NO_CONTENT);
        exchange.endExchange();
        log.debug("DELETE s3://{}/{} (EC)", bucket, key);
    }

    // ── private: EC write ─────────────────────────────────────────────────

    private void ecPut(String bucket, String key, byte[] data) throws Exception {
        ShardPlacement placement = cluster.computeShardPlacement(bucket, key);
        byte[][] shards = coder.encode(data);
        int shardSize   = shards[0].length;

        // Fan-out all shards in parallel
        List<CompletableFuture<Void>> futs = new ArrayList<>(ecConfig.totalShards());
        for (int i = 0; i < ecConfig.totalShards(); i++) {
            final int   idx   = i;
            final byte[] shard = shards[i];
            NodeId node = placement.nodeForShard(i);

            if (node.equals(cluster.self())) {
                // Local write — do it directly, no HTTP round-trip
                futs.add(CompletableFuture.runAsync(() -> {
                    try {
                        engine.put(ShardHandler.SHARD_BUCKET,
                                ShardHandler.shardKey(bucket, key, idx), shard);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }, Executors.newVirtualThreadPerTaskExecutor()));
            } else {
                futs.add(CompletableFuture.runAsync(() -> {
                    try {
                        shardClient.putShard(node, idx, bucket, key, shard);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }, Executors.newVirtualThreadPerTaskExecutor()));
            }
        }

        awaitAll(futs, "PUT shards", SHARD_TIMEOUT_MS);

        // Write metadata last — this is the atomic commit point
        EcMetadata meta = new EcMetadata(
                ecConfig.dataShards(), ecConfig.parityShards(),
                data.length, shardSize,
                placement.nodeWireIds());
        metaStore.put(bucket, key, meta);
    }

    // ── private: shard fetch ──────────────────────────────────────────────

    private byte[][] fetchShards(EcMetadata meta, String bucket, String key) {
        int total = meta.k() + meta.m();
        byte[][] shards = new byte[total][];

        List<CompletableFuture<Void>> futs = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            final int  idx  = i;
            NodeId node = meta.nodeForShard(i);

            if (node.equals(cluster.self())) {
                futs.add(CompletableFuture.runAsync(() -> {
                    try {
                        shards[idx] = engine.get(ShardHandler.SHARD_BUCKET,
                                ShardHandler.shardKey(bucket, key, idx));
                    } catch (IOException e) {
                        log.warn("Local shard read failed for {}/{}/{}", bucket, key, idx, e);
                        shards[idx] = null;
                    }
                }, Executors.newVirtualThreadPerTaskExecutor()));
            } else {
                futs.add(CompletableFuture.runAsync(() -> {
                    try {
                        shards[idx] = shardClient.getShard(node, idx, bucket, key);
                    } catch (IOException e) {
                        log.warn("Remote shard {} fetch failed from {}: {}", idx, node, e.getMessage());
                        shards[idx] = null;
                    }
                }, Executors.newVirtualThreadPerTaskExecutor()));
            }
        }

        // Wait for all fetches, but don't throw — missing shards are marked null
        try {
            CompletableFuture.allOf(futs.toArray(new CompletableFuture[0]))
                    .get(SHARD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("Shard fetch timed out for s3://{}/{}, proceeding with available shards",
                    bucket, key);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            log.warn("Shard fetch error for s3://{}/{}", bucket, key, e.getCause());
        }

        return shards;
    }

    // ── private: shard delete ─────────────────────────────────────────────

    private void deleteShards(EcMetadata meta, String bucket, String key) {
        int total = meta.k() + meta.m();
        List<CompletableFuture<Void>> futs = new ArrayList<>(total);

        for (int i = 0; i < total; i++) {
            final int  idx  = i;
            NodeId node = meta.nodeForShard(i);

            if (node.equals(cluster.self())) {
                futs.add(CompletableFuture.runAsync(() -> {
                    try {
                        engine.delete(ShardHandler.SHARD_BUCKET,
                                ShardHandler.shardKey(bucket, key, idx));
                    } catch (IOException e) {
                        log.warn("Local shard delete failed for {}/{}/{}", bucket, key, idx, e);
                    }
                }, Executors.newVirtualThreadPerTaskExecutor()));
            } else {
                futs.add(CompletableFuture.runAsync(() -> {
                    try {
                        shardClient.deleteShard(node, idx, bucket, key);
                    } catch (IOException e) {
                        log.warn("Remote shard {} delete failed from {}: {}", idx, node, e.getMessage());
                    }
                }, Executors.newVirtualThreadPerTaskExecutor()));
            }
        }

        try {
            CompletableFuture.allOf(futs.toArray(new CompletableFuture[0]))
                    .get(SHARD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("Shard delete timed out for s3://{}/{}, metadata will still be removed",
                    bucket, key);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException ignored) {}
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private static void awaitAll(List<CompletableFuture<Void>> futs,
                                  String op, long timeoutMs) throws Exception {
        try {
            CompletableFuture.allOf(futs.toArray(new CompletableFuture[0]))
                    .get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new IOException(op + " timed out after " + timeoutMs + "ms");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) throw io;
            throw new IOException(op + " failed", cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(op + " interrupted", e);
        }
    }

    private static void respond200(HttpServerExchange exchange, byte[] data) {
        String etag = "\"" + md5hex(data) + "\"";
        exchange.getResponseHeaders().put(Headers.ETAG, etag);
        exchange.setStatusCode(StatusCodes.OK);
        exchange.endExchange();
    }

    private static void sendData(HttpServerExchange exchange, byte[] data) {
        String etag = "\"" + md5hex(data) + "\"";
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE,   "application/octet-stream");
        exchange.getResponseHeaders().put(Headers.CONTENT_LENGTH, data.length);
        exchange.getResponseHeaders().put(Headers.ETAG,            etag);
        exchange.setStatusCode(StatusCodes.OK);
        exchange.getResponseSender().send(ByteBuffer.wrap(data));
    }

    private static String md5hex(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(data);
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest) sb.append("%02x".formatted(b & 0xff));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("MD5 unavailable", e);
        }
    }
}
