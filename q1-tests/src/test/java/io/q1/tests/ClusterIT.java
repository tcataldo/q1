package io.q1.tests;

import io.q1.api.Q1Server;
import io.q1.cluster.ClusterConfig;
import io.q1.cluster.NodeId;
import io.q1.cluster.PartitionRouter;
import io.q1.cluster.Q1StateMachine;
import io.q1.cluster.RatisCluster;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cluster integration tests. Runs a 2-node Q1 cluster using embedded Apache
 * Ratis — no Docker or external process required.
 *
 * <p>Verifies: Raft replication, non-leader 307 redirect, delete propagation.
 */
class ClusterIT {

    private static final int PARTITIONS = 4;
    private static final int PORT0      = 19200;
    private static final int PORT1      = 19201;
    private static final int RAFT0      = 16200;
    private static final int RAFT1      = 16201;
    private static final String BUCKET  = "cluster-test-bucket";
    private static final String GROUP_ID = "51310003-0000-0000-0000-000000000001";

    private static RatisCluster cluster0, cluster1;
    private static Q1Server     node0, node1;
    private static HttpClient   http;

    @BeforeAll
    static void startCluster() throws Exception {
        List<NodeId> peers = List.of(
                new NodeId("node0", "localhost", PORT0, RAFT0),
                new NodeId("node1", "localhost", PORT1, RAFT1));

        StorageEngine engine0 = new StorageEngine(Files.createTempDirectory("q1-cl0-"), PARTITIONS);
        StorageEngine engine1 = new StorageEngine(Files.createTempDirectory("q1-cl1-"), PARTITIONS);

        ClusterConfig cfg0 = ClusterConfig.builder()
                .self(peers.get(0)).peers(peers).numPartitions(PARTITIONS)
                .raftDataDir(Files.createTempDirectory("q1-raft0-").toString())
                .raftGroupId(GROUP_ID)
                .build();
        ClusterConfig cfg1 = ClusterConfig.builder()
                .self(peers.get(1)).peers(peers).numPartitions(PARTITIONS)
                .raftDataDir(Files.createTempDirectory("q1-raft1-").toString())
                .raftGroupId(GROUP_ID)
                .build();

        Q1StateMachine sm0 = new Q1StateMachine(engine0);
        Q1StateMachine sm1 = new Q1StateMachine(engine1);

        cluster0 = new RatisCluster(cfg0, sm0);
        cluster1 = new RatisCluster(cfg1, sm1);
        cluster0.start();
        cluster1.start();

        // Wait for Raft leader election (quorum = 2 nodes)
        waitForLeader(cluster0, cluster1);

        node0 = new Q1Server(engine0, cluster0, new PartitionRouter(cluster0), PORT0);
        node1 = new Q1Server(engine1, cluster1, new PartitionRouter(cluster1), PORT1);
        node0.start();
        node1.start();

        http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        // Bucket create — go through both nodes (bucket ops not yet replicated via Raft)
        createBucket();
    }

    @AfterAll
    static void stopCluster() throws Exception {
        if (node0 != null) node0.close();
        if (node1 != null) node1.close();
    }

    // ── tests ─────────────────────────────────────────────────────────────

    @Test
    void leaderIsElected() {
        boolean n0 = cluster0.isLocalLeader();
        boolean n1 = cluster1.isLocalLeader();
        assertTrue(n0 ^ n1, "Exactly one node must be the Raft leader");
    }

    @Test
    void replicationOnWrite() throws Exception {
        String key   = "replicated-key";
        byte[] value = "cluster-value".getBytes();

        int status = put(PORT0, key, value);
        assertEquals(200, status, "PUT should succeed");

        // Raft replication is synchronous — follower must have data immediately
        byte[] fromNode1 = get(PORT1, key);
        assertArrayEquals(value, fromNode1, "Follower should return replicated data");
    }

