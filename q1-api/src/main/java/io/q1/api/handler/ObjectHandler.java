package io.q1.api.handler;

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
 * Handles object-level S3 operations:
 * <ul>
 *   <li>PUT    /{bucket}/{key+}  — store object</li>
 *   <li>GET    /{bucket}/{key+}  — retrieve object</li>
 *   <li>HEAD   /{bucket}/{key+}  — check existence + metadata</li>
 *   <li>DELETE /{bucket}/{key+}  — delete object</li>
 * </ul>
 */
public final class ObjectHandler {

    private static final Logger log = LoggerFactory.getLogger(ObjectHandler.class);

    private final StorageEngine engine;

    public ObjectHandler(StorageEngine engine) {
        this.engine = engine;
    }

    public void put(HttpServerExchange exchange, String bucket, String key) throws IOException {
        if (!engine.bucketExists(bucket)) {
            BucketHandler.sendError(exchange, StatusCodes.NOT_FOUND, "NoSuchBucket",
                    "The specified bucket does not exist.");
            return;
        }

        // Block until the full body is received (virtual thread — blocking is fine)
        exchange.startBlocking();
        byte[] value = exchange.getInputStream().readAllBytes();

        engine.put(bucket, key, value);

        String etag = "\"" + md5hex(value) + "\"";
        exchange.getResponseHeaders().put(Headers.ETAG, etag);
        exchange.setStatusCode(StatusCodes.OK);
        exchange.endExchange();

        log.debug("PUT s3://{}/{} {} bytes etag={}", bucket, key, value.length, etag);
    }

    public void get(HttpServerExchange exchange, String bucket, String key) throws IOException {
        if (!engine.bucketExists(bucket)) {
            BucketHandler.sendError(exchange, StatusCodes.NOT_FOUND, "NoSuchBucket",
                    "The specified bucket does not exist.");
            return;
        }

        byte[] value = engine.get(bucket, key);
        if (value == null) {
            BucketHandler.sendError(exchange, StatusCodes.NOT_FOUND, "NoSuchKey",
                    "The specified key does not exist.");
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

    public void head(HttpServerExchange exchange, String bucket, String key) throws IOException {
        if (!engine.bucketExists(bucket)) {
            exchange.setStatusCode(StatusCodes.NOT_FOUND);
            exchange.endExchange();
            return;
        }

        if (!engine.exists(bucket, key)) {
            exchange.setStatusCode(StatusCodes.NOT_FOUND);
            exchange.endExchange();
            return;
        }

        // HEAD: same headers as GET but no body
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

    public void delete(HttpServerExchange exchange, String bucket, String key) throws IOException {
        if (!engine.bucketExists(bucket)) {
            BucketHandler.sendError(exchange, StatusCodes.NOT_FOUND, "NoSuchBucket",
                    "The specified bucket does not exist.");
            return;
        }

        engine.delete(bucket, key);
        exchange.setStatusCode(StatusCodes.NO_CONTENT);
        exchange.endExchange();

        log.debug("DELETE s3://{}/{}", bucket, key);
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private static String md5hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest) sb.append("%02x".formatted(b & 0xff));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("MD5 unavailable", e);
        }
    }
}
