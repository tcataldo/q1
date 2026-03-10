package io.q1.api.handler;

import io.q1.core.StorageEngine;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.Methods;
import io.undertow.util.StatusCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Internal handler for erasure-coding shard storage.
 *
 * <p>Mounted at {@value PATH_PREFIX}.  Stores shards in the local
 * {@link StorageEngine} under the internal bucket {@value SHARD_BUCKET}.
 * No cluster routing or bucket-existence checks — this is a node-local
 * endpoint called by the EC coordinator on the leader.
 *
 * <h3>Endpoints</h3>
 * <pre>
 *   PUT    /internal/v1/shard/{shardIndex}/{bucket}/{key...}
 *   GET    /internal/v1/shard/{shardIndex}/{bucket}/{key...}
 *   DELETE /internal/v1/shard/{shardIndex}/{bucket}/{key...}
 * </pre>
 */
public final class ShardHandler {

    private static final Logger log = LoggerFactory.getLogger(ShardHandler.class);

    public static final String PATH_PREFIX  = "/internal/v1/shard/";

    /** Internal StorageEngine bucket for all EC shards (never user-visible). */
    public static final String SHARD_BUCKET = "__q1_ec_shards__";

    private final StorageEngine engine;

    public ShardHandler(StorageEngine engine) {
        this.engine = engine;
    }

    public void handle(HttpServerExchange exchange) throws IOException {
        // Path after prefix: "{shardIndex}/{bucket}/{key...}"
        String sub = exchange.getRequestPath().substring(PATH_PREFIX.length());
        int firstSlash = sub.indexOf('/');
        if (firstSlash < 0) {
            exchange.setStatusCode(StatusCodes.BAD_REQUEST);
            exchange.endExchange();
            return;
        }
        int shardIndex;
        try {
            shardIndex = Integer.parseInt(sub.substring(0, firstSlash));
        } catch (NumberFormatException e) {
            exchange.setStatusCode(StatusCodes.BAD_REQUEST);
            exchange.endExchange();
            return;
        }

        String rest = sub.substring(firstSlash + 1); // "{bucket}/{key...}"
        int secondSlash = rest.indexOf('/');
        if (secondSlash < 0) {
            exchange.setStatusCode(StatusCodes.BAD_REQUEST);
            exchange.endExchange();
            return;
        }
        String bucket = rest.substring(0, secondSlash);
        String key    = rest.substring(secondSlash + 1);

        String method = exchange.getRequestMethod().toString();
        switch (method) {
            case "PUT"    -> put(exchange, shardIndex, bucket, key);
            case "GET"    -> get(exchange, shardIndex, bucket, key);
            case "DELETE" -> delete(exchange, shardIndex, bucket, key);
            default -> {
                exchange.setStatusCode(StatusCodes.METHOD_NOT_ALLOWED);
                exchange.endExchange();
            }
        }
    }

    private void put(HttpServerExchange exchange, int shardIndex,
                     String bucket, String key) throws IOException {
        exchange.startBlocking();
        byte[] shard = exchange.getInputStream().readAllBytes();
        engine.put(SHARD_BUCKET, shardKey(bucket, key, shardIndex), shard);
        exchange.setStatusCode(StatusCodes.OK);
        exchange.endExchange();
        log.debug("Stored shard {}/{}/{:02d} ({} bytes)", bucket, key, shardIndex, shard.length);
    }

    private void get(HttpServerExchange exchange, int shardIndex,
                     String bucket, String key) throws IOException {
        byte[] shard = engine.get(SHARD_BUCKET, shardKey(bucket, key, shardIndex));
        if (shard == null) {
            exchange.setStatusCode(StatusCodes.NOT_FOUND);
            exchange.endExchange();
            return;
        }
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE,  "application/octet-stream");
        exchange.getResponseHeaders().put(Headers.CONTENT_LENGTH, shard.length);
        exchange.setStatusCode(StatusCodes.OK);
        exchange.getResponseSender().send(ByteBuffer.wrap(shard));
        log.debug("Served shard {}/{}/{:02d} ({} bytes)", bucket, key, shardIndex, shard.length);
    }

    private void delete(HttpServerExchange exchange, int shardIndex,
                        String bucket, String key) throws IOException {
        engine.delete(SHARD_BUCKET, shardKey(bucket, key, shardIndex));
        exchange.setStatusCode(StatusCodes.NO_CONTENT);
        exchange.endExchange();
        log.debug("Deleted shard {}/{}/{:02d}", bucket, key, shardIndex);
    }

    /**
     * Internal storage key for shard {@code index} of object {@code bucket/key}.
     * Format: {@code {bucket}/{key}/{index:02d}}
     */
    public static String shardKey(String bucket, String key, int index) {
        return bucket + "/" + key + "/" + String.format("%02d", index);
    }
}
