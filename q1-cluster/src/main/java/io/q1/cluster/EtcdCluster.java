package io.q1.cluster;

import io.etcd.jetcd.*;
import io.etcd.jetcd.kv.TxnResponse;
import io.etcd.jetcd.op.Cmp;
import io.etcd.jetcd.op.CmpTarget;
import io.etcd.jetcd.op.Op;
import io.etcd.jetcd.options.GetOption;
import io.etcd.jetcd.options.PutOption;
import io.etcd.jetcd.options.WatchOption;
import io.etcd.jetcd.watch.WatchEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages cluster membership and per-partition leader election via etcd.
 *
 * <h3>etcd key layout</h3>
 * <pre>
 *   /q1/nodes/{nodeId}           →  "id:host:port"    (ephemeral, tied to lease)
 *   /q1/partitions/{id}/leader   →  "id:host:port"    (ephemeral, winner of election)
 * </pre>
 *
 * <h3>Leader election</h3>
 * Each partition runs an independent election.  A node acquires leadership by
 * writing its {@link NodeId} under the partition key <em>only if the key does
 * not yet exist</em> (etcd conditional transaction).  The key is tied to the
 * node's lease: when the lease expires (keepalive stops), the key is
 * automatically deleted and another node can win.
 *
 * <p>This prevents split-brain: at most one node holds the lease at any time.
 *
 * <h3>Threading</h3>
 * All etcd futures are blocked on virtual threads — blocking is cheap there.
 * Watch callbacks run on jetcd's internal executor.
 */
