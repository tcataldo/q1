package io.q1.tests;

import io.q1.api.Q1Server;
import io.q1.cluster.ClusterConfig;
import io.q1.cluster.EtcdCluster;
import io.q1.cluster.HttpReplicator;
import io.q1.cluster.NodeId;
import io.q1.cluster.PartitionRouter;
import io.q1.cluster.Replicator;
import io.q1.cluster.CatchupManager;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cluster integration tests.  Runs a 2-node Q1 cluster against a real etcd
 * instance managed by Testcontainers (bitnami/etcd).
 *
 * <p>Verifies: synchronous replication, non-leader 307 redirect, delete propagation.
 *
 * <p>Run with: {@code mvn verify -pl q1-tests}
 */
@Testcontainers
class ClusterIT {

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

    private static final int RF         = 2;
    private static final int PARTITIONS = 4; // small for fast elections
    private static final int PORT0      = 19200;
    private static final int PORT1      = 19201;
    private static final String BUCKET  = "cluster-test-bucket";

    private static Q1Server   node0, node1;
    private static HttpClient http;

    // ── lifecycle ─────────────────────────────────────────────────────────

    @BeforeAll
    static void startCluster() throws Exception {
        String etcdUrl = "http://localhost:" + etcd.getMappedPort(2379);

        StorageEngine engine0 = new StorageEngine(Files.createTempDirectory("q1-cluster-0-"), PARTITIONS);
        StorageEngine engine1 = new StorageEngine(Files.createTempDirectory("q1-cluster-1-"), PARTITIONS);

        ClusterConfig cfg0 = ClusterConfig.builder()
                .self(new NodeId("node0", "localhost", PORT0))
                .etcdEndpoints(List.of(etcdUrl))
                .replicationFactor(RF)
                .numPartitions(PARTITIONS)
                .leaseTtlSeconds(5)
                .build();

        ClusterConfig cfg1 = ClusterConfig.builder()
                .self(new NodeId("node1", "localhost", PORT1))
                .etcdEndpoints(List.of(etcdUrl))
                .replicationFactor(RF)
                .numPartitions(PARTITIONS)
                .leaseTtlSeconds(5)
                .build();

        EtcdCluster cluster0 = new EtcdCluster(cfg0);
        EtcdCluster cluster1 = new EtcdCluster(cfg1);
        cluster0.start();
        cluster1.start();

        // Wait for elections to settle before starting catchup
        Thread.sleep(4_000);

        PartitionRouter router0 = new PartitionRouter(cluster0);
        PartitionRouter router1 = new PartitionRouter(cluster1);
        Replicator      rep0    = new HttpReplicator(router0, RF);
        Replicator      rep1    = new HttpReplicator(router1, RF);

        new CatchupManager(cluster0).catchUp(engine0);
        new CatchupManager(cluster1).catchUp(engine1);

        node0 = new Q1Server(engine0, cluster0, router0, rep0, PORT0);
        node1 = new Q1Server(engine1, cluster1, router1, rep1, PORT1);
        node0.start();
        node1.start();

        http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build(); // redirects handled manually for clarity

        // Create bucket — try node0; if redirected follow to node1
        createBucket();
    }

    @AfterAll
    static void stopCluster() throws Exception {
        // Q1Server.close() already closes the cluster and engine; stop them here.
        if (node0 != null) node0.close();
        if (node1 != null) node1.close();
    }

    // ── tests ─────────────────────────────────────────────────────────────

    @Test
    void replicationOnWrite() throws Exception {
        String key   = "replicated-key";
        byte[] value = "cluster-value".getBytes();

        int status = put(PORT0, key, value);
        assertEquals(200, status, "PUT should succeed (with redirect if needed)");

        // Replication is synchronous — follower must have the data immediately
        byte[] fromNode1 = get(PORT1, key);
        assertArrayEquals(value, fromNode1, "Follower should return replicated data");
    }

