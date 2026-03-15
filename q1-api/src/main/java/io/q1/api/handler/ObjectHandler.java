package io.q1.api.handler;

import io.q1.cluster.RatisCluster;
import io.q1.cluster.RatisCommand;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Handles object-level S3 operations: GET, PUT, HEAD, DELETE.
 *
 * <p>In cluster mode a {@link RatisCluster} is injected. Writes are submitted
 * to the Raft log via {@link RatisCluster#submit}; the {@link io.q1.cluster.Q1StateMachine}
 * then applies the mutation to the {@link StorageEngine} on every node.
 *
 * <p>Reads are served directly from the local engine (eventual consistency).
 */
public final class ObjectHandler {

    private static final Logger log = LoggerFactory.getLogger(ObjectHandler.class);

    private final StorageEngine engine;
    private final RatisCluster  cluster; // null in standalone mode

    /** Standalone constructor. */
    public ObjectHandler(StorageEngine engine) {
        this(engine, null);
    }

    /** Cluster-aware constructor. */
    public ObjectHandler(StorageEngine engine, RatisCluster cluster) {
        this.engine  = engine;
        this.cluster = cluster;
    }

    // ── PUT ───────────────────────────────────────────────────────────────

    public void put(HttpServerExchange exchange, String bucket, String key) throws IOException {
        if (!engine.bucketExists(bucket)) {
            BucketHandler.sendError(exchange, StatusCodes.NOT_FOUND,
                    "NoSuchBucket", "The specified bucket does not exist.");
            return;
        }

        exchange.startBlocking();
        byte[] value = exchange.getInputStream().readAllBytes();

        if (cluster != null) {
            cluster.submit(RatisCommand.put(bucket, key, value));
        } else {
            engine.put(bucket, key, value);
        }

        String etag = "\"" + md5hex(value) + "\"";
        exchange.getResponseHeaders().put(Headers.ETAG, etag);
        exchange.setStatusCode(StatusCodes.OK);
        exchange.endExchange();

        log.debug("PUT s3://{}/{} {} bytes etag={}", bucket, key, value.length, etag);
    }

    // ── GET ───────────────────────────────────────────────────────────────

    public void get(HttpServerExchange exchange, String bucket, String key) throws IOException {
        if (!engine.bucketExists(bucket)) {
            BucketHandler.sendError(exchange, StatusCodes.NOT_FOUND,
                    "NoSuchBucket", "The specified bucket does not exist.");
            return;
        }

        byte[] value = engine.get(bucket, key);
        if (value == null) {
            BucketHandler.sendError(exchange, StatusCodes.NOT_FOUND,
                    "NoSuchKey", "The specified key does not exist.");
            return;
        }

        String etag = "\"" + md5hex(value) + "\"";
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE,  "application/octet-stream");
        exchange.getResponseHeaders().put(Headers.CONTENT_LENGTH, value.length);
        exchange.getResponseHeaders().put(Headers.ETAG,           etag);
        exchange.setStatusCode(StatusCodes.OK);
        exchange.getResponseSender().send(ByteBuffer.wrap(value));

        log.debug("GET s3://{}/{} {} bytes", bucket, key, value.length);
    }

    // ── HEAD ──────────────────────────────────────────────────────────────

    public void head(HttpServerExchange exchange, String bucket, String key) throws IOException {
        if (!engine.bucketExists(bucket)) {
            exchange.setStatusCode(StatusCodes.NOT_FOUND);
            exchange.endExchange();
            return;
        }

        byte[] value = engine.get(bucket, key);
        if (value == null) {
            exchange.setStatusCode(StatusCodes.NOT_FOUND);
            exchange.endExchange();
            return;
        }

        String etag = "\"" + md5hex(value) + "\"";
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE,  "application/octet-stream");
        exchange.getResponseHeaders().put(Headers.CONTENT_LENGTH, value.length);
        exchange.getResponseHeaders().put(Headers.ETAG,           etag);
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

        CompletableFuture<Void> raftFuture = null;
        if (cluster != null) {
            raftFuture = cluster.submitAsync(RatisCommand.delete(bucket, key));
        } else {
            engine.delete(bucket, key);
        }

        exchange.setStatusCode(StatusCodes.NO_CONTENT);

        if (raftFuture != null) {
            try {
                raftFuture.get(5_000, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                log.warn("Raft DELETE timed out for s3://{}/{}", bucket, key);
                exchange.setStatusCode(StatusCodes.SERVICE_UNAVAILABLE);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                log.warn("Raft DELETE failed for s3://{}/{}: {}", bucket, key, e.getCause().getMessage());
                exchange.setStatusCode(StatusCodes.SERVICE_UNAVAILABLE);
            }
        }

        exchange.endExchange();
        log.debug("DELETE s3://{}/{}", bucket, key);
    }

    // ── helpers ───────────────────────────────────────────────────────────

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