public final class EtcdCluster implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(EtcdCluster.class);

    private static final String KEY_NODES      = "/q1/nodes/";
    private static final String KEY_PARTITIONS = "/q1/partitions/";
    private static final String KEY_LEADER     = "/leader";

    private final ClusterConfig config;
    private final Client        client;

    /** partition id → current leader (updated by etcd watches) */
    private final ConcurrentHashMap<Integer, NodeId> partitionLeaders = new ConcurrentHashMap<>();

    /** node id → NodeId for every live node (updated by etcd watches) */
    private final ConcurrentHashMap<String, NodeId> activeNodes = new ConcurrentHashMap<>();

    private volatile long         leaseId;
    private          CloseableClient keepAlive;
    private final    AtomicBoolean   running = new AtomicBoolean(false);

    public EtcdCluster(ClusterConfig config) {
        this.config = config;
        this.client = Client.builder()
                .endpoints(config.etcdEndpoints().toArray(new String[0]))
                .build();
    }

    // ── lifecycle ─────────────────────────────────────────────────────────

    /**
     * Connect to etcd, register this node, load existing cluster state,
     * start keepalive and watches, then campaign for partition leadership.
     */
    public void start() throws Exception {
        running.set(true);

        Lease leaseClient = client.getLeaseClient();

        // 1. Grant a lease that acts as our "heartbeat"
        leaseId = leaseClient.grant(config.leaseTtlSeconds()).get().getID();
        log.info("Etcd lease granted: {} (ttl={}s)", leaseId, config.leaseTtlSeconds());

        // 2. Keep it alive in the background
        keepAlive = leaseClient.keepAlive(leaseId, new io.etcd.jetcd.support.CloseableClient() {
            @Override public void close() {}
        });

        // 3. Register self as a live node
        registerSelf();

        // 4. Load current state (nodes + partition leaders already elected)
        loadExistingNodes();
        loadExistingLeaders();

        // 5. Watch for node join/leave and leadership changes
        watchNodes();
        watchPartitionLeaders();

        // 6. Campaign for every partition in parallel on virtual threads
        for (int p = 0; p < config.numPartitions(); p++) {
            final int partitionId = p;
            Thread.ofVirtual()
                    .name("q1-elect-p" + partitionId)
                    .start(() -> electionLoop(partitionId));
        }

        log.info("Cluster node {} started, campaigning for {} partitions",
                config.self(), config.numPartitions());
    }

    @Override
    public void close() {
        running.set(false);
        if (keepAlive != null) keepAlive.close();
        client.close();
        log.info("Cluster node {} disconnected", config.self());
    }

    // ── routing API (called on every request) ─────────────────────────────

    /** The node currently leading this partition (may be null during election). */
    public Optional<NodeId> leaderFor(int partitionId) {
        return Optional.ofNullable(partitionLeaders.get(partitionId));
    }

    /** True if this node is the current leader for the given partition. */
    public boolean isLocalLeader(int partitionId) {
        return config.self().equals(partitionLeaders.get(partitionId));
    }

    /**
     * All active nodes that are NOT the leader for this partition.
     * Used by the replicator to fan-out writes.
     */
    public List<NodeId> followersFor(int partitionId) {
        NodeId leader = partitionLeaders.get(partitionId);
        return activeNodes.values().stream()
                .filter(n -> !n.equals(leader))
                .toList();
    }

    /** Snapshot of all currently active nodes (including self). */
    public Collection<NodeId> activeNodes() {
        return List.copyOf(activeNodes.values());
    }

    public NodeId self() { return config.self(); }

    public ClusterConfig config() { return config; }

    // ── election ──────────────────────────────────────────────────────────

    /**
     * Runs until {@link #running} is false.  Tries to acquire leadership for
     * {@code partitionId}; if another node already owns it, watches for the
     * key to disappear and retries.
     */
    private void electionLoop(int partitionId) {
        while (running.get()) {
            try {
                if (tryAcquireLeadership(partitionId)) {
                    log.info("Won leadership for partition {}", partitionId);
                    // Hold until the lease dies or we shut down
                    waitForLeadershipLoss(partitionId);
                    if (running.get()) {
                        log.info("Lost leadership for partition {}, re-campaigning", partitionId);
                    }
                } else {
                    // Someone else is leader — wait until the key disappears
                    waitUntilLeaderAbsent(partitionId);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("Election error for partition {}, retrying in 2s", partitionId, e);
                try { Thread.sleep(2_000); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt(); return;
                }
            }
        }
    }

    /**
     * Conditional PUT: write our NodeId only if the key doesn't exist yet.
     *
     * @return true if we won (transaction succeeded)
     */
    private boolean tryAcquireLeadership(int partitionId) throws Exception {
        ByteSequence key = leaderKey(partitionId);
        ByteSequence val = bs(config.self().toWire());

        TxnResponse txn = client.getKVClient().txn()
                .If(new Cmp(key, Cmp.Op.EQUAL, CmpTarget.VERSION, 0))  // key must not exist
                .Then(Op.put(key, val, PutOption.builder().withLeaseId(leaseId).build()))
                .commit()
                .get(5, TimeUnit.SECONDS);

        if (txn.isSucceeded()) {
            partitionLeaders.put(partitionId, config.self());
        }
        return txn.isSucceeded();
    }

    /** Block until the leader key for this partition is deleted. */
    private void waitUntilLeaderAbsent(int partitionId) throws InterruptedException {
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        ByteSequence key = leaderKey(partitionId);

        try (Watch.Watcher w = client.getWatchClient().watch(key, WatchOption.DEFAULT, resp ->
                resp.getEvents().forEach(ev -> {
                    if (ev.getEventType() == WatchEvent.EventType.DELETE) latch.countDown();
                }))) {
            // Re-check: leader might have disappeared between our failed txn and watch setup
            try {
                var resp = client.getKVClient().get(key).get(3, TimeUnit.SECONDS);
                if (resp.getKvs().isEmpty()) latch.countDown();
            } catch (Exception ignored) {}

            latch.await();
        }
    }

    /** Block until we lose our own leadership key (lease expiry or shutdown). */
    private void waitForLeadershipLoss(int partitionId) throws InterruptedException {
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        ByteSequence key = leaderKey(partitionId);

        try (Watch.Watcher w = client.getWatchClient().watch(key, WatchOption.DEFAULT, resp ->
                resp.getEvents().forEach(ev -> {
                    if (ev.getEventType() == WatchEvent.EventType.DELETE) {
                        partitionLeaders.remove(partitionId);
                        latch.countDown();
                    }
                }))) {
            latch.await();
        }
    }

    // ── registration & watch ──────────────────────────────────────────────

    private void registerSelf() throws Exception {
        ByteSequence key = bs(KEY_NODES + config.self().id());
        ByteSequence val = bs(config.self().toWire());
        client.getKVClient()
                .put(key, val, PutOption.builder().withLeaseId(leaseId).build())
                .get(5, TimeUnit.SECONDS);
        activeNodes.put(config.self().id(), config.self());
        log.debug("Registered self in etcd: {}", config.self());
    }

    private void loadExistingNodes() throws Exception {
        var resp = client.getKVClient()
                .get(bs(KEY_NODES), GetOption.builder().isPrefix(true).build())
                .get(5, TimeUnit.SECONDS);
        resp.getKvs().forEach(kv -> {
            NodeId n = NodeId.fromWire(str(kv.getValue()));
            activeNodes.put(n.id(), n);
        });
        log.debug("Loaded {} existing node(s)", activeNodes.size());
    }

    private void loadExistingLeaders() throws Exception {
        var resp = client.getKVClient()
                .get(bs(KEY_PARTITIONS), GetOption.builder().isPrefix(true).build())
                .get(5, TimeUnit.SECONDS);
        resp.getKvs().forEach(kv -> {
            String path = str(kv.getKey());  // e.g. /q1/partitions/5/leader
            try {
                String[] parts = path.split("/");
                int partId = Integer.parseInt(parts[parts.length - 2]);
                NodeId leader = NodeId.fromWire(str(kv.getValue()));
                partitionLeaders.put(partId, leader);
            } catch (Exception e) {
                log.warn("Could not parse partition leader key: {}", path, e);
            }
        });
        log.debug("Loaded leaders for {} partition(s)", partitionLeaders.size());
    }

    private void watchNodes() {
        client.getWatchClient().watch(
                bs(KEY_NODES),
                WatchOption.builder().isPrefix(true).build(),
                resp -> resp.getEvents().forEach(ev -> {
                    NodeId n = NodeId.fromWire(str(ev.getKeyValue().getValue()));
                    if (ev.getEventType() == WatchEvent.EventType.PUT) {
                        activeNodes.put(n.id(), n);
                        log.info("Node joined: {}", n);
                    } else if (ev.getEventType() == WatchEvent.EventType.DELETE) {
                        activeNodes.remove(n.id());
                        log.info("Node left: {}", n);
                    }
                }));
    }

    private void watchPartitionLeaders() {
        client.getWatchClient().watch(
                bs(KEY_PARTITIONS),
                WatchOption.builder().isPrefix(true).build(),
                resp -> resp.getEvents().forEach(ev -> {
                    String path = str(ev.getKeyValue().getKey());
                    try {
                        String[] parts = path.split("/");
                        int partId = Integer.parseInt(parts[parts.length - 2]);
                        if (ev.getEventType() == WatchEvent.EventType.PUT) {
                            NodeId leader = NodeId.fromWire(str(ev.getKeyValue().getValue()));
                            partitionLeaders.put(partId, leader);
                            log.debug("Partition {} leader → {}", partId, leader);
                        } else if (ev.getEventType() == WatchEvent.EventType.DELETE) {
                            partitionLeaders.remove(partId);
                            log.debug("Partition {} leader cleared", partId);
                        }
                    } catch (Exception e) {
                        log.warn("Could not process partition watch event for key {}", path, e);
                    }
                }));
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private ByteSequence leaderKey(int partitionId) {
        return bs(KEY_PARTITIONS + partitionId + KEY_LEADER);
    }

    private static ByteSequence bs(String s) {
        return ByteSequence.from(s, StandardCharsets.UTF_8);
    }

    private static String str(ByteSequence bs) {
        return bs.toString(StandardCharsets.UTF_8);
    }
}
