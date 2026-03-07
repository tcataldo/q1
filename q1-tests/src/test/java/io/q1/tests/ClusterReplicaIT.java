package io.q1.tests;

import io.q1.api.Q1Server;
import io.q1.cluster.ClusterConfig;
import io.q1.cluster.CatchupManager;
import io.q1.cluster.EtcdCluster;
import io.q1.cluster.HttpReplicator;
import io.q1.cluster.NodeId;
import io.q1.cluster.PartitionRouter;
import io.q1.cluster.Replicator;
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
 * 3-node cluster tests verifying deterministic replica assignment.
 *
 * <p>Node IDs are chosen so that lexicographic order is unambiguous:
 * <pre>  node-aaa &lt; node-bbb &lt; node-zzz</pre>
 *
 * <p>With RF=2 the replica for a partition is always the first sorted non-leader:
 * <ul>
 *   <li>node-aaa leads P → replica = node-bbb</li>
 *   <li>node-bbb leads P → replica = node-aaa</li>
 *   <li>node-zzz leads P → replica = node-aaa</li>
 * </ul>
 *
 * <p>Consequence: <strong>node-zzz is never a replica</strong> for partitions
 * it does not lead, making it the ideal probe for "non-assigned node" scenarios.
 */
@Testcontainers
class ClusterReplicaIT {

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

    private static final int RF         = 2;
    private static final int PARTITIONS = 4;
    private static final int PORT_AAA   = 19210;
    private static final int PORT_BBB   = 19211;
    private static final int PORT_ZZZ   = 19212;
    private static final String BUCKET  = "replica-it-bucket";

    private static EtcdCluster cAaa, cBbb, cZzz;
    private static Q1Server    nAaa, nBbb, nZzz;
    private static HttpClient  http;

    // ── lifecycle ─────────────────────────────────────────────────────────

    @BeforeAll
    static void startCluster() throws Exception {
        String etcdUrl = "http://localhost:" + etcd.getMappedPort(2379);

        StorageEngine eAaa = new StorageEngine(Files.createTempDirectory("q1-3n-aaa-"), PARTITIONS);
        StorageEngine eBbb = new StorageEngine(Files.createTempDirectory("q1-3n-bbb-"), PARTITIONS);
        StorageEngine eZzz = new StorageEngine(Files.createTempDirectory("q1-3n-zzz-"), PARTITIONS);

        cAaa = new EtcdCluster(cfg("node-aaa", PORT_AAA, etcdUrl));
        cBbb = new EtcdCluster(cfg("node-bbb", PORT_BBB, etcdUrl));
        cZzz = new EtcdCluster(cfg("node-zzz", PORT_ZZZ, etcdUrl));
        cAaa.start();
        cBbb.start();
        cZzz.start();

        // Wait for elections and replica assignments to propagate
        Thread.sleep(5_000);

        PartitionRouter rAaa = new PartitionRouter(cAaa);
        PartitionRouter rBbb = new PartitionRouter(cBbb);
        PartitionRouter rZzz = new PartitionRouter(cZzz);
        Replicator      repAaa = new HttpReplicator(rAaa, RF);
        Replicator      repBbb = new HttpReplicator(rBbb, RF);
        Replicator      repZzz = new HttpReplicator(rZzz, RF);

        new CatchupManager(cAaa).catchUp(eAaa);
        new CatchupManager(cBbb).catchUp(eBbb);
        new CatchupManager(cZzz).catchUp(eZzz);

        nAaa = new Q1Server(eAaa, cAaa, rAaa, repAaa, PORT_AAA);
        nBbb = new Q1Server(eBbb, cBbb, rBbb, repBbb, PORT_BBB);
        nZzz = new Q1Server(eZzz, cZzz, rZzz, repZzz, PORT_ZZZ);
        nAaa.start();
        nBbb.start();
        nZzz.start();

        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        // Buckets are not replicated — create on every node
        for (int port : new int[]{ PORT_AAA, PORT_BBB, PORT_ZZZ }) {
            HttpResponse<Void> r = http.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/" + BUCKET))
                            .PUT(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.discarding());
            assertTrue(r.statusCode() == 200 || r.statusCode() == 204,
                    "Bucket create on port " + port + " returned " + r.statusCode());
        }
    }

    @AfterAll
    static void stopCluster() throws Exception {
        if (nAaa != null) nAaa.close();
        if (nBbb != null) nBbb.close();
        if (nZzz != null) nZzz.close();
    }

    // ── tests ─────────────────────────────────────────────────────────────

    @Test
    void replicaListIsWrittenForAllPartitions() {
        // After elections, every partition must have exactly RF-1 = 1 assigned replica.
        for (int p = 0; p < PARTITIONS; p++) {
            List<NodeId> followers = cAaa.followersFor(p);
            assertEquals(RF - 1, followers.size(),
                    "Partition " + p + " should have RF-1 follower(s), got: " + followers);
        }
    }

