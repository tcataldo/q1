package io.q1.tests;

import io.q1.api.Q1Server;
import io.q1.cluster.ClusterConfig;
import io.q1.cluster.NodeId;
import io.q1.cluster.PartitionRouter;
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
 * 3-node cluster tests. Verifies that with a single global Raft group all
 * nodes converge to the same state after writes.
 *
 * <p>With Raft, every committed write is applied on every member of the group
 * via the state machine — there is no "non-assigned" node.
 */
class ClusterReplicaIT {

    private static final int PARTITIONS = 4;
    private static final int PORT_A     = 19210;
    private static final int PORT_B     = 19211;
    private static final int PORT_C     = 19212;
    private static final int RAFT_A     = 16210;
    private static final int RAFT_B     = 16211;
    private static final int RAFT_C     = 16212;
    private static final String BUCKET  = "replica-it-bucket";
    private static final String GROUP_ID = "51310004-0000-0000-0000-000000000001";

    private static RatisCluster cA, cB, cC;
    private static Q1Server     nA, nB, nC;
    private static HttpClient   http;

    @BeforeAll
    static void startCluster() throws Exception {
        List<NodeId> peers = List.of(
                new NodeId("node-aaa", "localhost", PORT_A, RAFT_A),
                new NodeId("node-bbb", "localhost", PORT_B, RAFT_B),
                new NodeId("node-zzz", "localhost", PORT_C, RAFT_C));

        StorageEngine eA = new StorageEngine(Files.createTempDirectory("q1-3n-a-"), PARTITIONS);
        StorageEngine eB = new StorageEngine(Files.createTempDirectory("q1-3n-b-"), PARTITIONS);
        StorageEngine eC = new StorageEngine(Files.createTempDirectory("q1-3n-c-"), PARTITIONS);

        cA = new RatisCluster(cfg(peers, 0), eA);
        cB = new RatisCluster(cfg(peers, 1), eB);
        cC = new RatisCluster(cfg(peers, 2), eC);
        cA.start();
        cB.start();
        cC.start();

        waitForLeader(cA, cB, cC);

        nA = new Q1Server(eA, cA, new PartitionRouter(cA), PORT_A);
        nB = new Q1Server(eB, cB, new PartitionRouter(cB), PORT_B);
        nC = new Q1Server(eC, cC, new PartitionRouter(cC), PORT_C);
        nA.start();
        nB.start();
        nC.start();

        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        createBucket();
    }

    @AfterAll
    static void stopCluster() throws Exception {
        if (nA != null) nA.close();
        if (nB != null) nB.close();
        if (nC != null) nC.close();
    }

    // ── tests ─────────────────────────────────────────────────────────────

    @Test
    void exactlyOneLeaderElected() {
        int leaderCount = (cA.isLocalLeader() ? 1 : 0)
                        + (cB.isLocalLeader() ? 1 : 0)
                        + (cC.isLocalLeader() ? 1 : 0);
        assertEquals(1, leaderCount, "Exactly one node must be the Raft leader");
    }

    @Test
    void allNodesReceiveLiveWrites() throws Exception {
        String key   = "all-nodes-" + System.nanoTime();
        byte[] value = "shared-value".getBytes();

        int status = put(leaderPort(), key, value);
        assertEquals(200, status, "PUT to leader should succeed");

        // Raft applies the write on all nodes before returning 200
        for (int port : new int[]{ PORT_A, PORT_B, PORT_C }) {
            assertEquals(200, getStatus(port, key),
                    "Every node must have the data after Raft commit (port=" + port + ")");
        }
    }

    @Test
    void deleteReplicatedToAllNodes() throws Exception {
        String key = "delete-3n-" + System.nanoTime();

        put(leaderPort(), key, "value".getBytes());
        delete(leaderPort(), key);

        for (int port : new int[]{ PORT_A, PORT_B, PORT_C }) {
            assertEquals(404, getStatus(port, key),
                    "Every node must return 404 after delete (port=" + port + ")");
        }
    }

    @Test
    void writesToAnyNodeSucceed() throws Exception {
        // Non-leader nodes transparently proxy writes to the leader — no 307 is
        // exposed to the client. A PUT to any node must return 200 and the data
        // must be visible on every node via Raft synchronous replication.
        String keyViaLeader    = "any-leader-"    + System.nanoTime();
        String keyViaNonLeader = "any-follower-"  + System.nanoTime();
        byte[] value = "x".getBytes();

        assertEquals(200, rawPut(leaderPort(),    keyViaLeader,    value).statusCode(),
                "Write to leader must succeed");
        assertEquals(200, rawPut(nonLeaderPort(), keyViaNonLeader, value).statusCode(),
                "Write to non-leader must succeed via transparent proxy");

        for (int port : new int[]{ PORT_A, PORT_B, PORT_C }) {
            assertEquals(200, getStatus(port, keyViaLeader),
                    "key written via leader must be on port=" + port);
            assertEquals(200, getStatus(port, keyViaNonLeader),
                    "key written via non-leader must be on port=" + port);
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private int leaderPort() {
        if (cA.isLocalLeader()) return PORT_A;
        if (cB.isLocalLeader()) return PORT_B;
        return PORT_C;
    }

    private int nonLeaderPort() {
        if (!cA.isLocalLeader()) return PORT_A;
        if (!cB.isLocalLeader()) return PORT_B;
        return PORT_C;
    }

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

    private int getStatus(int port, String key) throws Exception {
        return http.send(
                HttpRequest.newBuilder(uri(port, key)).GET().build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private static void createBucket() throws Exception {
        // Route through the leader (will 307 if not leader)
        for (int port : new int[]{ PORT_A, PORT_B, PORT_C }) {
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
            break; // Raft replicates the bucket creation to all nodes
        }
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

    private static ClusterConfig cfg(List<NodeId> peers, int selfIdx)
            throws Exception {
        return ClusterConfig.builder()
                .self(peers.get(selfIdx)).peers(peers).numPartitions(PARTITIONS)
                .raftDataDir(Files.createTempDirectory("q1-raft-" + selfIdx + "-").toString())
                .raftGroupId(GROUP_ID)
                .build();
    }

    private static URI uri(int port, String key) {
        return URI.create("http://localhost:" + port + "/" + BUCKET + "/" + key);
    }
}
