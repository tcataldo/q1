package io.q1.cluster;

import io.etcd.jetcd.*;
import io.etcd.jetcd.kv.TxnResponse;
import io.etcd.jetcd.op.Cmp;
import io.etcd.jetcd.op.CmpTarget;
import io.etcd.jetcd.op.Op;
import io.etcd.jetcd.options.GetOption;
import io.etcd.jetcd.options.PutOption;
import io.etcd.jetcd.options.WatchOption;
import io.etcd.jetcd.support.CloseableClient;
import io.etcd.jetcd.watch.WatchEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Manages cluster membership and per-partition leader election via etcd.
 *
 * <h3>etcd key layout</h3>
 * <pre>
 *   /q1/nodes/{nodeId}              →  "id:host:port"              (ephemeral, tied to lease)
 *   /q1/partitions/{id}/leader      →  "id:host:port"              (ephemeral, winner of election)
 *   /q1/partitions/{id}/replicas    →  "id:host:port,…"            (ephemeral, set by the leader)
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
 * <h3>Deterministic replica assignment</h3>
 * Immediately after winning leadership for a partition the leader computes a
 * stable list of RF-1 follower nodes — sorted by {@link NodeId#id()} and
 * taking the first {@code RF - 1} — and writes it to
 * {@code /q1/partitions/{id}/replicas} (also tied to its lease, so the key
 * disappears if the leader dies).
 *
 * <p>All nodes watch this key via a single prefix watch on
 * {@code /q1/partitions/} and keep a local {@code partitionReplicas} map
 * current.  This map is used by:
 * <ul>
 *   <li>{@link #followersFor(int)} — stable, deterministic target list for
 *       {@link HttpReplicator}</li>
 *   <li>{@link #isAssignedReplica(int)} — lets {@link CatchupManager} skip
 *       partitions this node was never assigned to</li>
 * </ul>
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
    private static final String KEY_REPLICAS   = "/replicas";

    private final ClusterConfig config;
    private final Client        client;

    /** partition id → current leader (updated by etcd watches) */
    private final ConcurrentHashMap<Integer, NodeId>       partitionLeaders  = new ConcurrentHashMap<>();

    /** partition id → stable RF-1 replica list (written by the leader, watched by all) */
    private final ConcurrentHashMap<Integer, List<NodeId>> partitionReplicas = new ConcurrentHashMap<>();

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
        keepAlive = leaseClient.keepAlive(leaseId,
                new io.grpc.stub.StreamObserver<io.etcd.jetcd.lease.LeaseKeepAliveResponse>() {
                    @Override public void onNext(io.etcd.jetcd.lease.LeaseKeepAliveResponse r) {}
                    @Override public void onError(Throwable t) { log.warn("Lease keepalive error", t); }
                    @Override public void onCompleted() {}
                });

        // 3. Register self as a live node
        registerSelf();

        // 4. Load current state (nodes, leaders, replica assignments)
        loadExistingNodes();
        loadExistingLeaders();
        loadExistingReplicas();

        // 5. Single prefix watch handles both leader and replica key changes
        watchNodes();
        watchPartitions();

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
     * Stable RF-1 replica list for this partition, as committed to etcd by the
     * current leader.  Falls back to a ring-based computation if the replicas key
     * is not yet known or was written before all nodes had registered (race at
     * startup — corrected by {@link #refreshReplicasForLeadPartitions()}).
     */
    public List<NodeId> followersFor(int partitionId) {
        List<NodeId> replicas = partitionReplicas.get(partitionId);
        // Non-null but empty means the leader wrote before other nodes registered.
        // Fall back to the ring computation until the refresh arrives.
        if (replicas != null && !replicas.isEmpty()) return replicas;
        return computeRingReplicas(partitionLeaders.get(partitionId));
    }

    /**
     * True if this node appears in the stable replica list for {@code partitionId}.
     * Returns {@code true} when the list is not yet known or is empty (safe default:
     * sync it rather than miss data).
     *
     * <p>Used by {@link CatchupManager} to skip partitions this node is not
     * responsible for, avoiding unnecessary full-partition syncs.
     */
    public boolean isAssignedReplica(int partitionId) {
        List<NodeId> replicas = partitionReplicas.get(partitionId);
        if (replicas == null || replicas.isEmpty()) return true; // not yet settled — safe default
        return replicas.stream().anyMatch(config.self()::equals);
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
                    writeReplicas(partitionId);
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
                .If(new Cmp(key, Cmp.Op.EQUAL, CmpTarget.version(0)))   // key must not exist
                .Then(Op.put(key, val, PutOption.builder().withLeaseId(leaseId).build()))
                .commit()
                .get(5, TimeUnit.SECONDS);

        if (txn.isSucceeded()) {
            partitionLeaders.put(partitionId, config.self());
        }
        return txn.isSucceeded();
    }

    /**
     * Computes the ring-based RF-1 replica list for {@code partitionId} and
     * writes it to {@code /q1/partitions/{id}/replicas}, tied to this node's
     * lease so it is automatically deleted if the leader dies.
     *
     * <p>All active nodes are sorted by {@link NodeId#id()} to form a ring.
     * The RF-1 replicas are the nodes immediately clockwise from the leader in
     * that ring (wrapping around).  This distributes replica load evenly: each
     * node is replica for approximately {@code PARTITIONS / N} partitions.
     */
    private void writeReplicas(int partitionId) throws Exception {
        List<NodeId> replicas = computeRingReplicas(config.self());

        String value = replicas.stream()
                .map(NodeId::toWire)
                .collect(Collectors.joining(","));

        client.getKVClient()
                .put(replicasKey(partitionId), bs(value),
                     PutOption.builder().withLeaseId(leaseId).build())
                .get(5, TimeUnit.SECONDS);

        partitionReplicas.put(partitionId, replicas);
        log.info("Partition {}: replicas → {}", partitionId,
                replicas.stream().map(NodeId::id).toList());
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

    // ── registration & watches ────────────────────────────────────────────

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
            String path = str(kv.getKey());
            if (!path.endsWith(KEY_LEADER)) return;
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

    private void loadExistingReplicas() throws Exception {
        var resp = client.getKVClient()
                .get(bs(KEY_PARTITIONS), GetOption.builder().isPrefix(true).build())
                .get(5, TimeUnit.SECONDS);
        resp.getKvs().forEach(kv -> {
            String path = str(kv.getKey());
            if (!path.endsWith(KEY_REPLICAS)) return;
            try {
                String[] parts = path.split("/");
                int partId = Integer.parseInt(parts[parts.length - 2]);
                List<NodeId> replicas = parseReplicas(str(kv.getValue()));
                partitionReplicas.put(partId, replicas);
            } catch (Exception e) {
                log.warn("Could not parse partition replicas key: {}", path, e);
            }
        });
        log.debug("Loaded replica assignments for {} partition(s)", partitionReplicas.size());
    }

    private void watchNodes() {
        client.getWatchClient().watch(
                bs(KEY_NODES),
                WatchOption.builder().isPrefix(true).build(),
                resp -> resp.getEvents().forEach(ev -> {
                    if (ev.getEventType() == WatchEvent.EventType.PUT) {
                        NodeId n = NodeId.fromWire(str(ev.getKeyValue().getValue()));
                        activeNodes.put(n.id(), n);
                        log.info("Node joined: {}", n);
                        // Rewrite replica assignments for any partition we lead, so the
                        // new node is included if it should be (fixes startup race where
                        // the leader wins election before other nodes have registered).
                        refreshReplicasForLeadPartitions();
                    } else if (ev.getEventType() == WatchEvent.EventType.DELETE) {
                        // For DELETE the value is empty — extract nodeId from the key path.
                        String keyPath = str(ev.getKeyValue().getKey());
                        String nodeId  = keyPath.substring(KEY_NODES.length());
                        activeNodes.remove(nodeId);
                        log.info("Node left: {}", nodeId);
                        // Rewrite replica assignments to exclude the departed node.
                        refreshReplicasForLeadPartitions();
                    }
                }));
    }

    /**
     * For every partition this node currently leads, recomputes and rewrites the
     * replica assignment in etcd.  Called when the active-node set changes (join
     * or leave) so the replica list stays consistent with actual cluster membership.
     */
    private void refreshReplicasForLeadPartitions() {
        for (int p = 0; p < config.numPartitions(); p++) {
            final int partitionId = p;
            if (isLocalLeader(partitionId)) {
                Thread.ofVirtual()
                        .name("q1-refresh-replicas-p" + partitionId)
                        .start(() -> {
                            try {
                                writeReplicas(partitionId);
                            } catch (Exception e) {
                                log.warn("Failed to refresh replicas for partition {}", partitionId, e);
                            }
                        });
            }
        }
    }

    /**
     * Single prefix watch on {@code /q1/partitions/} handling both leader and
     * replica key changes, dispatched by key suffix.
     */
    private void watchPartitions() {
        client.getWatchClient().watch(
                bs(KEY_PARTITIONS),
                WatchOption.builder().isPrefix(true).build(),
                resp -> resp.getEvents().forEach(ev -> {
                    String path = str(ev.getKeyValue().getKey());
                    try {
                        String[] parts = path.split("/");
                        int partId = Integer.parseInt(parts[parts.length - 2]);

                        if (path.endsWith(KEY_LEADER)) {
                            if (ev.getEventType() == WatchEvent.EventType.PUT) {
                                NodeId leader = NodeId.fromWire(str(ev.getKeyValue().getValue()));
                                partitionLeaders.put(partId, leader);
                                log.debug("Partition {} leader → {}", partId, leader);
                            } else if (ev.getEventType() == WatchEvent.EventType.DELETE) {
                                partitionLeaders.remove(partId);
                                log.debug("Partition {} leader cleared", partId);
                            }

                        } else if (path.endsWith(KEY_REPLICAS)) {
                            if (ev.getEventType() == WatchEvent.EventType.PUT) {
                                List<NodeId> replicas = parseReplicas(str(ev.getKeyValue().getValue()));
                                partitionReplicas.put(partId, replicas);
                                log.debug("Partition {} replicas → {}", partId,
                                        replicas.stream().map(NodeId::id).toList());
                            } else if (ev.getEventType() == WatchEvent.EventType.DELETE) {
                                partitionReplicas.remove(partId);
                                log.debug("Partition {} replicas cleared", partId);
                            }
                        }
                    } catch (Exception e) {
                        log.warn("Could not process partition watch event for key {}", path, e);
                    }
                }));
    }

    // ── helpers ───────────────────────────────────────────────────────────

    /**
     * Computes the RF-1 replicas for the given leader using a ring over all active
     * nodes sorted by {@link NodeId#id()}.  The replicas are the nodes immediately
     * clockwise from the leader in that ring, wrapping around.
     *
     * <p>Example with nodes [A, B, C] and RF=2:
     * <pre>
     *   A leads → replica = B
     *   B leads → replica = C
     *   C leads → replica = A  (wrap)
     * </pre>
     * Each node is replica for exactly the partitions led by its predecessor in
     * the ring, giving balanced storage across the cluster.
     */
    private List<NodeId> computeRingReplicas(NodeId leader) {
        if (leader == null) return List.of();
        List<NodeId> sorted = activeNodes.values().stream()
                .sorted(Comparator.comparing(NodeId::id))
                .toList();
        int n = sorted.size();
        int leaderPos = -1;
        for (int i = 0; i < n; i++) {
            if (sorted.get(i).equals(leader)) { leaderPos = i; break; }
        }
        if (leaderPos < 0) return List.of();
        int needed = Math.min(config.replicationFactor() - 1, n - 1);
        List<NodeId> replicas = new ArrayList<>(needed);
        for (int i = 1; i <= needed; i++) {
            replicas.add(sorted.get((leaderPos + i) % n));
        }
        return replicas;
    }

    private ByteSequence leaderKey(int partitionId) {
        return bs(KEY_PARTITIONS + partitionId + KEY_LEADER);
    }

    private ByteSequence replicasKey(int partitionId) {
        return bs(KEY_PARTITIONS + partitionId + KEY_REPLICAS);
    }

    private static List<NodeId> parseReplicas(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split(","))
                .filter(s -> !s.isBlank())
                .map(NodeId::fromWire)
                .toList();
    }

    private static ByteSequence bs(String s) {
        return ByteSequence.from(s, StandardCharsets.UTF_8);
    }

    private static String str(ByteSequence bs) {
        return bs.toString(StandardCharsets.UTF_8);
    }
}