    @Test
    void nodeZzzIsNeverAssignedAsReplica() {
        // node-zzz is lexicographically last. With RF=2 the replica is always
        // the first sorted non-leader, so node-zzz is never picked by node-aaa or node-bbb.
        for (int p = 0; p < PARTITIONS; p++) {
            if (cZzz.isLocalLeader(p)) continue; // leader of its own partition — skip
            List<NodeId> followers = cAaa.followersFor(p);
            boolean zzzIsFollower = followers.stream()
                    .anyMatch(n -> "node-zzz".equals(n.id()));
            assertFalse(zzzIsFollower,
                    "node-zzz should never be an assigned replica (partition " + p
                    + ", actual: " + followers + ")");
        }
    }

    @Test
    void nonAssignedNodeDoesNotReceiveLiveWrites() throws Exception {
        // Find a key that hashes to a partition NOT led by node-zzz.
        // For that partition, node-zzz is neither leader nor replica → must not receive the write.
        String key = findKeyForNonZzzLeaderPartition();
        assertNotNull(key, "Could not find a key for a non-zzz-leader partition in 1000 attempts");

        int status = put(PORT_AAA, key, "payload".getBytes());
        assertEquals(200, status, "PUT should succeed (redirect followed if needed)");

        // node-zzz must return 404 — it was not in the replica assignment for this partition
        int zzzStatus = http.send(
                HttpRequest.newBuilder(uri(PORT_ZZZ, key)).GET().build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
        assertEquals(404, zzzStatus,
                "Non-assigned node-zzz must not have the data for key: " + key);
    }

    @Test
    void assignedReplicaReceivesAllWritesForItsPartitions() throws Exception {
        // For partitions led by node-zzz, node-aaa is the assigned replica.
        // Writes to those partitions must appear on node-aaa synchronously.
        String key = findKeyForZzzLeaderPartition();
        assumeNotNull(key, "node-zzz leads no partition in this election — test not applicable");

        int status = put(PORT_ZZZ, key, "zzz-payload".getBytes());
        assertEquals(200, status, "PUT on zzz-leader partition should succeed");

        // node-aaa is the assigned replica → must have the data
        int aaaStatus = http.send(
                HttpRequest.newBuilder(uri(PORT_AAA, key)).GET().build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
        assertEquals(200, aaaStatus,
                "node-aaa (assigned replica for node-zzz's partition) must have the data");
    }

    // ── helpers ───────────────────────────────────────────────────────────

    /** Partition for a key, using the same formula as {@link io.q1.core.StorageEngine}. */
    private int partition(String key) {
        return Math.abs((BUCKET + '\u0000' + key).hashCode()) % PARTITIONS;
    }

    private String findKeyForNonZzzLeaderPartition() {
        for (int i = 0; i < 1000; i++) {
            String k = "probe-nonzzz-" + i;
            if (!cZzz.isLocalLeader(partition(k))) return k;
        }
        return null;
    }

    private String findKeyForZzzLeaderPartition() {
        for (int i = 0; i < 1000; i++) {
            String k = "probe-zzz-" + i;
            if (cZzz.isLocalLeader(partition(k))) return k;
        }
        return null;
    }

    /** PUT with manual redirect following (307 → re-PUT to Location). */
    private int put(int port, String key, byte[] value) throws Exception {
        HttpResponse<Void> resp = http.send(
                HttpRequest.newBuilder(uri(port, key))
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(value)).build(),
                HttpResponse.BodyHandlers.discarding());
        if (resp.statusCode() == 307) {
            String location = resp.headers().firstValue("Location").orElseThrow(
                    () -> new AssertionError("307 without Location header"));
            resp = http.send(
                    HttpRequest.newBuilder(URI.create(location))
                            .PUT(HttpRequest.BodyPublishers.ofByteArray(value)).build(),
                    HttpResponse.BodyHandlers.discarding());
        }
        return resp.statusCode();
    }

    private URI uri(int port, String key) {
        return URI.create("http://localhost:" + port + "/" + BUCKET + "/" + key);
    }

    private static ClusterConfig cfg(String nodeId, int port, String etcdUrl) {
        return ClusterConfig.builder()
                .self(new NodeId(nodeId, "localhost", port))
                .etcdEndpoints(List.of(etcdUrl))
                .replicationFactor(RF)
                .numPartitions(PARTITIONS)
                .leaseTtlSeconds(5)
                .build();
    }

    /** Skip the test gracefully if the condition is null (election non-determinism). */
    private static void assumeNotNull(Object value, String message) {
        org.junit.jupiter.api.Assumptions.assumeTrue(value != null, message);
    }
}
