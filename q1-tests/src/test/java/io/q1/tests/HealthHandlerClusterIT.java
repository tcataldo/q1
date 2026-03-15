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
 * Integration tests for {@code GET /healthz} in cluster (Raft replication) mode.
 *
 * <p>Starts a 2-node in-process Ratis cluster and verifies the health endpoint
 * response for both leader and follower states.
 */
class HealthHandlerClusterIT {

    private static final int PARTITIONS = 4;
    private static final int PORT0      = 19600;
    private static final int PORT1      = 19601;
    private static final int RAFT0      = 16600;
    private static final int RAFT1      = 16601;
    private static final String GROUP_ID = "51310008-0000-0000-0000-000000000001";

    private static RatisCluster cluster0, cluster1;
    private static Q1Server     node0, node1;
    private static HttpClient   http;

    @BeforeAll
    static void startCluster() throws Exception {
        List<NodeId> peers = List.of(
                new NodeId("hh-node0", "localhost", PORT0, RAFT0),
                new NodeId("hh-node1", "localhost", PORT1, RAFT1));

        StorageEngine engine0 = new StorageEngine(
                Files.createTempDirectory("q1-hh0-"), PARTITIONS);
        StorageEngine engine1 = new StorageEngine(
                Files.createTempDirectory("q1-hh1-"), PARTITIONS);

        ClusterConfig cfg0 = ClusterConfig.builder()
                .self(peers.get(0)).peers(peers).numPartitions(PARTITIONS)
                .raftDataDir(Files.createTempDirectory("q1-hh-raft0-").toString())
                .raftGroupId(GROUP_ID)
                .build();
        ClusterConfig cfg1 = ClusterConfig.builder()
                .self(peers.get(1)).peers(peers).numPartitions(PARTITIONS)
                .raftDataDir(Files.createTempDirectory("q1-hh-raft1-").toString())
                .raftGroupId(GROUP_ID)
                .build();

        cluster0 = new RatisCluster(cfg0, new Q1StateMachine(engine0));
        cluster1 = new RatisCluster(cfg1, new Q1StateMachine(engine1));
        cluster0.start();
        cluster1.start();

        waitForLeader(cluster0, cluster1);

        node0 = new Q1Server(engine0, cluster0, new PartitionRouter(cluster0), PORT0);
        node1 = new Q1Server(engine1, cluster1, new PartitionRouter(cluster1), PORT1);
        node0.start();
        node1.start();

        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterAll
    static void stopCluster() throws Exception {
        if (node0 != null) node0.close();
        if (node1 != null) node1.close();
    }

    // ── tests ─────────────────────────────────────────────────────────────

    @Test
    void healthzReturns200WhenClusterReady() throws Exception {
        // Both nodes are ready — both should return 200
        assertEquals(200, healthzStatus(PORT0), "node0 /healthz should be 200");
        assertEquals(200, healthzStatus(PORT1), "node1 /healthz should be 200");
    }

    @Test
    void healthzContentTypeIsJson() throws Exception {
        var resp = healthz(PORT0);
        assertTrue(resp.headers().firstValue("content-type")
                       .orElse("").contains("application/json"),
                "Content-Type should be application/json");
    }

    @Test
    void healthzClusterModeFields() throws Exception {
        String body = healthz(PORT0).body();
        assertAll("cluster-mode /healthz must include cluster fields",
            () -> assertTrue(body.contains("\"nodeId\""),    "missing nodeId"),
            () -> assertTrue(body.contains("\"mode\""),      "missing mode"),
            () -> assertTrue(body.contains("\"status\""),    "missing status"),
            () -> assertTrue(body.contains("\"leader\""),    "missing leader field"),
            () -> assertTrue(body.contains("\"peers\""),     "missing peers field"),
            () -> assertTrue(body.contains("\"partitions\""),"missing partitions"),
            () -> assertTrue(body.contains("replication"),   "mode should be 'replication'"),
            () -> assertTrue(body.contains("\"ok\""),        "status should be ok")
        );
    }

    @Test
    void healthzLeaderAndFollowerDiffer() throws Exception {
        String body0 = healthz(PORT0).body();
        String body1 = healthz(PORT1).body();

        // Exactly one of the two nodes should be the leader
        boolean n0Leader = body0.contains("\"leader\":true");
        boolean n1Leader = body1.contains("\"leader\":true");
        assertTrue(n0Leader ^ n1Leader,
                "Exactly one node should report leader:true");
    }

    @Test
    void healthzFollowerIncludesLeaderUrl() throws Exception {
        // The follower's response should include a leaderUrl pointing to the leader
        String body0 = healthz(PORT0).body();
        String body1 = healthz(PORT1).body();

        String followerBody = body0.contains("\"leader\":true") ? body1 : body0;
        assertTrue(followerBody.contains("\"leaderUrl\""),
                "Follower should include leaderUrl in /healthz response");
    }

    @Test
    void healthzLeaderDoesNotIncludeLeaderUrl() throws Exception {
        String body0 = healthz(PORT0).body();
        String body1 = healthz(PORT1).body();

        String leaderBody = body0.contains("\"leader\":true") ? body0 : body1;
        assertFalse(leaderBody.contains("\"leaderUrl\""),
                "Leader should NOT include leaderUrl in /healthz response");
    }

    @Test
    void healthzNoEcFieldInReplicationMode() throws Exception {
        String body = healthz(PORT0).body();
        assertFalse(body.contains("\"ec\""),
                "Replication mode /healthz should not include ec config field");
    }

    @Test
    void healthzPeersCountMatchesClusterSize() throws Exception {
        String body = healthz(PORT0).body();
        // 2-node cluster → peers should be reported as 2
        assertTrue(body.contains("\"peers\":2"),
                "2-node cluster should report peers:2, got: " + body);
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private static HttpResponse<String> healthz(int port) throws Exception {
        return http.send(
                HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/healthz")).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static int healthzStatus(int port) throws Exception {
        return http.send(
                HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/healthz")).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private static void waitForLeader(RatisCluster c0, RatisCluster c1)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (c0.isClusterReady() && c1.isClusterReady()) return;
            Thread.sleep(100);
        }
        throw new IllegalStateException("Cluster not ready after 10 seconds");
    }
}
