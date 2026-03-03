package io.q1.api;

import io.q1.api.handler.BucketHandler;
import io.q1.api.handler.ObjectHandler;
import io.q1.core.StorageEngine;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Methods;
import io.undertow.util.StatusCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Root Undertow handler.  Parses the S3 path-style URL and dispatches to
 * {@link ObjectHandler} or {@link BucketHandler}.
 *
 * <p>Every request is dispatched to a virtual-thread executor so handlers
 * can block freely on I/O without tying up Undertow's IO threads.
 *
 * <h3>Path conventions</h3>
 * <pre>
 *   GET  /                         → list buckets
 *   PUT  /{bucket}                 → create bucket
 *   GET  /{bucket}                 → list objects
 *   DELETE /{bucket}               → delete bucket
 *   PUT  /{bucket}/{key+}          → put object
 *   GET  /{bucket}/{key+}          → get object
 *   HEAD /{bucket}/{key+}          → head object
 *   DELETE /{bucket}/{key+}        → delete object
 * </pre>
 */
public final class S3Router implements HttpHandler {

    private static final Logger log = LoggerFactory.getLogger(S3Router.class);

    private final ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor();

    private final BucketHandler bucketHandler;
    private final ObjectHandler objectHandler;

    public S3Router(StorageEngine engine) {
        this.bucketHandler = new BucketHandler(engine);
        this.objectHandler = new ObjectHandler(engine);
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        if (exchange.isInIoThread()) {
            // Dispatch off the IO thread so handlers may block
            exchange.dispatch(vt, () -> route(exchange));
            return;
        }
        route(exchange);
    }

    private void route(HttpServerExchange exchange) {
        try {
            String path   = exchange.getRequestPath();        // e.g. "/mybucket/my/key"
            String method = exchange.getRequestMethod().toString();

            ParsedPath pp = parse(path);

            if (pp.bucket() == null) {
                // Root: only GET is meaningful (list buckets)
                if (Methods.GET.equalToString(method)) {
                    bucketHandler.listBuckets(exchange);
                } else {
                    exchange.setStatusCode(StatusCodes.METHOD_NOT_ALLOWED);
                    exchange.endExchange();
                }
                return;
            }

            if (pp.key() == null) {
                // Bucket-level operation
                switch (method) {
                    case "PUT"    -> bucketHandler.createBucket(exchange, pp.bucket());
                    case "GET"    -> bucketHandler.listObjects(exchange, pp.bucket());
                    case "DELETE" -> bucketHandler.deleteBucket(exchange, pp.bucket());
                    default -> {
                        exchange.setStatusCode(StatusCodes.METHOD_NOT_ALLOWED);
                        exchange.endExchange();
                    }
                }
                return;
            }

            // Object-level operation
            switch (method) {
                case "PUT"    -> objectHandler.put(exchange, pp.bucket(), pp.key());
                case "GET"    -> objectHandler.get(exchange, pp.bucket(), pp.key());
                case "HEAD"   -> objectHandler.head(exchange, pp.bucket(), pp.key());
                case "DELETE" -> objectHandler.delete(exchange, pp.bucket(), pp.key());
                default -> {
                    exchange.setStatusCode(StatusCodes.METHOD_NOT_ALLOWED);
                    exchange.endExchange();
                }
            }

        } catch (Exception e) {
            log.error("Unhandled error processing {} {}", exchange.getRequestMethod(),
                    exchange.getRequestPath(), e);
            BucketHandler.sendError(exchange, StatusCodes.INTERNAL_SERVER_ERROR,
                    "InternalError", "We encountered an internal error. Please try again.");
        }
    }

    // ── path parsing ──────────────────────────────────────────────────────

    private record ParsedPath(String bucket, String key) {}

    /**
     * Splits {@code /bucket/key/with/slashes} into (bucket, key).
     * Key may be {@code null} for bucket-only paths.
     */
    private static ParsedPath parse(String path) {
        // Strip leading slash
        String s = path.startsWith("/") ? path.substring(1) : path;
        if (s.isEmpty()) return new ParsedPath(null, null);

        int slash = s.indexOf('/');
        if (slash < 0) return new ParsedPath(s, null);           // /bucket only

        String bucket = s.substring(0, slash);
        String key    = s.substring(slash + 1);
        if (key.isEmpty()) return new ParsedPath(bucket, null);  // trailing slash

        return new ParsedPath(bucket, key);
    }

    public void shutdown() {
        vt.close();
    }
}
