package io.q1.tests;

import io.q1.api.Q1Server;
import io.q1.api.handler.ShardHandler;
import io.q1.cluster.CatchupManager;
import io.q1.cluster.ClusterConfig;
import io.q1.cluster.EcConfig;
import io.q1.cluster.ErasureCoder;
import io.q1.cluster.EtcdCluster;
import io.q1.cluster.HttpShardClient;
import io.q1.cluster.NodeId;
import io.q1.cluster.PartitionRouter;
import io.q1.cluster.ShardPlacement;
import io.q1.core.StorageEngine;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cluster integration tests for erasure coding.  Runs a 3-node Q1 cluster
 * (k=2, m=1) against a real etcd instance managed by Testcontainers.
 *
 * <p>Verifies: EC write/read round-trip, degraded-mode reconstruction when one
 * shard is missing, delete removes all shards.
 *
 * <p>Run with: {@code mvn verify -pl q1-tests -Pcluster-tests}
 */
@Testcontainers
class EcClusterIT {

    // ── etcd container ────────────────────────────────────────────────────

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> etcd =
            new GenericContainer<>("gcr.io/etcd-development/etcd:v3.5.17")
                    .withCommand(
                            "etcd",
                            "--listen-client-urls=http://0.0.0.0:2379",
                            "--advertise-client-urls=http://0.0.0.0:2379")
                    .withExposedPorts(2379)
                    .waitingFor(Wait.forListeningPort())
                    .withStartupTimeout(Duration.ofSeconds(60));

    // ── cluster topology ──────────────────────────────────────────────────

    private static final int EC_K        = 2;
    private static final int EC_M        = 1;
    private static final int PARTITIONS  = 4;
    private static final int PORT0       = 19300;
    private static final int PORT1       = 19301;
    private static final int PORT2       = 19302;
    private static final String BUCKET   = "ec-test-bucket";

    private static EtcdCluster   cluster0, cluster1, cluster2;
    private static StorageEngine engine0, engine1, engine2;
    private static Q1Server      node0, node1, node2;
    private static HttpClient    http;

    // ── lifecycle ─────────────────────────────────────────────────────────

    @BeforeAll
    static void startCluster() throws Exception {
        String etcdUrl = "http://localhost:" + etcd.getMappedPort(2379);
        EcConfig ecConfig = new EcConfig(EC_K, EC_M);

        engine0 = new StorageEngine(Files.createTempDirectory("q1-ec-0-"), PARTITIONS);
        engine1 = new StorageEngine(Files.createTempDirectory("q1-ec-1-"), PARTITIONS);
        engine2 = new StorageEngine(Files.createTempDirectory("q1-ec-2-"), PARTITIONS);

        cluster0 = startCluster(etcdUrl, "ec-node0", PORT0, ecConfig);
        cluster1 = startCluster(etcdUrl, "ec-node1", PORT1, ecConfig);
        cluster2 = startCluster(etcdUrl, "ec-node2", PORT2, ecConfig);

        // Wait for elections to settle
        Thread.sleep(4_000);

        node0 = startNode(engine0, cluster0, PORT0);
        node1 = startNode(engine1, cluster1, PORT1);
        node2 = startNode(engine2, cluster2, PORT2);

        http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        // Create bucket on all nodes (not replicated)
        for (int port : new int[]{ PORT0, PORT1, PORT2 }) {
            http.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/" + BUCKET))
                            .PUT(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.discarding());
        }
    }

    @AfterAll
    static void stopCluster() throws Exception {
        if (node0 != null) node0.close();
        if (node1 != null) node1.close();
        if (node2 != null) node2.close();
    }

    // ── tests ─────────────────────────────────────────────────────────────

    @Test
    void ecWriteAndReadFromAllNodes() throws Exception {
        String key   = "ec-basic-" + System.nanoTime();
        byte[] data  = randomBytes(8192);

        int putStatus = put(PORT0, key, data);
        assertEquals(200, putStatus, "PUT should succeed");

        // All 3 nodes should serve the same data (any node can reconstruct)
        assertArrayEquals(data, get(PORT0, key), "node0 must return correct data");
        assertArrayEquals(data, get(PORT1, key), "node1 must return correct data");
        assertArrayEquals(data, get(PORT2, key), "node2 must return correct data");
    }