    @Test
    void nonLeaderRedirects() throws Exception {
        // At least one node must be a non-leader for some partition.
        // We send a raw PUT (no redirect follow) and expect either 200 (leader) or 307 (redirect).
        String key   = "redirect-test-" + System.nanoTime();
        byte[] value = "data".getBytes();

        HttpResponse<Void> raw0 = rawPut(PORT0, key, value);
        HttpResponse<Void> raw1 = rawPut(PORT1, key, value);

        // For this key, exactly one node is leader (returns 200) and the other redirects (307)
        boolean node0Leader = raw0.statusCode() == 200;
        boolean node1Leader = raw1.statusCode() == 200;

        // With 2 nodes and 4 partitions, each node leads some partitions.
        // The specific key may land on a partition where node0 is leader or not.
        // We just assert the non-leader returned 307.
        if (!node0Leader) {
            assertEquals(307, raw0.statusCode(), "Non-leader must return 307");
            assertNotNull(raw0.headers().firstValue("Location").orElse(null),
                    "307 must include Location header");
        }
        if (!node1Leader) {
            assertEquals(307, raw1.statusCode(), "Non-leader must return 307");
        }
    }

    @Test
    void deleteReplicatedToFollower() throws Exception {
        String key = "delete-cluster-" + System.nanoTime();

        // Write then delete — both via node0 (follows redirect automatically)
        put(PORT0, key, "to-delete".getBytes());
        delete(PORT0, key);

        // Both nodes should return 404
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

    /** PUT with manual redirect following (handles 307 → re-PUT to Location). */
    private int put(int port, String key, byte[] value) throws Exception {
        HttpResponse<Void> resp = rawPut(port, key, value);
        if (resp.statusCode() == 307) {
            String location = resp.headers().firstValue("Location").orElseThrow(
                    () -> new AssertionError("307 without Location header"));
            resp = http.send(
                    HttpRequest.newBuilder(URI.create(location))
                            .PUT(HttpRequest.BodyPublishers.ofByteArray(value))
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
        }
        return resp.statusCode();
    }

    private HttpResponse<Void> rawPut(int port, String key, byte[] value) throws Exception {
        return http.send(
                HttpRequest.newBuilder(uri(port, key))
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(value))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
    }

    private void delete(int port, String key) throws Exception {
        HttpResponse<Void> resp = http.send(
                HttpRequest.newBuilder(uri(port, key)).DELETE().build(),
                HttpResponse.BodyHandlers.discarding());
        if (resp.statusCode() == 307) {
            String location = resp.headers().firstValue("Location").orElseThrow();
            http.send(
                    HttpRequest.newBuilder(URI.create(location)).DELETE().build(),
                    HttpResponse.BodyHandlers.discarding());
        }
    }

    private byte[] get(int port, String key) throws Exception {
        HttpResponse<byte[]> resp = http.send(
                HttpRequest.newBuilder(uri(port, key)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, resp.statusCode(),
                "GET /" + BUCKET + "/" + key + " from port " + port + " returned " + resp.statusCode());
        return resp.body();
    }

    private int getStatus(int port, String key) throws Exception {
        return http.send(
                HttpRequest.newBuilder(uri(port, key)).GET().build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private int headStatus(int port, String key) throws Exception {
        return http.send(
                HttpRequest.newBuilder(uri(port, key))
                        .method("HEAD", HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    /** Create the test bucket on every node (bucket state is not replicated). */
    private static void createBucket() throws Exception {
        for (int port : new int[]{ PORT0, PORT1 }) {
            HttpResponse<Void> resp = http.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/" + BUCKET))
                            .PUT(HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
            assertTrue(resp.statusCode() == 200 || resp.statusCode() == 204,
                    "Bucket create on port " + port + " returned " + resp.statusCode());
        }
    }

    private static URI uri(int port, String key) {
        return URI.create("http://localhost:" + port + "/" + BUCKET + "/" + key);
    }
}
