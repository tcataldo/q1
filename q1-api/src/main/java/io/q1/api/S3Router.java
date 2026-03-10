package io.q1.api;

import io.q1.api.handler.BucketHandler;
import io.q1.api.handler.EcObjectHandler;
import io.q1.api.handler.ObjectHandler;
import io.q1.api.handler.ShardHandler;
import io.q1.api.handler.SyncHandler;
import io.q1.cluster.EcMetadataStore;
import io.q1.cluster.ErasureCoder;
import io.q1.cluster.EtcdCluster;
import io.q1.cluster.HttpShardClient;
import io.q1.cluster.PartitionRouter;
import io.q1.cluster.Replicator;
import io.q1.core.StorageEngine;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.Methods;
import io.undertow.util.StatusCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Root Undertow handler.  Parses S3 path-style URLs and dispatches to
 * {@link ObjectHandler} or {@link BucketHandler}.
 *
 * <h3>Cluster routing</h3>
 * If a {@link PartitionRouter} is supplied (cluster mode):
 * <ul>
 *   <li>Write requests ({@code PUT}, {@code DELETE}) that land on a
 *       non-leader node are redirected (307) to the current leader.</li>
 *   <li>Requests carrying {@value io.q1.cluster.HttpReplicator#REPLICA_HEADER}
 *       are replica writes from the leader — they skip routing and are applied
 *       directly to local storage without further replication.</li>
 *   <li>Read requests ({@code GET}, {@code HEAD}) are served locally by any
 *       node (eventual consistency reads).</li>
 * </ul>
 *
 * {@code router == null} means standalone mode; all requests handled locally.
 */
public final class S3Router implements HttpHandler {

    private static final Logger log = LoggerFactory.getLogger(S3Router.class);

    private final ExecutorService   vt = Executors.newVirtualThreadPerTaskExecutor();
    private final BucketHandler     bucketHandler;
    private final ObjectHandler     objectHandler;   // used when EC is disabled
    private final EcObjectHandler   ecObjectHandler; // non-null when EC is enabled
    private final SyncHandler       syncHandler;     // null in standalone mode
    private final ShardHandler      shardHandler;    // null when EC is disabled
    private final PartitionRouter   router;          // null in standalone mode

    /** Standalone constructor (no cluster). */
    public S3Router(StorageEngine engine) {
        this.bucketHandler   = new BucketHandler(engine);
        this.objectHandler   = new ObjectHandler(engine);
        this.ecObjectHandler = null;
        this.syncHandler     = null;
        this.shardHandler    = null;
        this.router          = null;
    }

    /** Cluster constructor (plain replication, EC disabled). */
    public S3Router(StorageEngine engine, PartitionRouter router, Replicator replicator) {
        this.bucketHandler   = new BucketHandler(engine);
        this.objectHandler   = new ObjectHandler(engine, replicator);
        this.ecObjectHandler = null;
        this.syncHandler     = new SyncHandler(engine);
        this.shardHandler    = null;
        this.router          = router;
    }

    /** Cluster constructor with EC enabled. */
    public S3Router(StorageEngine engine, PartitionRouter router,
                    EtcdCluster cluster, ErasureCoder coder,
                    EcMetadataStore metaStore, HttpShardClient shardClient) {
        this.bucketHandler   = new BucketHandler(engine);
        this.objectHandler   = new ObjectHandler(engine); // fallback for non-EC objects
        this.ecObjectHandler = new EcObjectHandler(engine, cluster, coder, metaStore, shardClient);
        this.syncHandler     = new SyncHandler(engine);
        this.shardHandler    = new ShardHandler(engine);
        this.router          = router;
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

            // Internal cluster endpoints — not part of the S3 surface
            if (path.startsWith(SyncHandler.PATH_PREFIX)) {
                if (syncHandler != null) {
                    syncHandler.handle(exchange);
                } else {
                    exchange.setStatusCode(StatusCodes.NOT_FOUND);
                    exchange.endExchange();
                }
                return;
            }

            if (path.startsWith(ShardHandler.PATH_PREFIX)) {
                if (shardHandler != null) {
                    shardHandler.handle(exchange);
                } else {
                    exchange.setStatusCode(StatusCodes.NOT_FOUND);
                    exchange.endExchange();
                }
                return;
            }

            // Replica writes come from the leader and must never be blocked —
            // they are part of live replication, not client-originated traffic.
            boolean isReplicaWrite = exchange.getRequestHeaders()
                    .getFirst(io.q1.cluster.HttpReplicator.REPLICA_HEADER) != null;

            // Reject client requests when the cluster is under-replicated.
            // With fewer active nodes than RF, some data may be unavailable.
            if (router != null && !isReplicaWrite && !router.isClusterReady()) {
                log.warn("Cluster not ready (active nodes < RF), rejecting {} {}",
                        method, path);
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
                // Bucket-level — writes don't need per-key routing
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

            // Redirect writes to the leader (unless this is already a replica write)
            if (isWrite && !isReplicaWrite && router != null) {
                Optional<String> leaderUrl = router.leaderBaseUrl(pp.bucket(), pp.key());
                if (leaderUrl.isPresent()) {
                    String location = leaderUrl.get() + path
                            + (exchange.getQueryString().isEmpty() ? "" : "?" + exchange.getQueryString());
                    log.debug("Redirecting {} {} → {}", method, path, location);
                    exchange.getResponseHeaders().put(Headers.LOCATION, location);
                    exchange.setStatusCode(StatusCodes.TEMPORARY_REDIRECT); // 307 keeps method
                    exchange.endExchange();
                    return;
                }
            }

            if (ecObjectHandler != null) {
                switch (method) {
                    case "PUT"    -> ecObjectHandler.put(exchange, pp.bucket(), pp.key(), isReplicaWrite);
                    case "GET"    -> ecObjectHandler.get(exchange, pp.bucket(), pp.key());
                    case "HEAD"   -> ecObjectHandler.head(exchange, pp.bucket(), pp.key());
                    case "DELETE" -> ecObjectHandler.delete(exchange, pp.bucket(), pp.key(), isReplicaWrite);
                    default -> { exchange.setStatusCode(StatusCodes.METHOD_NOT_ALLOWED); exchange.endExchange(); }
                }
            } else {
                switch (method) {
                    case "PUT"    -> objectHandler.put(exchange, pp.bucket(), pp.key(), isReplicaWrite);
                    case "GET"    -> objectHandler.get(exchange, pp.bucket(), pp.key());
                    case "HEAD"   -> objectHandler.head(exchange, pp.bucket(), pp.key());
                    case "DELETE" -> objectHandler.delete(exchange, pp.bucket(), pp.key(), isReplicaWrite);
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

    // ── path parsing ──────────────────────────────────────────────────────

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
