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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 3-node cluster tests verifying ring-based replica assignment and balanced storage.
 *
 * <p>Node IDs are chosen so that lexicographic order is unambiguous:
 * <pre>  node-aaa &lt; node-bbb &lt; node-zzz</pre>
 *
 * <p>With RF=2 the ring assigns replicas clockwise from the leader:
 * <ul>
 *   <li>node-aaa leads P → replica = node-bbb</li>
 *   <li>node-bbb leads P → replica = node-zzz</li>
 *   <li>node-zzz leads P → replica = node-aaa (wrap)</li>
 * </ul>
 *
 * <p>Each node stores data for exactly the partitions it leads plus the
 * partitions led by its predecessor in the ring — approximately
 * {@code RF / N = 2/3} of the total data, perfectly balanced.
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

    // Ring order (sorted by nodeId)
    private static final String[] NODE_IDS = { "node-aaa", "node-bbb", "node-zzz" };

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

        Thread.sleep(5_000); // wait for elections and replica assignments

        PartitionRouter rAaa = new PartitionRouter(cAaa);
        PartitionRouter rBbb = new PartitionRouter(cBbb);
        PartitionRouter rZzz = new PartitionRouter(cZzz);
        Replicator repAaa = new HttpReplicator(rAaa, RF);
        Replicator repBbb = new HttpReplicator(rBbb, RF);
        Replicator repZzz = new HttpReplicator(rZzz, RF);

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
    void ringBasedReplicaAssignment() {
        // For each partition, the assigned replica must be the node immediately
        // clockwise from the leader in the sorted ring [aaa, bbb, zzz].
        EtcdCluster[] clusters = { cAaa, cBbb, cZzz };

        for (int p = 0; p < PARTITIONS; p++) {
            int leaderIdx = leaderIndex(p, clusters);
            assertNotEquals(-1, leaderIdx, "Partition " + p + " has no leader");

            String expectedReplicaId = NODE_IDS[(leaderIdx + 1) % NODE_IDS.length];
            List<NodeId> followers = cAaa.followersFor(p);

            assertEquals(1, followers.size(),
                    "Partition " + p + " should have 1 replica");
            assertEquals(expectedReplicaId, followers.get(0).id(),
                    "Partition " + p + " (leader=" + NODE_IDS[leaderIdx]
                    + ") should have replica " + expectedReplicaId);
        }
    }

    @Test
    void replicaCountEqualsLeadershipOfPredecessor() {
        // Ring invariant: the node at position i receives exactly as many replica
        // assignments as its predecessor (at position i-1) has partition leaderships.
        //
        //   Ring: [aaa(0), bbb(1), zzz(2)]
        //   If aaa leads k partitions → bbb gets k replica slots
        //   If bbb leads j partitions → zzz gets j replica slots
        //   If zzz leads m partitions → aaa gets m replica slots
        //
        // This holds regardless of how elections are distributed and is what
        // distinguishes ring assignment from the old "always first sorted" approach.
        EtcdCluster[] clusters = { cAaa, cBbb, cZzz };

        int[] leadCounts = new int[3];
        for (int p = 0; p < PARTITIONS; p++) {
            int li = leaderIndex(p, clusters);
            if (li >= 0) leadCounts[li]++;
        }

        Map<String, Integer> replicaCounts = new HashMap<>(Map.of(
                "node-aaa", 0, "node-bbb", 0, "node-zzz", 0));
        for (int p = 0; p < PARTITIONS; p++) {
            cAaa.followersFor(p).forEach(n -> replicaCounts.merge(n.id(), 1, Integer::sum));
        }

        for (int i = 0; i < NODE_IDS.length; i++) {
            int predecessorIdx  = (i - 1 + NODE_IDS.length) % NODE_IDS.length;
            int expectedReplicas = leadCounts[predecessorIdx];
            int actualReplicas   = replicaCounts.get(NODE_IDS[i]);
            assertEquals(expectedReplicas, actualReplicas,
                    NODE_IDS[i] + " replica count should equal leadership count of "
                    + NODE_IDS[predecessorIdx] + " (" + expectedReplicas + ")");
        }
    }

    @Test
    void nonAssignedNodeDoesNotReceiveLiveWrites() throws Exception {
        // Pick any key; determine leader, replica, and excluded node for its partition.
        // The excluded node must return 404 after a successful write.
        PartitionRoles roles = findAnyRoles();
        assertNotNull(roles, "Could not resolve partition roles after 1000 key probes");

        int status = put(roles.leaderPort, roles.key, "value".getBytes());
        assertEquals(200, status, "PUT should succeed");

        assertEquals(200, getStatus(roles.replicaPort, roles.key),
                "Assigned replica must have the data (synchronous replication)");
        assertEquals(404, getStatus(roles.excludedPort, roles.key),
                "Non-assigned node must not have the data");
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private record PartitionRoles(String key, int leaderPort, int replicaPort, int excludedPort) {}

    private static final int[] PORTS = { PORT_AAA, PORT_BBB, PORT_ZZZ };

    /**
     * Probes keys until one resolves to a partition where all three roles
     * (leader, replica, excluded) are clearly determined.
     */
    private PartitionRoles findAnyRoles() {
        EtcdCluster[] clusters = { cAaa, cBbb, cZzz };
        for (int i = 0; i < 1000; i++) {
            String k = "probe-" + i;
            int p = partition(k);
            int leaderIdx = leaderIndex(p, clusters);
            if (leaderIdx < 0) continue;
            int replicaIdx  = (leaderIdx + 1) % 3;
            int excludedIdx = (leaderIdx + 2) % 3;
            return new PartitionRoles(k, PORTS[leaderIdx], PORTS[replicaIdx], PORTS[excludedIdx]);
        }
        return null;
    }

    private static int leaderIndex(int partition, EtcdCluster[] clusters) {
        for (int i = 0; i < clusters.length; i++) {
            if (clusters[i].isLocalLeader(partition)) return i;
        }
        return -1;
    }

    private int partition(String key) {
        return Math.abs((BUCKET + '\u0000' + key).hashCode()) % PARTITIONS;
    }

    private int put(int port, String key, byte[] value) throws Exception {
        HttpResponse<Void> resp = http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/" + BUCKET + "/" + key))
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(value)).build(),
                HttpResponse.BodyHandlers.discarding());
        if (resp.statusCode() == 307) {
            String loc = resp.headers().firstValue("Location").orElseThrow(
                    () -> new AssertionError("307 without Location header"));
            resp = http.send(
                    HttpRequest.newBuilder(URI.create(loc))
                            .PUT(HttpRequest.BodyPublishers.ofByteArray(value)).build(),
                    HttpResponse.BodyHandlers.discarding());
        }
        return resp.statusCode();
    }

    private int getStatus(int port, String key) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/" + BUCKET + "/" + key))
                        .GET().build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
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
}