    @Test
    void ecReconstructsWithOneMissingShardDeleted() throws Exception {
        String key  = "ec-degrade-" + System.nanoTime();
        byte[] data = randomBytes(4096);

        int putStatus = put(PORT0, key, data);
        assertEquals(200, putStatus);

        // Determine which node holds shard 0 for this key
        ShardPlacement placement = cluster0.computeShardPlacement(BUCKET, key);
        NodeId shardZeroNode = placement.nodeForShard(0);

        // Delete shard 0 directly from the responsible engine
        StorageEngine shardZeroEngine = engineForNode(shardZeroNode);
        String sk = ShardHandler.shardKey(BUCKET, key, 0);
        shardZeroEngine.delete(ShardHandler.SHARD_BUCKET, sk);

        // With k=2, m=1: even with 1 shard missing, 2 shards remain → reconstruction
        assertArrayEquals(data, get(PORT0, key), "Must reconstruct from 2 of 3 shards");
        assertArrayEquals(data, get(PORT1, key), "Must reconstruct from 2 of 3 shards on node1");
    }

    @Test
    void ecDeleteRemovesMetadataAndShards() throws Exception {
        String key  = "ec-delete-" + System.nanoTime();
        byte[] data = randomBytes(1024);

        put(PORT0, key, data);

        // Verify it exists
        assertEquals(200, getStatus(PORT0, key));

        // Delete it
        int deleteStatus = delete(PORT0, key);
        // 204 expected (or 200 if redirect followed)
        assertTrue(deleteStatus == 204 || deleteStatus == 200,
                "DELETE should return 2xx, got " + deleteStatus);

        // All nodes should now return 404
        assertEquals(404, getStatus(PORT0, key), "node0: 404 after delete");
        assertEquals(404, getStatus(PORT1, key), "node1: 404 after delete");
        assertEquals(404, getStatus(PORT2, key), "node2: 404 after delete");

    }

    @Test
    void headReturnsCorrectContentLength() throws Exception {
        String key  = "ec-head-" + System.nanoTime();
        byte[] data = randomBytes(5000);

        put(PORT0, key, data);

        HttpResponse<Void> resp = http.send(
                HttpRequest.newBuilder(uri(PORT0, key))
                        .method("HEAD", HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.discarding());

        assertEquals(200, resp.statusCode());
        long contentLength = resp.headers().firstValueAsLong("content-length").orElse(-1);
        assertEquals(data.length, contentLength, "Content-Length must match original object size");
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private int put(int port, String key, byte[] value) throws Exception {
        HttpResponse<Void> resp = http.send(
                HttpRequest.newBuilder(uri(port, key))
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(value))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        return resp.statusCode();
    }

    private byte[] get(int port, String key) throws Exception {
        HttpResponse<byte[]> resp = http.send(
                HttpRequest.newBuilder(uri(port, key)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, resp.statusCode(),
                "GET /" + BUCKET + "/" + key + " from port " + port
                        + " returned " + resp.statusCode());
        return resp.body();
    }

    private int getStatus(int port, String key) throws Exception {
        return http.send(
                HttpRequest.newBuilder(uri(port, key)).GET().build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private int delete(int port, String key) throws Exception {
        HttpResponse<Void> resp = http.send(
                HttpRequest.newBuilder(uri(port, key)).DELETE().build(),
                HttpResponse.BodyHandlers.discarding());
        if (resp.statusCode() == 307) {
            String location = resp.headers().firstValue("Location").orElseThrow();
            resp = http.send(
                    HttpRequest.newBuilder(URI.create(location)).DELETE().build(),
                    HttpResponse.BodyHandlers.discarding());
        }
        return resp.statusCode();
    }

    private StorageEngine engineForNode(NodeId node) {
        if (node.equals(cluster0.self())) return engine0;
        if (node.equals(cluster1.self())) return engine1;
        return engine2;
    }

    private static EtcdCluster startCluster(String etcdUrl, String nodeId, int port,
                                             EcConfig ecConfig) throws Exception {
        ClusterConfig cfg = ClusterConfig.builder()
                .self(new NodeId(nodeId, "localhost", port))
                .etcdEndpoints(List.of(etcdUrl))
                .replicationFactor(1)
                .numPartitions(PARTITIONS)
                .leaseTtlSeconds(5)
                .ecConfig(ecConfig)
                .build();
        EtcdCluster cluster = new EtcdCluster(cfg);
        cluster.start();
        return cluster;
    }

    private static Q1Server startNode(StorageEngine engine, EtcdCluster cluster,
                                      int port) throws Exception {
        PartitionRouter router  = new PartitionRouter(cluster);
        ErasureCoder    coder   = new ErasureCoder(cluster.config().ecConfig());
        HttpShardClient shards  = new HttpShardClient();

        new CatchupManager(cluster).catchUp(engine);

        Q1Server server = new Q1Server(engine, cluster, router, coder, shards, port);
        server.start();
        return server;
    }

    private static URI uri(int port, String key) {
        return URI.create("http://localhost:" + port + "/" + BUCKET + "/" + key);
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        new Random(System.nanoTime()).nextBytes(b);
        return b;
    }
}
