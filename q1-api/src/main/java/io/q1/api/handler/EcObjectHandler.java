package io.q1.api.handler;

import io.q1.cluster.EcConfig;
import io.q1.cluster.ErasureCoder;
import io.q1.cluster.RatisCluster;
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
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Handles object PUT / GET / HEAD / DELETE using Reed-Solomon erasure coding.
 *
 * <p>Used instead of {@link ObjectHandler} when {@link EcConfig#enabled()} is true.
 * Ratis is NOT on the data path: shard placement is computed from the deterministic
 * ring ({@link io.q1.cluster.RatisCluster#computeShardPlacement}), and the original
 * object size is embedded in each shard payload as an 8-byte big-endian header.
 *
 * <h3>Shard payload format</h3>
 * <pre>
 *   [8B] original size (big-endian long)
 *   [N B] shard data (raw Reed-Solomon shard, zero-padded to shardSize)
 * </pre>
 *
 * <h3>PUT</h3>
 * Encode → build k+m payloads with the 8-byte header → fan-out to placement nodes.
 * No etcd write.  All shards are self-describing.
 *
 * <h3>GET</h3>
 * Compute ring placement → parallel fetch from k+m nodes → decode from k present
 * shards → return original bytes.  No etcd read.
 *
 * <h3>Fallback</h3>
 * Objects written before EC was enabled have no shards on any node.  If all shard
 * fetches return 404, the handler falls back to {@link StorageEngine#get} (plain
 * replication path).
 */
public final class EcObjectHandler {

    private static final Logger log = LoggerFactory.getLogger(EcObjectHandler.class);

    /** Bytes in the shard payload header: 8B big-endian original-object-size. */
    public static final int HEADER_BYTES = Long.BYTES;

    private static final long SHARD_TIMEOUT_MS = 5_000;

    private final StorageEngine   engine;
    private final RatisCluster    cluster;
    private final ErasureCoder    coder;
    private final HttpShardClient shardClient;
    private final EcConfig        ecConfig;

    public EcObjectHandler(StorageEngine engine,
                           RatisCluster cluster,
                           ErasureCoder coder,
                           HttpShardClient shardClient) {
        this.engine      = engine;
        this.cluster     = cluster;
        this.coder       = coder;
        this.shardClient = shardClient;
        this.ecConfig    = cluster.config().ecConfig();
    }

    // ── PUT ───────────────────────────────────────────────────────────────

    public void put(HttpServerExchange exchange, String bucket, String key) throws IOException {
        if (!engine.bucketExists(bucket)) {
            BucketHandler.sendError(exchange, StatusCodes.NOT_FOUND,
                    "NoSuchBucket", "The specified bucket does not exist.");
            return;
        }

        exchange.startBlocking();
        byte[] data = exchange.getInputStream().readAllBytes();

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

        ShardPlacement placement = cluster.computeShardPlacement(bucket, key);
        byte[][] payloads = fetchPayloads(placement, bucket, key);

        // Count present payloads and extract originalSize from the first available one
        int presentCount = 0;
        long originalSize = -1;
        for (byte[] p : payloads) {
            if (p != null && p.length >= HEADER_BYTES) {
                if (originalSize < 0) originalSize = parseOriginalSize(p);
                presentCount++;
            }
        }

        if (presentCount == 0) {
            // No EC shards found — fall back to plain-replication object
            byte[] plain = engine.get(bucket, key);
            if (plain == null) {
                BucketHandler.sendError(exchange, StatusCodes.NOT_FOUND,
                        "NoSuchKey", "The specified key does not exist.");
                return;
            }
            sendData(exchange, plain);
            return;
        }

        if (presentCount < ecConfig.dataShards()) {
            BucketHandler.sendError(exchange, StatusCodes.SERVICE_UNAVAILABLE,
                    "ServiceUnavailable",
                    "Not enough shards available (" + presentCount + "/" + ecConfig.dataShards()
                            + ") to reconstruct the object.");
            return;
        }

        // Strip headers, leave null for missing shards (decoder will reconstruct them)
        byte[][] shards = new byte[ecConfig.totalShards()][];
        for (int i = 0; i < ecConfig.totalShards(); i++) {
            if (payloads[i] != null && payloads[i].length >= HEADER_BYTES) {
                shards[i] = parseShardData(payloads[i]);
            }
        }

        byte[] data;
        try {
            data = coder.decode(shards, originalSize);
        } catch (IOException e) {
            throw new IOException("EC decode failed for s3://" + bucket + "/" + key, e);
        }

        sendData(exchange, data);
        log.debug("GET s3://{}/{} {} bytes (EC, {}/{} shards available)",
                bucket, key, data.length, presentCount, ecConfig.totalShards());
    }

    // ── HEAD ──────────────────────────────────────────────────────────────

    public void head(HttpServerExchange exchange, String bucket, String key) throws IOException {
        if (!engine.bucketExists(bucket)) {
            exchange.setStatusCode(StatusCodes.NOT_FOUND);
            exchange.endExchange();
            return;
        }

        // Parallel shard existence check: local shards fetched for originalSize,
        // remote shards checked via HEAD only (no data download).
        ShardPlacement placement = cluster.computeShardPlacement(bucket, key);
        int    total       = ecConfig.totalShards();
        boolean[] present  = new boolean[total];
        long[]    sizes    = new long[total]; // >=0 when local shard found with header

        List<CompletableFuture<Void>> futs = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            final int  idx  = i;
            NodeId     node = placement.nodeForShard(i);
            futs.add(CompletableFuture.runAsync(() -> {
                try {
                    if (node.equals(cluster.self())) {
                        byte[] payload = engine.get(ShardHandler.SHARD_BUCKET,
                                ShardHandler.shardKey(bucket, key, idx));
                        if (payload != null && payload.length >= HEADER_BYTES) {
                            present[idx] = true;
                            sizes[idx]   = parseOriginalSize(payload);
                        }
                    } else {
                        present[idx] = shardClient.shardExists(node, idx, bucket, key);
                        sizes[idx]   = -1;
                    }
                } catch (IOException e) {
                    log.warn("Shard {} HEAD failed on {}: {}", idx, node, e.getMessage());
                }
            }, Executors.newVirtualThreadPerTaskExecutor()));
        }

        try {
            CompletableFuture.allOf(futs.toArray(new CompletableFuture[0]))
                    .get(SHARD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("Shard HEAD timed out for s3://{}/{}", bucket, key);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException ignored) {}

        boolean anyPresent   = false;
        long    originalSize = -1;
        for (int i = 0; i < total; i++) {
            if (present[i]) {
                anyPresent = true;
                if (sizes[i] >= 0 && originalSize < 0) originalSize = sizes[i];
            }
        }

        if (anyPresent) {
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/octet-stream");
            if (originalSize >= 0)
                exchange.getResponseHeaders().put(Headers.CONTENT_LENGTH, originalSize);
            exchange.setStatusCode(StatusCodes.OK);
            exchange.endExchange();
            return;
        }

        // Fallback: plain object (pre-EC)
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

    public void delete(HttpServerExchange exchange, String bucket, String key) throws IOException {
        if (!engine.bucketExists(bucket)) {
            BucketHandler.sendError(exchange, StatusCodes.NOT_FOUND,
                    "NoSuchBucket", "The specified bucket does not exist.");
            return;
        }

        // Fan-out shard deletes using ring placement (best-effort)
        ShardPlacement placement = cluster.computeShardPlacement(bucket, key);
        deleteShards(placement, bucket, key);

        // Also delete from plain storage (covers pre-EC objects and min-size fallback)
        engine.delete(bucket, key);

        exchange.setStatusCode(StatusCodes.NO_CONTENT);
        exchange.endExchange();
        log.debug("DELETE s3://{}/{}", bucket, key);
    }

    // ── private: EC write ─────────────────────────────────────────────────

    private void ecPut(String bucket, String key, byte[] data) throws Exception {
        ShardPlacement placement = cluster.computeShardPlacement(bucket, key);
        byte[][] shards = coder.encode(data);

        List<CompletableFuture<Void>> futs = new ArrayList<>(ecConfig.totalShards());
        for (int i = 0; i < ecConfig.totalShards(); i++) {
            final int    idx     = i;
            final byte[] payload = buildPayload(data.length, shards[i]);
            NodeId       node    = placement.nodeForShard(i);

            if (node.equals(cluster.self())) {
                futs.add(CompletableFuture.runAsync(() -> {
                    try {
                        engine.put(ShardHandler.SHARD_BUCKET,
                                ShardHandler.shardKey(bucket, key, idx), payload);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }, Executors.newVirtualThreadPerTaskExecutor()));
            } else {
                futs.add(CompletableFuture.runAsync(() -> {
                    try {
                        shardClient.putShard(node, idx, bucket, key, payload);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }, Executors.newVirtualThreadPerTaskExecutor()));
            }
        }

        awaitAll(futs, "PUT shards", SHARD_TIMEOUT_MS);
    }

    // ── private: shard fetch ──────────────────────────────────────────────

    /** Fetch raw payloads (header + shard data) from all k+m placement nodes in parallel. */
    private byte[][] fetchPayloads(ShardPlacement placement, String bucket, String key) {
        int total = ecConfig.totalShards();
        byte[][] payloads = new byte[total][];

        List<CompletableFuture<Void>> futs = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            final int  idx  = i;
            NodeId     node = placement.nodeForShard(i);

            futs.add(CompletableFuture.runAsync(() -> {
                try {
                    payloads[idx] = fetchOnePayload(node, idx, bucket, key);
                } catch (IOException e) {
                    log.warn("Shard {} fetch failed from {}: {}", idx, node, e.getMessage());
                    payloads[idx] = null;
                }
            }, Executors.newVirtualThreadPerTaskExecutor()));
        }

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

        return payloads;
    }

    private byte[] fetchOnePayload(NodeId node, int shardIndex,
                                   String bucket, String key) throws IOException {
        if (node.equals(cluster.self())) {
            return engine.get(ShardHandler.SHARD_BUCKET,
                    ShardHandler.shardKey(bucket, key, shardIndex));
        }
        return shardClient.getShard(node, shardIndex, bucket, key);
    }

    // ── private: shard delete ─────────────────────────────────────────────

    private void deleteShards(ShardPlacement placement, String bucket, String key) {
        List<CompletableFuture<Void>> futs = new ArrayList<>(ecConfig.totalShards());
        for (int i = 0; i < ecConfig.totalShards(); i++) {
            final int  idx  = i;
            NodeId     node = placement.nodeForShard(i);

            futs.add(CompletableFuture.runAsync(() -> {
                try {
                    if (node.equals(cluster.self())) {
                        engine.delete(ShardHandler.SHARD_BUCKET,
                                ShardHandler.shardKey(bucket, key, idx));
                    } else {
                        shardClient.deleteShard(node, idx, bucket, key);
                    }
                } catch (IOException e) {
                    log.warn("Shard {} delete failed on {}: {}", idx, node, e.getMessage());
                }
            }, Executors.newVirtualThreadPerTaskExecutor()));
        }

        try {
            CompletableFuture.allOf(futs.toArray(new CompletableFuture[0]))
                    .get(SHARD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("Shard delete timed out for s3://{}/{}", bucket, key);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException ignored) {}
    }

    // ── shard payload helpers ─────────────────────────────────────────────

    /** Prepend the 8-byte originalSize header to raw shard data. */
    public static byte[] buildPayload(long originalSize, byte[] shardData) {
        byte[] payload = new byte[HEADER_BYTES + shardData.length];
        ByteBuffer.wrap(payload).putLong(originalSize);
        System.arraycopy(shardData, 0, payload, HEADER_BYTES, shardData.length);
        return payload;
    }

    public static long parseOriginalSize(byte[] payload) {
        return ByteBuffer.wrap(payload).getLong(0);
    }

    public static byte[] parseShardData(byte[] payload) {
        return Arrays.copyOfRange(payload, HEADER_BYTES, payload.length);
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────

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
