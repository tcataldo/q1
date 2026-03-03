package io.q1.api.handler;

import io.q1.core.StorageEngine;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.StatusCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 * Serves partition sync streams to catching-up followers.
 *
 * <pre>
 * GET /internal/v1/sync/{partitionId}?segment={segId}&amp;offset={byteOffset}
 * </pre>
 *
 * <ul>
 *   <li>200 + binary body — raw segment record bytes from the requested position.</li>
 *   <li>204 — follower is already current (no new data).</li>
 *   <li>400 — bad request parameters.</li>
 * </ul>
 *
 * The body is a sequence of records in the on-disk segment format so the
 * follower can parse them with {@link io.q1.core.Segment#scanStream}.
 */
public final class SyncHandler {

    private static final Logger log = LoggerFactory.getLogger(SyncHandler.class);

    /** Path prefix consumed by {@link io.q1.api.S3Router} before calling this handler. */
    public static final String PATH_PREFIX = "/internal/v1/sync/";

    private static final int COPY_BUF = 256 * 1024; // 256 KiB chunks

    private final StorageEngine engine;

    public SyncHandler(StorageEngine engine) {
        this.engine = engine;
    }

    public void handle(HttpServerExchange exchange) {
        exchange.startBlocking();
        try {
            // Path: /internal/v1/sync/{partitionId}
            String tail        = exchange.getRequestPath().substring(PATH_PREFIX.length());
            int    partitionId = Integer.parseInt(tail.split("/")[0]);

            int  fromSegment = intParam(exchange, "segment", 0);
            long fromOffset  = longParam(exchange, "offset",  0L);

            log.debug("Sync request: partition={} segment={} offset={}",
                    partitionId, fromSegment, fromOffset);

            try (InputStream stream = engine.openSyncStream(partitionId, fromSegment, fromOffset)) {

                // Peek: is there anything to send?
                byte[] first = stream.readNBytes(COPY_BUF);
                if (first.length == 0) {
                    exchange.setStatusCode(StatusCodes.NO_CONTENT);
                    exchange.endExchange();
                    return;
                }

                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/octet-stream");
                exchange.setStatusCode(StatusCodes.OK);

                OutputStream out = exchange.getOutputStream();
                out.write(first);

                byte[] buf = new byte[COPY_BUF];
                int n;
                while ((n = stream.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
                out.flush();
            }

        } catch (NumberFormatException e) {
            exchange.setStatusCode(StatusCodes.BAD_REQUEST);
            exchange.endExchange();
        } catch (IOException e) {
            log.error("Error serving sync request", e);
            exchange.setStatusCode(StatusCodes.INTERNAL_SERVER_ERROR);
            exchange.endExchange();
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private static int intParam(HttpServerExchange ex, String name, int def) {
        var d = ex.getQueryParameters().get(name);
        return (d == null || d.isEmpty()) ? def : Integer.parseInt(d.peekFirst());
    }

    private static long longParam(HttpServerExchange ex, String name, long def) {
        var d = ex.getQueryParameters().get(name);
        return (d == null || d.isEmpty()) ? def : Long.parseLong(d.peekFirst());
    }
}
