package io.q1.cluster;

import io.q1.core.StorageEngine;
import io.q1.core.SyncState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;

/**
 * Runs at node startup to bring lagging partitions up to date with their
 * current leader before the node starts serving requests.
 *
 * <h3>Algorithm (per partition)</h3>
 * <ol>
 *   <li>Skip partitions where this node is already the leader.</li>
 *   <li>Read the local {@link SyncState}: which segment and byte offset does
 *       this node have?</li>
 *   <li>Call the leader's sync endpoint:
 *       {@code GET /internal/v1/sync/{partitionId}?segment={s}&offset={o}}</li>
 *   <li>204 → already current, nothing to do.</li>
 *   <li>200 → stream of segment records; apply each one via
 *       {@link StorageEngine#applySyncStream}.</li>
 * </ol>
 *
 * <h3>Failure handling</h3>
 * A failed catchup for a partition is logged and skipped — the node will still
 * start, but reads for that partition may be stale.  The live-replication path
 * ({@link HttpReplicator}) will keep it current going forward.
 *
 * <p>All HTTP calls use the JDK {@link HttpClient} with virtual threads.
 */
public final class CatchupManager {

    private static final Logger log = LoggerFactory.getLogger(CatchupManager.class);

    /** How long to wait for election results before starting catchup. */
    private static final Duration ELECTION_SETTLE  = Duration.ofSeconds(3);
    private static final Duration REQUEST_TIMEOUT  = Duration.ofSeconds(30);

    private final EtcdCluster cluster;
    private final HttpClient  http;

    public CatchupManager(EtcdCluster cluster) {
        this.cluster = cluster;
        this.http = HttpClient.newBuilder()
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    /**
     * Waits for elections to settle, then syncs every non-leader partition.
     * Blocks until all catchups have completed (or failed).
     *
     * <p>Should be called before {@link io.q1.api.Q1Server#start()} so the
     * node does not serve stale reads.
     */
    public void catchUp(StorageEngine engine) {
        waitForElections();

        // Release surplus leaderships so that no node holds more than ⌈P/N⌉
        // partitions.  Then wait again for any released partitions to be
        // re-elected by another node before we start syncing.
        cluster.rebalance();
        waitForElections();

        int numPartitions = cluster.config().numPartitions();
        int synced   = 0;
        int current  = 0;
        int skipped  = 0;

        for (int p = 0; p < numPartitions; p++) {
            if (cluster.isLocalLeader(p)) {
                skipped++;
                continue;
            }

            if (!cluster.isAssignedReplica(p)) {
                log.debug("Partition {}: not in replica assignment, skipping catchup", p);
                skipped++;
                continue;
            }

            var leaderOpt = cluster.leaderFor(p);
            if (leaderOpt.isEmpty()) {
                log.warn("Partition {}: no leader elected yet, skipping catchup", p);
                skipped++;
                continue;
            }

            NodeId leader = leaderOpt.get();

            try {
                boolean needed = syncPartition(engine, p, leader);
                if (needed) synced++; else current++;
            } catch (Exception e) {
                log.warn("Partition {}: catchup from {} failed — node may serve stale data", p, leader, e);
                skipped++;
            }
        }

        log.info("Catchup complete: {} synced, {} already current, {} skipped",
                synced, current, skipped);
    }

    // ── private ───────────────────────────────────────────────────────────

    /**
     * @return true if data was received and applied, false if already current
     */
    private boolean syncPartition(StorageEngine engine, int partitionId, NodeId leader)
            throws IOException, InterruptedException {

        SyncState local = engine.partitionSyncState(partitionId);

        URI uri = URI.create(leader.httpBase()
                + "/internal/v1/sync/" + partitionId
                + "?segment=" + local.segmentId()
                + "&offset="  + local.byteOffset());

        log.debug("Partition {}: requesting sync from {} (local=seg{}@{})",
                partitionId, leader, local.segmentId(), local.byteOffset());

        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();

        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());

        return switch (resp.statusCode()) {
            case 200 -> {
                try (InputStream body = resp.body()) {
                    engine.applySyncStream(partitionId, body);
                }
                SyncState after = engine.partitionSyncState(partitionId);
                log.info("Partition {}: synced from {} (now seg{}@{})",
                        partitionId, leader, after.segmentId(), after.byteOffset());
                yield true;
            }
            case 204 -> {
                log.debug("Partition {}: already current", partitionId);
                yield false;
            }
            default -> throw new IOException(
                    "Sync request for partition " + partitionId +
                    " returned HTTP " + resp.statusCode());
        };
    }

    /**
     * Blocks until all partitions have a known leader (elections settled) or
     * {@link #ELECTION_SETTLE} has elapsed.
     */
    private void waitForElections() {
        int  numPartitions = cluster.config().numPartitions();
        long deadline      = System.currentTimeMillis() + ELECTION_SETTLE.toMillis();

        log.info("Waiting up to {}s for leader elections to settle…", ELECTION_SETTLE.toSeconds());

        while (System.currentTimeMillis() < deadline) {
            long unelected = 0;
            for (int p = 0; p < numPartitions; p++) {
                if (cluster.leaderFor(p).isEmpty()) unelected++;
            }
            if (unelected == 0) break;
            try { Thread.sleep(200); } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); return;
            }
        }

        // Log final state
        long elected = 0;
        for (int p = 0; p < numPartitions; p++) {
            if (cluster.leaderFor(p).isPresent()) elected++;
        }
        log.info("Elections settled: {}/{} partitions have a leader", elected, numPartitions);
    }
}
