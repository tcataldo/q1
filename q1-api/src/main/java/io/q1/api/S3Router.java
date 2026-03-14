package io.q1.api;

import io.q1.api.handler.BucketHandler;
import io.q1.api.handler.EcObjectHandler;
import io.q1.api.handler.HealthHandler;
import io.q1.api.handler.ObjectHandler;
import io.q1.api.handler.ShardHandler;
import io.q1.cluster.ErasureCoder;
import io.q1.cluster.HttpShardClient;
import io.q1.cluster.PartitionRouter;
import io.q1.cluster.RatisCluster;
import io.q1.core.StorageEngine;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.Methods;
import io.undertow.util.StatusCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Root Undertow handler. Parses S3 path-style URLs and dispatches to
 * {@link ObjectHandler} or {@link BucketHandler}.
 *
 * <h3>Cluster routing</h3>
 * If a {@link PartitionRouter} is supplied (cluster mode):
 * <ul>
 *   <li>Write requests ({@code PUT}, {@code DELETE}) that land on a non-leader
 *       node are transparently proxied to the Raft leader (replication mode)
 *       or served locally (EC mode).</li>
 *   <li>Read requests ({@code GET}, {@code HEAD}) are served locally by any
 *       node (eventual consistency reads).</li>
 *   <li>In EC mode, object writes are served locally on any node — no Raft
 *       involvement; shard fan-out is done directly over HTTP.</li>
 * </ul>
 *
 * {@code router == null} means standalone mode; all requests handled locally.
 */
public final class S3Router implements HttpHandler {

    private static final Logger log = LoggerFactory.getLogger(S3Router.class);

    /** Headers that must not be forwarded when proxying to the leader. */
    private static final Set<String> HOP_BY_HOP = Set.of(
            "host", "connection", "transfer-encoding", "keep-alive",
            "proxy-authenticate", "proxy-authorization", "te", "trailers", "upgrade");

    private final ExecutorService   vt = Executors.newVirtualThreadPerTaskExecutor();
    private final HttpClient        httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final HealthHandler     healthHandler;
    private final BucketHandler     bucketHandler;
    private final ObjectHandler     objectHandler;   // used when EC is disabled
    private final EcObjectHandler   ecObjectHandler; // non-null when EC is enabled
    private final ShardHandler      shardHandler;    // null when EC is disabled
    private final PartitionRouter   router;          // null in standalone mode
    private final RatisCluster      cluster;         // null in standalone mode

    /** Standalone constructor (no cluster). */
    public S3Router(StorageEngine engine) {
        this.healthHandler   = new HealthHandler("standalone", engine.numPartitions(), null);
        this.bucketHandler   = new BucketHandler(engine);
        this.objectHandler   = new ObjectHandler(engine);
        this.ecObjectHandler = null;
        this.shardHandler    = null;
        this.router          = null;
        this.cluster         = null;
    }

    /** Cluster constructor (plain replication via Raft, EC disabled). */
    public S3Router(StorageEngine engine, PartitionRouter router, RatisCluster cluster) {
        this.healthHandler   = new HealthHandler(cluster.self().id(), engine.numPartitions(), cluster);
        this.bucketHandler   = new BucketHandler(engine, cluster);
        this.objectHandler   = new ObjectHandler(engine, cluster);
        this.ecObjectHandler = null;
        this.shardHandler    = null;
        this.router          = router;
        this.cluster         = cluster;
    }

    /** Cluster constructor with EC enabled. */
    public S3Router(StorageEngine engine, PartitionRouter router, RatisCluster cluster,
                    ErasureCoder coder, HttpShardClient shardClient) {
        this.healthHandler   = new HealthHandler(cluster.self().id(), engine.numPartitions(), cluster);
        this.bucketHandler   = new BucketHandler(engine, cluster);
        this.objectHandler   = new ObjectHandler(engine); // fallback for non-EC objects
        this.ecObjectHandler = new EcObjectHandler(engine, cluster, coder, shardClient);
        this.shardHandler    = new ShardHandler(engine);
        this.router          = router;
        this.cluster         = cluster;
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        if (exchange.isInIoThread()) {
            exchange.dispatch(vt, () -> route(exchange));
            return;
        }
        route(exchange);
    }

