package io.q1.api;

import io.q1.api.handler.EcObjectHandler;
import io.q1.api.handler.ShardHandler;
import io.q1.cluster.EcConfig;
import io.q1.cluster.ErasureCoder;
import io.q1.cluster.RatisCluster;
import io.q1.cluster.HttpShardClient;
import io.q1.cluster.NodeId;
import io.q1.cluster.ShardPlacement;
import io.q1.core.StorageEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.SequencedSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Background EC shard repair scanner.
 *
 * <p>Iterates local shard keys partition by partition, verifies that each shard
 * of every EC object is present on its ring-assigned node, and reconstructs and
 * pushes any missing shards when at least {@code k} shards are available.
 *
 * <h3>Checkpoint</h3>
 * The last scanned internal shard key is persisted in each partition's RocksDB
 * under the reserved key {@code 0x00 rchk}.  On the next cycle the scan resumes
 * from that key; when the end of the shard space is reached the checkpoint is
 * reset to {@code null} and the cycle starts over.
 *
 * <h3>Scan cycle</h3>
 * One partition is processed per scheduled tick (configurable via
 * {@value #SCAN_INTERVAL_ENV} and {@value #BATCH_SIZE_ENV}).  This spreads the
 * I/O and network load evenly rather than scanning all partitions at once.
 */
public final class EcRepairScanner {

    private static final Logger log = LoggerFactory.getLogger(EcRepairScanner.class);

    static final String SCAN_INTERVAL_ENV = "Q1_REPAIR_INTERVAL_S";
    static final String BATCH_SIZE_ENV    = "Q1_REPAIR_BATCH_SIZE";

    private static final long SCAN_INTERVAL_S =
            Long.parseLong(System.getenv().getOrDefault(SCAN_INTERVAL_ENV, "60"));
    private static final int BATCH_SIZE =
            Integer.parseInt(System.getenv().getOrDefault(BATCH_SIZE_ENV, "200"));

    /** Internal shard key prefix used for the RocksDB scan. */
    private static final String SHARD_PREFIX =
            ShardHandler.SHARD_BUCKET + "\u0000"; // "__q1_ec_shards__\x00"

    private final StorageEngine   engine;
    private final RatisCluster     cluster;
    private final ErasureCoder    coder;
    private final HttpShardClient shardClient;
    private final EcConfig        ecConfig;
    private final int             numPartitions;

    /** Index of the partition to scan on the next tick (round-robin). */
    private int nextPartition = 0;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(
                    Thread.ofVirtual().name("ec-repair").factory());

    public EcRepairScanner(StorageEngine engine, RatisCluster cluster,
                           ErasureCoder coder, HttpShardClient shardClient) {
        this.engine        = engine;
        this.cluster       = cluster;
        this.coder         = coder;
        this.shardClient   = shardClient;
        this.ecConfig      = cluster.config().ecConfig();
        this.numPartitions = engine.numPartitions();
    }

    public void start() {
        scheduler.scheduleWithFixedDelay(
                this::scanNextPartition, SCAN_INTERVAL_S, SCAN_INTERVAL_S, TimeUnit.SECONDS);
        log.info("EC repair scanner started: interval={}s batch={} partitions={}",
                SCAN_INTERVAL_S, BATCH_SIZE, numPartitions);
    }

    public void stop() {
        scheduler.shutdownNow();
        try {
            scheduler.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ── partition scan ────────────────────────────────────────────────────

    private void scanNextPartition() {
        int partitionId = nextPartition;
        nextPartition = (nextPartition + 1) % numPartitions;
        try {
            scanPartition(partitionId);
        } catch (Exception e) {
            log.error("EC repair scan error in partition {}", partitionId, e);
        }
    }

    private void scanPartition(int partitionId) throws IOException {
        String checkpoint = engine.getRepairCheckpoint(partitionId);
        String fromKey    = checkpoint; // null → scan from start of SHARD_PREFIX

        List<String> keys = engine.scanKeysFrom(partitionId, fromKey, SHARD_PREFIX, BATCH_SIZE);

        if (keys.isEmpty()) {
            // End of shard space reached — reset for next full cycle
            if (checkpoint != null) {
                engine.setRepairCheckpoint(partitionId, null);
                log.debug("Partition {}: repair scan cycle complete, checkpoint reset", partitionId);
            }
            return;
        }

        // Deduplicate to unique (userBucket, userKey) objects within this batch
        SequencedSet<String> objects = new LinkedHashSet<>();
        for (String internalKey : keys) {
            String objectId = objectIdFromInternalKey(internalKey);
            if (objectId != null) objects.add(objectId);
        }

        int repaired = 0;
        for (String objectId : objects) {
            try {
                repaired += repairObject(objectId);
            } catch (Exception e) {
                log.warn("Repair failed for {}: {}", objectId, e.getMessage());
            }
        }

        // Save checkpoint: next scan starts after the last key in this batch
        String newCheckpoint = keys.getLast() + "\u0000";
        engine.setRepairCheckpoint(partitionId, newCheckpoint);

        if (repaired > 0) {
            log.info("Partition {}: repair scan pushed {} shard(s), checkpoint={}",
                    partitionId, repaired, keys.getLast());
        } else {
            log.debug("Partition {}: scanned {} object(s), none needed repair", partitionId, objects.size());
        }
    }

    // ── per-object repair ─────────────────────────────────────────────────

    /**
     * Checks all {@code k+m} shards of {@code objectId} ({@code "bucket/key"}) and
     * pushes any missing shards if at least {@code k} are available.
     *
     * @return number of shards pushed
     */
    private int repairObject(String objectId) throws IOException {
        int slash = objectId.indexOf('/');
        if (slash < 0) return 0;
        String userBucket = objectId.substring(0, slash);
        String userKey    = objectId.substring(slash + 1);

        ShardPlacement placement = cluster.computeShardPlacement(userBucket, userKey);
        int total   = ecConfig.totalShards();
        int k       = ecConfig.dataShards();

        // Check existence of each shard
        boolean[] present = new boolean[total];
        int presentCount  = 0;
        for (int i = 0; i < total; i++) {
            present[i] = shardPresent(placement.nodeForShard(i), i, userBucket, userKey);
            if (present[i]) presentCount++;
        }

        int missingCount = total - presentCount;
        if (missingCount == 0) return 0; // nothing to do

        if (presentCount < k) {
            log.warn("Cannot repair {}/{}: only {}/{} shards present (need {})",
                    userBucket, userKey, presentCount, total, k);
            return 0;
        }

        // Fetch payloads for all present shards
        byte[][] payloads = fetchPresentPayloads(placement, present, userBucket, userKey);

        // Extract original size from the first available payload
        long originalSize = -1;
        for (byte[] p : payloads) {
            if (p != null && p.length >= EcObjectHandler.HEADER_BYTES) {
                originalSize = EcObjectHandler.parseOriginalSize(p);
                break;
            }
        }
        if (originalSize < 0) return 0;

        // Strip headers; leave null for missing (EC decoder will reconstruct them)
        byte[][] shards = new byte[total][];
        for (int i = 0; i < total; i++) {
            if (payloads[i] != null && payloads[i].length >= EcObjectHandler.HEADER_BYTES) {
                shards[i] = EcObjectHandler.parseShardData(payloads[i]);
            }
        }

        // Decode → original bytes
        byte[] original;
        try {
            original = coder.decode(shards, originalSize);
        } catch (IOException e) {
            log.warn("Cannot decode {}/{} for repair: {}", userBucket, userKey, e.getMessage());
            return 0;
        }

        // Re-encode to produce all k+m shards
        byte[][] allShards = coder.encode(original);

        // Push missing shards to their assigned nodes
        int pushed = 0;
        for (int i = 0; i < total; i++) {
            if (!present[i]) {
                byte[] payload = EcObjectHandler.buildPayload(originalSize, allShards[i]);
                pushShard(placement.nodeForShard(i), i, userBucket, userKey, payload);
                pushed++;
            }
        }

        log.info("Repaired {}/{}: pushed {}/{} missing shards", userBucket, userKey, pushed, missingCount);
        return pushed;
    }

    // ── shard I/O helpers ─────────────────────────────────────────────────

    private boolean shardPresent(NodeId node, int idx, String bucket, String key) {
        try {
            if (node.equals(cluster.self())) {
                return engine.exists(ShardHandler.SHARD_BUCKET,
                        ShardHandler.shardKey(bucket, key, idx));
            }
            return shardClient.shardExists(node, idx, bucket, key);
        } catch (IOException e) {
            log.warn("Could not check shard {}/{}/{} on {}: {}", bucket, key, idx, node, e.getMessage());
            return false; // treat as absent; may trigger spurious repair attempts
        }
    }

    private byte[][] fetchPresentPayloads(ShardPlacement placement, boolean[] present,
                                          String bucket, String key) {
        int total    = placement.totalShards();
        byte[][] out = new byte[total][];
        for (int i = 0; i < total; i++) {
            if (!present[i]) continue;
            NodeId node = placement.nodeForShard(i);
            try {
                out[i] = fetchPayload(node, i, bucket, key);
            } catch (IOException e) {
                log.warn("Shard fetch failed for {}/{}/{} from {}: {}",
                        bucket, key, i, node, e.getMessage());
            }
        }
        return out;
    }

    private byte[] fetchPayload(NodeId node, int idx, String bucket, String key) throws IOException {
        if (node.equals(cluster.self())) {
            return engine.get(ShardHandler.SHARD_BUCKET, ShardHandler.shardKey(bucket, key, idx));
        }
        return shardClient.getShard(node, idx, bucket, key);
    }

    private void pushShard(NodeId node, int idx, String bucket, String key, byte[] payload) {
        try {
            if (node.equals(cluster.self())) {
                engine.put(ShardHandler.SHARD_BUCKET,
                        ShardHandler.shardKey(bucket, key, idx), payload);
            } else {
                shardClient.putShard(node, idx, bucket, key, payload);
            }
        } catch (IOException e) {
            log.warn("Failed to push repair shard {}/{}/{} to {}: {}",
                    bucket, key, idx, node, e.getMessage());
        }
    }

    // ── key parsing ───────────────────────────────────────────────────────

    /**
     * Extracts the object identity ({@code "userBucket/userKey"}) from an internal
     * shard key ({@code "__q1_ec_shards__\u0000userBucket/userKey/NN"}).
     * Returns {@code null} if the key is not a valid shard key.
     */
    static String objectIdFromInternalKey(String internalKey) {
        if (!internalKey.startsWith(SHARD_PREFIX)) return null;
        String shardObjectKey = internalKey.substring(SHARD_PREFIX.length());
        // shardObjectKey = "userBucket/userKey/NN" — last component is always 2-digit index
        int lastSlash = shardObjectKey.lastIndexOf('/');
        if (lastSlash < 0) return null;
        return shardObjectKey.substring(0, lastSlash); // "userBucket/userKey"
    }
}
