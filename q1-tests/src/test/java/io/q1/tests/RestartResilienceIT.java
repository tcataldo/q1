package io.q1.tests;

import io.q1.api.Q1Server;
import io.q1.cluster.ClusterConfig;
import io.q1.cluster.NodeId;
import io.q1.cluster.PartitionRouter;
import io.q1.cluster.Q1StateMachine;
import io.q1.cluster.RatisCluster;
import io.q1.core.StorageEngine;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cluster resilience tests: node restarts, leader failover, snapshot recovery.
 *
 * <p>Each test creates its own 3-node in-process cluster and tears it down
 * independently so failures are isolated. Port ranges are allocated per test
 * to avoid OS TIME_WAIT collisions if a previous test's close is slow.
 *
 * <h3>Scenarios</h3>
 * <ul>
 *   <li>{@link #followerRestartPreservesData}: data survives a follower stop/restart.</li>
 *   <li>{@link #writesWhileFollowerDown}: missed entries are replayed on follower rejoin.</li>
 *   <li>{@link #leaderFailoverElectsNewLeader}: new leader elected, old leader catches up on rejoin.</li>
 *   <li>{@link #snapshotRecoveryAfterRestart}: snapshot taken before restart is loaded on startup.</li>
 * </ul>
 */
class RestartResilienceIT {

    private static final int  PARTITIONS = 4;
    private static final String BUCKET   = "resilience-bucket";
    /** Unique group UUID so background Ratis threads don't bleed into other test classes. */
    private static final String GROUP_ID = "51310002-0000-0000-0000-000000000001";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    // ── test cases ────────────────────────────────────────────────────────

    /**
     * Write 10 keys, restart a follower from the same persisted directories,
     * verify all keys are accessible on the restarted follower.
     */
    @Test
    void followerRestartPreservesData() throws Exception {
        List<NodeId> peers = peers(19440, 16440);
        ClusterNode[] nodes = startCluster(peers);
        try {
            createBucket(nodes);
            int leader = leaderIdx(nodes);

            for (int i = 0; i < 10; i++) {
                assertEquals(200, put(nodes[leader], "key-" + i, ("val-" + i).getBytes()));
            }

            int follower = firstFollowerIdx(nodes, leader);
            nodes[follower].stop();
            nodes[follower].start(peers);
            waitReady(nodes);

            for (int i = 0; i < 10; i++) {
                byte[] got = waitForKey(nodes[follower], "key-" + i, 10_000);
                assertArrayEquals(("val-" + i).getBytes(), got,
                        "key-" + i + " must survive follower restart");
            }
        } finally {
            stopAll(nodes);
        }
    }

    /**
     * Stop a follower, write 10 keys to the leader (quorum still holds: 2/3),
     * restart the follower and verify it replays all missed entries.
     */
    @Test
    void writesWhileFollowerDown() throws Exception {
        List<NodeId> peers = peers(19445, 16445);
        ClusterNode[] nodes = startCluster(peers);
        try {
            createBucket(nodes);
            int leader   = leaderIdx(nodes);
            int follower = firstFollowerIdx(nodes, leader);

            nodes[follower].stop();

            for (int i = 0; i < 10; i++) {
                assertEquals(200, put(nodes[leader], "missed-" + i, ("v" + i).getBytes()));
            }

            nodes[follower].start(peers);
            waitReady(nodes);

            for (int i = 0; i < 10; i++) {
                byte[] got = waitForKey(nodes[follower], "missed-" + i, 15_000);
                assertArrayEquals(("v" + i).getBytes(), got,
                        "missed-" + i + " must be on follower after catch-up");
            }
        } finally {
            stopAll(nodes);
        }
    }

    /**
     * Stop the current leader, verify a new leader is elected among the survivors
     * and that writes continue. Then restart the old leader and verify it catches
     * up with all writes that happened while it was down.
     */
    @Test
    void leaderFailoverElectsNewLeader() throws Exception {
        List<NodeId> peers = peers(19450, 16450);
        ClusterNode[] nodes = startCluster(peers);
        try {
            createBucket(nodes);
            int oldLeaderIdx = leaderIdx(nodes);

            for (int i = 0; i < 5; i++) {
                assertEquals(200, put(nodes[oldLeaderIdx], "before-" + i, "pre".getBytes()));
            }

            nodes[oldLeaderIdx].stop();

            // Wait for a new leader among the 2 survivors
            ClusterNode[] survivors = without(nodes, oldLeaderIdx);
            waitReady(survivors);

            int newLeaderInSurvivors = leaderIdx(survivors);
            for (int i = 0; i < 5; i++) {
                assertEquals(200, put(survivors[newLeaderInSurvivors], "after-" + i, "post".getBytes()));
            }

            // Both survivors must have all 10 keys
            for (ClusterNode n : survivors) {
                for (int i = 0; i < 5; i++) {
                    assertEquals(200, getStatus(n, "before-" + i),
                            "before-" + i + " on " + n.nodeId.id());
                    assertEquals(200, getStatus(n, "after-" + i),
                            "after-" + i + " on " + n.nodeId.id());
                }
            }

            // Restart old leader — it rejoins as follower and catches up
            nodes[oldLeaderIdx].start(peers);
            waitReady(nodes);

            for (int i = 0; i < 5; i++) {
                byte[] got = waitForKey(nodes[oldLeaderIdx], "after-" + i, 15_000);
                assertArrayEquals("post".getBytes(), got,
                        "after-" + i + " must be on recovered old leader");
            }
        } finally {
            stopAll(nodes);
        }
    }

    /**
     * Force a snapshot on a follower, stop it, restart it, verify the snapshot
     * file was created and all data is accessible after restart.
     *
     * <p>The snapshot acts as a low-water mark: Ratis will only replay log
     * entries that follow it, so restart time is bounded regardless of history.
     */
    @Test
    void snapshotRecoveryAfterRestart() throws Exception {
        List<NodeId> peers = peers(19455, 16455);
        ClusterNode[] nodes = startCluster(peers);
        try {
            createBucket(nodes);
            int leader   = leaderIdx(nodes);
            int follower = firstFollowerIdx(nodes, leader);

            for (int i = 0; i < 20; i++) {
                assertEquals(200, put(nodes[leader], "snap-" + i, ("s" + i).getBytes()));
            }

            // Trigger snapshot directly on the follower state machine
            long snapIndex = nodes[follower].sm.takeSnapshot();
            assertTrue(snapIndex >= 0,
                    "takeSnapshot() must return a valid (non-negative) index, got " + snapIndex);

            assertTrue(snapshotExists(nodes[follower]),
                    "A snapshot marker file must exist in the Raft state machine directory");

            nodes[follower].stop();
            nodes[follower].start(peers);
            waitReady(nodes);

            for (int i = 0; i < 20; i++) {
                byte[] got = waitForKey(nodes[follower], "snap-" + i, 10_000);
                assertArrayEquals(("s" + i).getBytes(), got,
                        "snap-" + i + " must survive snapshot + restart");
            }
        } finally {
            stopAll(nodes);
        }
    }

    // ── cluster lifecycle helpers ─────────────────────────────────────────

    private ClusterNode[] startCluster(List<NodeId> peers) throws Exception {
        ClusterNode[] nodes = new ClusterNode[peers.size()];
        for (int i = 0; i < peers.size(); i++) {
            nodes[i] = new ClusterNode(
                    peers.get(i),
                    Files.createTempDirectory("q1-res-data-"),
                    Files.createTempDirectory("q1-res-raft-"));
            nodes[i].start(peers);
        }
        waitReady(nodes);
        return nodes;
    }

    private void waitReady(ClusterNode... nodes) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            boolean ok = true;
            for (ClusterNode n : nodes) {
                if (!n.isReady()) { ok = false; break; }
            }
            if (ok) return;
            Thread.sleep(100);
        }
        throw new IllegalStateException("Cluster not ready after 15 s");
    }

    private void stopAll(ClusterNode[] nodes) {
        for (ClusterNode n : nodes) {
            try { n.stop(); } catch (Exception ignored) {}
        }
    }

    private int leaderIdx(ClusterNode[] nodes) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            for (int i = 0; i < nodes.length; i++) {
                if (nodes[i].cluster != null && nodes[i].cluster.isLocalLeader()) return i;
            }
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        throw new IllegalStateException("No leader found");
    }

    private int firstFollowerIdx(ClusterNode[] nodes, int leaderIdx) {
        for (int i = 0; i < nodes.length; i++) {
            if (i != leaderIdx) return i;
        }
        throw new IllegalStateException("No follower");
    }

    /** Returns all nodes except the one at {@code excludeIdx}. */
    private ClusterNode[] without(ClusterNode[] nodes, int excludeIdx) {
        ClusterNode[] result = new ClusterNode[nodes.length - 1];
        int j = 0;
        for (int i = 0; i < nodes.length; i++) {
            if (i != excludeIdx) result[j++] = nodes[i];
        }
        return result;
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────

    private void createBucket(ClusterNode[] nodes) throws Exception {
        // With transparent proxy, hitting any node routes to the leader
        for (ClusterNode n : nodes) {
            int status = http.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + n.nodeId.port() + "/" + BUCKET))
                            .PUT(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status == 200 || status == 204) return;
        }
        fail("Could not create bucket on any node");
    }

    private int put(ClusterNode node, String key, byte[] value) throws Exception {
        return http.send(
                HttpRequest.newBuilder(uri(node, key))
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(value)).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private int getStatus(ClusterNode node, String key) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(node, key)).GET().build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    /**
     * Poll GET until 200 or timeout. Needed after follower restart while it
     * may still be replaying missed log entries.
     */
    private byte[] waitForKey(ClusterNode node, String key, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            HttpResponse<byte[]> resp = http.send(
                    HttpRequest.newBuilder(uri(node, key)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() == 200) return resp.body();
            Thread.sleep(100);
        }
        fail("Key '" + key + "' not accessible on " + node.nodeId.id()
                + " after " + timeoutMs + " ms");
        return null; // unreachable
    }

    /**
     * Check whether a SimpleStateMachineStorage snapshot file exists under the
     * Raft data directory. Ratis stores them at:
     * {@code raftDir/<groupId>/statemachine/snapshot.<term>_<index>}
     */
    private boolean snapshotExists(ClusterNode node) {
        try (var stream = Files.walk(node.raftDir)) {
            return stream.anyMatch(p -> p.getFileName().toString().startsWith("snapshot."));
        } catch (Exception e) {
            return false;
        }
    }

    private static List<NodeId> peers(int baseHttp, int baseRaft) {
        return List.of(
                new NodeId("rn-0", "localhost", baseHttp,     baseRaft),
                new NodeId("rn-1", "localhost", baseHttp + 1, baseRaft + 1),
                new NodeId("rn-2", "localhost", baseHttp + 2, baseRaft + 2));
    }

    private static URI uri(ClusterNode node, String key) {
        return URI.create("http://localhost:" + node.nodeId.port() + "/" + BUCKET + "/" + key);
    }

    // ── ClusterNode ───────────────────────────────────────────────────────

    /**
     * A cluster node that can be stopped and restarted from the same persistent
     * directories ({@link #dataDir} for the storage engine, {@link #raftDir}
     * for the Raft log + snapshot). Fields are re-created on each {@link #start}.
     */
    private static class ClusterNode {

        final NodeId nodeId;
        final Path   dataDir;
        final Path   raftDir;

        RatisCluster   cluster;
        StorageEngine  engine;
        Q1StateMachine sm;
        Q1Server       server;

        ClusterNode(NodeId nodeId, Path dataDir, Path raftDir) {
            this.nodeId  = nodeId;
            this.dataDir = dataDir;
            this.raftDir = raftDir;
        }

        void start(List<NodeId> peers) throws Exception {
            engine = new StorageEngine(dataDir, PARTITIONS);
            ClusterConfig cfg = ClusterConfig.builder()
                    .self(nodeId).peers(peers).numPartitions(PARTITIONS)
                    .raftDataDir(raftDir.toString())
                    .raftGroupId(GROUP_ID)
                    .build();
            sm      = new Q1StateMachine(engine);
            cluster = new RatisCluster(cfg, sm);
            cluster.start();
            server  = new Q1Server(engine, cluster, new PartitionRouter(cluster), nodeId.port());
            server.start();
        }

        /**
         * Stop the node. Delegates entirely to {@link Q1Server#close()} which
         * already closes the cluster and engine in the correct order
         * (HTTP → Raft → storage). Do NOT call cluster/engine.close() separately
         * or they will be closed a second time, throwing ClosedChannelException.
         */
        void stop() throws Exception {
            if (server != null) server.close();
            server  = null;
            cluster = null;
            engine  = null;
            sm      = null;
        }

        boolean isReady() {
            return cluster != null && cluster.isClusterReady();
        }
    }
}