    private void route(HttpServerExchange exchange) {
        try {
            String path   = exchange.getRequestPath();
            String method = exchange.getRequestMethod().toString();

            // Health check — served even when the cluster is not ready
            if ("/healthz".equals(path) && "GET".equals(method)) {
                healthHandler.handle(exchange);
                return;
            }

            // Internal EC shard endpoint
            if (path.startsWith(ShardHandler.PATH_PREFIX)) {
                if (shardHandler != null) {
                    shardHandler.handle(exchange);
                } else {
                    exchange.setStatusCode(StatusCodes.NOT_FOUND);
                    exchange.endExchange();
                }
                return;
            }

            // Reject client requests when the cluster is not ready
            if (router != null && !router.isClusterReady()) {
                log.warn("Cluster not ready, rejecting {} {}", method, path);
                BucketHandler.sendError(exchange, StatusCodes.SERVICE_UNAVAILABLE,
                        "ServiceUnavailable",
                        "Not enough nodes are available to serve requests. Retry later.");
                return;
            }

            ParsedPath pp = parse(path);

            if (pp.bucket() == null) {
                if (Methods.GET.equalToString(method)) {
                    bucketHandler.listBuckets(exchange);
                } else {
                    exchange.setStatusCode(StatusCodes.METHOD_NOT_ALLOWED);
                    exchange.endExchange();
                }
                return;
            }

            if (pp.key() == null) {
                // Bucket-level operations — proxy to Raft leader for writes
                boolean isWrite = "PUT".equals(method) || "DELETE".equals(method);
                if (isWrite && router != null) {
                    Optional<String> leaderUrl = router.leaderBaseUrl(pp.bucket(), "");
                    if (leaderUrl.isPresent()) {
                        proxyToLeader(exchange, leaderUrl.get() + path);
                        return;
                    }
                }
                switch (method) {
                    case "PUT"    -> bucketHandler.createBucket(exchange, pp.bucket());
                    case "GET"    -> bucketHandler.listObjects(exchange, pp.bucket());
                    case "DELETE" -> bucketHandler.deleteBucket(exchange, pp.bucket());
                    default -> { exchange.setStatusCode(StatusCodes.METHOD_NOT_ALLOWED); exchange.endExchange(); }
                }
                return;
            }

            // ── object-level ──────────────────────────────────────────────

            boolean isWrite = "PUT".equals(method) || "DELETE".equals(method);

            // Proxy writes to the Raft leader (replication mode only).
            // In EC mode any node can handle writes: encoding + shard fan-out are local ops.
            if (isWrite && router != null && ecObjectHandler == null) {
                Optional<String> leaderUrl = router.leaderBaseUrl(pp.bucket(), pp.key());
                if (leaderUrl.isPresent()) {
                    String target = leaderUrl.get() + path
                            + (exchange.getQueryString().isEmpty() ? "" : "?" + exchange.getQueryString());
                    log.debug("Proxying {} {} → {}", method, path, target);
                    proxyToLeader(exchange, target);
                    return;
                }
            }

            if (ecObjectHandler != null) {
                switch (method) {
                    case "PUT"    -> ecObjectHandler.put(exchange, pp.bucket(), pp.key());
                    case "GET"    -> ecObjectHandler.get(exchange, pp.bucket(), pp.key());
                    case "HEAD"   -> ecObjectHandler.head(exchange, pp.bucket(), pp.key());
                    case "DELETE" -> ecObjectHandler.delete(exchange, pp.bucket(), pp.key());
                    default -> { exchange.setStatusCode(StatusCodes.METHOD_NOT_ALLOWED); exchange.endExchange(); }
                }
            } else {
                switch (method) {
                    case "PUT"    -> objectHandler.put(exchange, pp.bucket(), pp.key());
                    case "GET"    -> objectHandler.get(exchange, pp.bucket(), pp.key());
                    case "HEAD"   -> objectHandler.head(exchange, pp.bucket(), pp.key());
                    case "DELETE" -> objectHandler.delete(exchange, pp.bucket(), pp.key());
                    default -> { exchange.setStatusCode(StatusCodes.METHOD_NOT_ALLOWED); exchange.endExchange(); }
                }
            }

        } catch (Exception e) {
            log.error("Unhandled error processing {} {}", exchange.getRequestMethod(),
                    exchange.getRequestPath(), e);
            BucketHandler.sendError(exchange, StatusCodes.INTERNAL_SERVER_ERROR,
                    "InternalError", "We encountered an internal error. Please try again.");
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    /**
     * Forwards the current request to {@code targetUrl} and writes the leader's
     * response back to the client transparently.  The client sees the leader's
     * status code and body — no redirect is exposed.
     */
    private void proxyToLeader(HttpServerExchange exchange, String targetUrl) throws Exception {
        exchange.startBlocking();
        byte[] body = exchange.getInputStream().readAllBytes();

        HttpRequest.Builder req = HttpRequest.newBuilder()
                .uri(URI.create(targetUrl))
                .method(exchange.getRequestMethod().toString(),
                        body.length > 0
                                ? HttpRequest.BodyPublishers.ofByteArray(body)
                                : HttpRequest.BodyPublishers.noBody());

        // Forward request headers, skipping hop-by-hop ones
        for (var headerValues : exchange.getRequestHeaders()) {
            String name = headerValues.getHeaderName().toString();
            if (HOP_BY_HOP.contains(name.toLowerCase())) continue;
            for (String val : headerValues) {
                try { req.header(name, val); } catch (IllegalArgumentException ignored) {}
            }
        }

        HttpResponse<byte[]> resp = httpClient.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());

        exchange.setStatusCode(resp.statusCode());
        resp.headers().map().forEach((name, vals) -> {
            if (HOP_BY_HOP.contains(name.toLowerCase())) return;
            for (String val : vals) {
                exchange.getResponseHeaders().add(
                        new io.undertow.util.HttpString(name), val);
            }
        });

        byte[] respBody = resp.body();
        if (respBody != null && respBody.length > 0) {
            exchange.getResponseHeaders().put(Headers.CONTENT_LENGTH, respBody.length);
            exchange.getResponseSender().send(ByteBuffer.wrap(respBody));
        } else {
            exchange.endExchange();
        }
    }

    private record ParsedPath(String bucket, String key) {}

    private static ParsedPath parse(String path) {
        String s = path.startsWith("/") ? path.substring(1) : path;
        if (s.isEmpty()) return new ParsedPath(null, null);
        int slash = s.indexOf('/');
        if (slash < 0) return new ParsedPath(s, null);
        String bucket = s.substring(0, slash);
        String key    = s.substring(slash + 1);
        return new ParsedPath(bucket, key.isEmpty() ? null : key);
    }

    public void shutdown() { vt.close(); }
}
