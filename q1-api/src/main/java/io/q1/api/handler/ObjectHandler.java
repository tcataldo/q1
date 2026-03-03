package io.q1.api.handler;

import io.q1.cluster.Replicator;
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

/**
 * Handles object-level S3 operations: GET, PUT, HEAD, DELETE.
 *
 * <p>In cluster mode a non-null {@link Replicator} is injected.
 * After a successful local write the leader calls
 * {@link Replicator#replicateWrite} / {@link Replicator#replicateDelete}
 * to push the change to followers <em>before</em> responding to the client.
 *
 * <p>Replica writes (flagged by the caller via {@code isReplicaWrite}) skip
 * the replication step so followers do not fan-out further.
 */
public final class ObjectHandler {

    private static final Logger log = LoggerFactory.getLogger(ObjectHandler.class);

    private final StorageEngine engine;
    private final Replicator    replicator; // null in standalone mode

    /** Standalone constructor. */
    public ObjectHandler(StorageEngine engine) {
        this(engine, null);
    }

    /** Cluster-aware constructor. */
    public ObjectHandler(StorageEngine engine, Replicator replicator) {
        this.engine     = engine;
        this.replicator = replicator;
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
        byte[] value = exchange.getInputStream().readAllBytes();

        // 1. Write locally
        engine.put(bucket, key, value);

        // 2. Replicate to followers (only if we're the leader, not a replica write)
        if (!isReplicaWrite && replicator != null) {
            replicator.replicateWrite(bucket, key, value);
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
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE,   "application/octet-stream");
        exchange.getResponseHeaders().put(Headers.CONTENT_LENGTH,  value.length);
        exchange.getResponseHeaders().put(Headers.ETAG,            etag);
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
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE,   "application/octet-stream");
        exchange.getResponseHeaders().put(Headers.CONTENT_LENGTH,  value.length);
        exchange.getResponseHeaders().put(Headers.ETAG,            etag);
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

        engine.delete(bucket, key);

        if (!isReplicaWrite && replicator != null) {
            replicator.replicateDelete(bucket, key);
        }

        exchange.setStatusCode(StatusCodes.NO_CONTENT);
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