    @Test
    void writesToAnyNodeSucceed() throws Exception {
        // Non-leader nodes transparently proxy writes to the leader (no 307 exposed
        // to the client). Both nodes should return 200 directly.
        String key0 = "proxy-n0-" + System.nanoTime();
        String key1 = "proxy-n1-" + System.nanoTime();
        byte[] value = "data".getBytes();

        assertEquals(200, rawPut(PORT0, key0, value).statusCode(),
                "PUT to node0 should succeed (leader applies or proxy forwards)");
        assertEquals(200, rawPut(PORT1, key1, value).statusCode(),
                "PUT to node1 should succeed (leader applies or proxy forwards)");

        // Raft replicates synchronously — both keys must be on both nodes
        assertEquals(200, getStatus(PORT0, key1), "node0 must have key written via node1");
        assertEquals(200, getStatus(PORT1, key0), "node1 must have key written via node0");
    }

    @Test
    void deleteReplicatedToFollower() throws Exception {
        String key = "delete-cluster-" + System.nanoTime();

        put(PORT0, key, "to-delete".getBytes());
        delete(PORT0, key);

        assertEquals(404, getStatus(PORT0, key), "Node0 should return 404 after delete");
        assertEquals(404, getStatus(PORT1, key), "Node1 should return 404 after delete");
    }

    @Test
    void headOnBothNodes() throws Exception {
        String key   = "head-test-" + System.nanoTime();
        byte[] value = "head-data".getBytes();

        put(PORT0, key, value);

        assertEquals(200, headStatus(PORT0, key), "HEAD on node0 should return 200");
        assertEquals(200, headStatus(PORT1, key), "HEAD on node1 should return 200");
    }

    // ── helpers ───────────────────────────────────────────────────────────

    /** PUT with manual 307 redirect follow. */
    private int put(int port, String key, byte[] value) throws Exception {
        HttpResponse<Void> resp = rawPut(port, key, value);
        if (resp.statusCode() == 307) {
            String loc = resp.headers().firstValue("Location").orElseThrow();
            resp = http.send(
                    HttpRequest.newBuilder(URI.create(loc))
                            .PUT(HttpRequest.BodyPublishers.ofByteArray(value)).build(),
                    HttpResponse.BodyHandlers.discarding());
        }
        return resp.statusCode();
    }

    private HttpResponse<Void> rawPut(int port, String key, byte[] value) throws Exception {
        return http.send(
                HttpRequest.newBuilder(uri(port, key))
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(value)).build(),
                HttpResponse.BodyHandlers.discarding());
    }

    private void delete(int port, String key) throws Exception {
        HttpResponse<Void> resp = http.send(
                HttpRequest.newBuilder(uri(port, key)).DELETE().build(),
                HttpResponse.BodyHandlers.discarding());
        if (resp.statusCode() == 307) {
            String loc = resp.headers().firstValue("Location").orElseThrow();
            http.send(HttpRequest.newBuilder(URI.create(loc)).DELETE().build(),
                    HttpResponse.BodyHandlers.discarding());
        }
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
        return http.send(HttpRequest.newBuilder(uri(port, key)).GET().build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private int headStatus(int port, String key) throws Exception {
        return http.send(
                HttpRequest.newBuilder(uri(port, key))
                        .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private static void createBucket() throws Exception {
        // Bucket ops are routed to the Raft leader — just try both and follow redirects
        for (int port : new int[]{ PORT0, PORT1 }) {
            HttpResponse<Void> resp = http.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/" + BUCKET))
                            .PUT(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.discarding());
            if (resp.statusCode() == 307) {
                String loc = resp.headers().firstValue("Location").orElseThrow();
                resp = http.send(
                        HttpRequest.newBuilder(URI.create(loc))
                                .PUT(HttpRequest.BodyPublishers.noBody()).build(),
                        HttpResponse.BodyHandlers.discarding());
            }
            assertTrue(resp.statusCode() == 200 || resp.statusCode() == 204,
                    "Bucket create on port " + port + " returned " + resp.statusCode());
        }
    }

    /**
     * Block until a leader is elected AND all nodes know who the leader is
     * (i.e. every node reports {@link RatisCluster#isClusterReady()}).
     */
    private static void waitForLeader(RatisCluster c0, RatisCluster c1)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (c0.isClusterReady() && c1.isClusterReady()) return;
            Thread.sleep(100);
        }
        throw new IllegalStateException("Cluster not ready after 10 seconds");
    }

    private static URI uri(int port, String key) {
        return URI.create("http://localhost:" + port + "/" + BUCKET + "/" + key);
    }
}
