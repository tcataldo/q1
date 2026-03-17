package io.q1.tests;

import io.q1.api.Q1Server;
import io.q1.api.handler.ShardHandler;
import io.q1.cluster.ClusterConfig;
import io.q1.cluster.EcConfig;
import io.q1.cluster.ErasureCoder;
import io.q1.cluster.HttpShardClient;
import io.q1.cluster.NodeId;
import io.q1.cluster.PartitionRouter;
import io.q1.cluster.RatisCluster;
import io.q1.cluster.ShardPlacement;
import io.q1.core.StorageEngine;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cluster integration tests for erasure coding. Runs a 3-node Q1 cluster
 * (k=2, m=1) with embedded Apache Ratis — no external process required.
 *
 * <p>Verifies: EC write/read round-trip, degraded-mode reconstruction when one
 * shard is missing, delete removes all shards.
 */
class EcClusterIT {

    private static final int EC_K       = 2;
    private static final int EC_M       = 1;
    private static final int PARTITIONS = 4;
    private static final int PORT0      = 19300;
    private static final int PORT1      = 19301;
    private static final int PORT2      = 19302;
    private static final int RAFT0      = 16300;
    private static final int RAFT1      = 16301;
    private static final int RAFT2      = 16302;
    private static final String BUCKET  = "ec-test-bucket";
    private static final String GROUP_ID = "51310005-0000-0000-0000-000000000001";

    private static RatisCluster  cluster0, cluster1, cluster2;
    private static StorageEngine engine0, engine1, engine2;
    private static Q1Server      node0, node1, node2;
    private static HttpClient    http;

    @BeforeAll
    static void startCluster() throws Exception {
        EcConfig     ecConfig = new EcConfig(EC_K, EC_M);
        List<NodeId> peers    = List.of(
                new NodeId("ec-node0", "localhost", PORT0, RAFT0),
                new NodeId("ec-node1", "localhost", PORT1, RAFT1),
                new NodeId("ec-node2", "localhost", PORT2, RAFT2));

        engine0 = new StorageEngine(Files.createTempDirectory("q1-ec0-"), PARTITIONS);
        engine1 = new StorageEngine(Files.createTempDirectory("q1-ec1-"), PARTITIONS);
        engine2 = new StorageEngine(Files.createTempDirectory("q1-ec2-"), PARTITIONS);

        cluster0 = makeCluster(peers, 0, ecConfig, engine0);
        cluster1 = makeCluster(peers, 1, ecConfig, engine1);
        cluster2 = makeCluster(peers, 2, ecConfig, engine2);
        cluster0.start();
        cluster1.start();
        cluster2.start();

        waitForLeader(cluster0, cluster1, cluster2);

        node0 = makeNode(engine0, cluster0, PORT0);
        node1 = makeNode(engine1, cluster1, PORT1);
        node2 = makeNode(engine2, cluster2, PORT2);
        node0.start();
        node1.start();
        node2.start();

        http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        // Create bucket (routed via Raft leader)
        http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + PORT0 + "/" + BUCKET))
                        .PUT(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.discarding());
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
        String key  = "ec-basic-" + System.nanoTime();
        byte[] data = randomBytes(8192);

        assertEquals(200, put(PORT0, key, data), "PUT should succeed");

        assertArrayEquals(data, get(PORT0, key), "node0 must return correct data");
        assertArrayEquals(data, get(PORT1, key), "node1 must return correct data");
        assertArrayEquals(data, get(PORT2, key), "node2 must return correct data");
    }

    @Test
    void ecReconstructsWithOneMissingShardDeleted() throws Exception {
        String key  = "ec-degrade-" + System.nanoTime();
        byte[] data = randomBytes(4096);

        assertEquals(200, put(PORT0, key, data));

        // Delete shard 0 directly from the responsible engine
        ShardPlacement placement = cluster0.computeShardPlacement(BUCKET, key);
        NodeId         shardNode = placement.nodeForShard(0);
        StorageEngine  eng       = engineForNode(shardNode);
        eng.delete(ShardHandler.SHARD_BUCKET, ShardHandler.shardKey(BUCKET, key, 0));

        // k=2, m=1: 2 remaining shards are enough to reconstruct
        assertArrayEquals(data, get(PORT0, key), "Must reconstruct from 2 of 3 shards");
        assertArrayEquals(data, get(PORT1, key), "Must reconstruct from 2 of 3 shards on node1");
    }

    @Test
    void ecDeleteRemovesAllShards() throws Exception {
        String key  = "ec-delete-" + System.nanoTime();
        byte[] data = randomBytes(1024);

        put(PORT0, key, data);
        assertEquals(200, getStatus(PORT0, key));

        int deleteStatus = delete(PORT0, key);
        assertTrue(deleteStatus == 204 || deleteStatus == 200,
                "DELETE should return 2xx, got " + deleteStatus);

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
                        .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.discarding());

        assertEquals(200, resp.statusCode());
        long contentLength = resp.headers().firstValueAsLong("content-length").orElse(-1);
        assertEquals(data.length, contentLength, "Content-Length must match original size");
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private int put(int port, String key, byte[] value) throws Exception {
        return http.send(
                HttpRequest.newBuilder(uri(port, key))
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(value)).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private byte[] get(int port, String key) throws Exception {
        HttpResponse<byte[]> resp = http.send(
                HttpRequest.newBuilder(uri(port, key)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, resp.statusCode(),
                "GET port=" + port + " key=" + key + " → " + resp.statusCode());
        return resp.body();
    }

    private int getStatus(int port, String key) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(port, key)).GET().build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private int delete(int port, String key) throws Exception {
        HttpResponse<Void> resp = http.send(
                HttpRequest.newBuilder(uri(port, key)).DELETE().build(),
                HttpResponse.BodyHandlers.discarding());
        if (resp.statusCode() == 307) {
            String loc = resp.headers().firstValue("Location").orElseThrow();
            resp = http.send(HttpRequest.newBuilder(URI.create(loc)).DELETE().build(),
                    HttpResponse.BodyHandlers.discarding());
        }
        return resp.statusCode();
    }

    private StorageEngine engineForNode(NodeId node) {
        if (node.equals(cluster0.self())) return engine0;
        if (node.equals(cluster1.self())) return engine1;
        return engine2;
    }

    private static RatisCluster makeCluster(List<NodeId> peers, int selfIdx,
                                             EcConfig ecConfig, StorageEngine engine)
            throws Exception {
        ClusterConfig cfg = ClusterConfig.builder()
                .self(peers.get(selfIdx)).peers(peers).numPartitions(PARTITIONS)
                .raftDataDir(Files.createTempDirectory("q1-ecr-" + selfIdx + "-").toString())
                .ecConfig(ecConfig)
                .raftGroupId(GROUP_ID)
                .build();
        return new RatisCluster(cfg, engine);
    }

    private static Q1Server makeNode(StorageEngine engine, RatisCluster cluster, int port) {
        ErasureCoder    coder  = new ErasureCoder(cluster.config().ecConfig());
        HttpShardClient shards = new HttpShardClient();
        return new Q1Server(engine, cluster, new PartitionRouter(cluster), coder, shards, port);
    }

    private static void waitForLeader(RatisCluster... clusters) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            boolean allReady = true;
            for (RatisCluster c : clusters) {
                if (!c.isClusterReady()) { allReady = false; break; }
            }
            if (allReady) return;
            Thread.sleep(100);
        }
        throw new IllegalStateException("Cluster not ready after 10 seconds");
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
