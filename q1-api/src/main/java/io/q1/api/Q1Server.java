package io.q1.api;

import io.q1.cluster.CatchupManager;
import io.q1.cluster.ClusterConfig;
import io.q1.cluster.EtcdCluster;
import io.q1.cluster.HttpReplicator;
import io.q1.cluster.NodeId;
import io.q1.cluster.PartitionRouter;
import io.q1.cluster.Replicator;
import io.q1.core.StorageEngine;
import io.undertow.Undertow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * Entry point for the Q1 object store.
 *
 * <h3>Standalone mode</h3>
 * <pre>
 * java --enable-preview --enable-native-access=ALL-UNNAMED \
 *      -jar q1-api.jar
 * </pre>
 *
 * <h3>Cluster mode (env vars)</h3>
 * <pre>
 *   Q1_NODE_ID      unique node name          (default: random UUID prefix)
 *   Q1_HOST         advertised hostname/IP    (default: localhost)
 *   Q1_PORT         HTTP listen port          (default: 9000)
 *   Q1_DATA_DIR     data directory            (default: ./q1-data)
 *   Q1_ETCD         comma-separated endpoints (default: none → standalone)
 *   Q1_RF           replication factor        (default: 1)
 *   Q1_PARTITIONS   number of partitions      (default: 16)
 * </pre>
 */
public final class Q1Server implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(Q1Server.class);

    private final StorageEngine engine;
    private final EtcdCluster   cluster;   // null in standalone mode
    private final S3Router      router;
    private final Undertow      server;
    private final int           port;

    /** Standalone constructor (single node, no replication). */
    public Q1Server(StorageEngine engine, int port) {
        this.engine  = engine;
        this.cluster = null;
        this.port    = port;
        this.router  = new S3Router(engine);
        this.server  = buildServer(port);
    }

    /** Cluster constructor. */
    public Q1Server(StorageEngine engine, EtcdCluster cluster,
                    PartitionRouter partitionRouter, Replicator replicator, int port) {
        this.engine  = engine;
        this.cluster = cluster;
        this.port    = port;
        this.router  = new S3Router(engine, partitionRouter, replicator);
        this.server  = buildServer(port);
    }

    public void start() {
        server.start();
        log.info("Q1 listening on port {}", port);
    }

    public void stop() {
        server.stop();
        router.shutdown();
        log.info("Q1 stopped");
    }

    @Override
    public void close() throws IOException {
        stop();
        if (cluster != null) cluster.close();
        engine.close();
    }

    // ── standalone entry point ────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        String nodeId    = env("Q1_NODE_ID",    "node-" + UUID.randomUUID().toString().substring(0, 8));
        String host      = env("Q1_HOST",       "localhost");
        int    port      = Integer.parseInt(env("Q1_PORT",      "9000"));
        String dataDir   = env("Q1_DATA_DIR",   "q1-data");
        String etcdRaw   = env("Q1_ETCD",       "");
        int    rf        = Integer.parseInt(env("Q1_RF",         "1"));
        int    parts     = Integer.parseInt(env("Q1_PARTITIONS", "16"));

        StorageEngine engine = new StorageEngine(Path.of(dataDir), parts);
        Q1Server      server;

        if (etcdRaw.isBlank()) {
            log.info("Starting in standalone mode (no Q1_ETCD configured)");
            server = new Q1Server(engine, port);
        } else {
            NodeId self = new NodeId(nodeId, host, port);
            ClusterConfig cfg = ClusterConfig.builder()
                    .self(self)
                    .etcdEndpoints(List.of(etcdRaw.split(",")))
                    .replicationFactor(rf)
                    .numPartitions(parts)
                    .build();

            EtcdCluster cluster = new EtcdCluster(cfg);
            cluster.start();

            PartitionRouter partitionRouter = new PartitionRouter(cluster);
            Replicator      replicator      = new HttpReplicator(partitionRouter, rf);

            // Bring lagging partitions up to date before accepting client traffic
            new CatchupManager(cluster).catchUp(engine);

            server = new Q1Server(engine, cluster, partitionRouter, replicator, port);
            log.info("Starting in cluster mode: node={} rf={} partitions={}", self, rf, parts);
        }

        server.start();

        final Q1Server finalServer = server;
        Runtime.getRuntime().addShutdownHook(Thread.ofVirtual().unstarted(() -> {
            log.info("Shutdown signal received");
            try { finalServer.close(); } catch (IOException e) { log.error("Error on close", e); }
        }));
    }

    // ── private ───────────────────────────────────────────────────────────

    private Undertow buildServer(int port) {
        return Undertow.builder()
                .addHttpListener(port, "0.0.0.0")
                .setHandler(router)
                .build();
    }

    private static String env(String name, String defaultValue) {
        String v = System.getenv(name);
        return (v == null || v.isBlank()) ? defaultValue : v;
    }
}
