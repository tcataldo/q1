package io.q1.cluster;

import io.q1.core.StorageEngine;
import org.apache.ratis.client.RaftClient;
import org.apache.ratis.conf.Parameters;
import org.apache.ratis.conf.RaftProperties;
import org.apache.ratis.util.SizeInBytes;
import org.apache.ratis.grpc.GrpcFactory;
import org.apache.ratis.grpc.GrpcConfigKeys;
import org.apache.ratis.proto.RaftProtos;
import org.apache.ratis.protocol.ClientId;
import org.apache.ratis.protocol.GroupManagementRequest;
import org.apache.ratis.protocol.Message;
import org.apache.ratis.protocol.RaftClientReply;
import org.apache.ratis.protocol.RaftGroup;
import org.apache.ratis.protocol.RaftGroupId;
import org.apache.ratis.protocol.RaftPeer;
import org.apache.ratis.protocol.RaftPeerId;
import org.apache.ratis.server.RaftServer;
import org.apache.ratis.server.RaftServerConfigKeys;
import org.apache.ratis.server.storage.RaftStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages the embedded Apache Ratis node for Q1 cluster coordination.
 *
 * <h3>Design — per-partition Raft groups</h3>
 * Each of the P partitions has its own {@link RaftGroup}, whose RF members are
 * chosen deterministically by round-robin over the sorted peer list.  A single
 * {@link RaftServer} hosts all of the groups this node participates in, plus a
 * metadata group (RF=N) for bucket CREATE/DELETE operations.
 *
 * <p>Leadership is distributed: each partition group independently elects its
 * own leader, so writes are spread across all nodes rather than funnelled
 * through one global leader.
 *
 * <h3>Group UUID derivation</h3>
 * All group IDs are derived from a <em>namespace</em> UUID configured via
 * {@link ClusterConfig#raftGroupId()} (or {@code Q1_RAFT_GROUP_ID} env var):
 * <pre>
 *   partitionGroup(p)  = UUID(msb(namespace), p)
 *   metaGroup          = UUID(msb(namespace), -1L)   // all-1s LSB
 * </pre>
 * Using the same namespace across nodes guarantees they agree on group IDs
 * without any coordination.  Different test classes use different namespaces
 * to prevent background Ratis threads from interfering.
 *
 * <h3>Routing</h3>
 * Non-leader nodes proxy writes transparently via {@link PartitionRouter}
 * (see {@link io.q1.api.S3Router}).
 *
 * <h3>Membership</h3>
 * Peers are configured statically at startup. Dynamic membership change is
 * not supported in this version.
 */
public final class RatisCluster implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(RatisCluster.class);

    /**
     * Default namespace UUID.  All production nodes must share this value
     * (or an override from {@code Q1_RAFT_GROUP_ID}).  Different from the
     * old single-group UUID to avoid replaying an incompatible Raft log.
     */
    private static final String DEFAULT_NAMESPACE = "51310001-0001-0000-0000-000000000000";

    private final ClusterConfig config;
    private final RaftPeerId    selfId;
    private final UUID          namespace;   // base for deriving all group IDs
    private final RaftGroupId   metaGid;     // always present — bucket ops
    private final RaftGroupId[] partGids;    // indexed by partitionId
    private final RaftServer    server;

    /** State machines for every group this node participates in. */
    private final Map<RaftGroupId, Q1StateMachine> stateMachines = new HashMap<>();

    /** Raft clients for every group this node participates in. */
    private final Map<RaftGroupId, RaftClient> clients = new HashMap<>();

    /** Monotone counter for admin request IDs (group management). */
    private final AtomicLong adminCallId = new AtomicLong();

    public RatisCluster(ClusterConfig config, StorageEngine engine) throws IOException {
        this.config = config;
        this.selfId = RaftPeerId.valueOf(config.self().id());

        // ── namespace & group ID derivation ───────────────────────────────
        String nsStr = config.raftGroupId() != null
                ? config.raftGroupId()
                : System.getenv().getOrDefault("Q1_RAFT_GROUP_ID", DEFAULT_NAMESPACE);
        this.namespace = UUID.fromString(nsStr);
        long nsMsb = namespace.getMostSignificantBits();

        this.metaGid  = RaftGroupId.valueOf(new UUID(nsMsb, -1L));
        this.partGids = new RaftGroupId[config.numPartitions()];
        for (int p = 0; p < config.numPartitions(); p++) {
            partGids[p] = RaftGroupId.valueOf(new UUID(nsMsb, (long) p));
        }

        // ── Raft properties (shared across all groups) ────────────────────
        RaftProperties props = buildProperties();

        Files.createDirectories(Path.of(config.raftDataDir()));

        // ── build server with per-group state machine registry ────────────
        buildStateMachinesAndGroups(engine, props);

        this.server = RaftServer.newBuilder()
                .setServerId(selfId)
                .setStateMachineRegistry(stateMachines::get)
                .setProperties(props)
                .setOption(RaftStorage.StartupOption.RECOVER)
                .build();
    }

    // ── lifecycle ─────────────────────────────────────────────────────────

    public void start() throws IOException {
        server.start();

        // Collect groups already recovered from disk (RECOVER startup option).
        // For those, Ratis has already associated our state machine (from the
        // registry) and started the group — we must NOT call groupManagement
        // again or Ratis will reject the duplicate with AlreadyExists.
        java.util.Set<RaftGroupId> alreadyLoaded = new java.util.HashSet<>();
        for (RaftGroupId gid : server.getGroupIds()) {
            alreadyLoaded.add(gid);
        }

        // Add groups that were not recovered from disk (fresh start or new groups).
        ClientId adminId = ClientId.randomId();
        int added = 0;
        for (RaftGroupId gid : stateMachines.keySet()) {
            if (alreadyLoaded.contains(gid)) continue;
            RaftGroup grp = buildGroup(gid);
            GroupManagementRequest req = GroupManagementRequest.newAdd(
                    adminId, selfId, adminCallId.getAndIncrement(), grp);
            RaftClientReply reply = server.groupManagement(req);
            if (!reply.isSuccess()) {
                log.warn("groupManagement add {} returned: {}", gid, reply.getException());
            }
            added++;
        }

        log.info("Ratis node {} started ({} groups, {} new, raftPort={})",
                config.self().id(), stateMachines.size(), added, config.self().raftPort());
    }

    @Override
    public void close() {
        for (RaftClient c : clients.values()) {
            try { c.close(); } catch (IOException e) { log.warn("Error closing RaftClient", e); }
        }
        try { server.close(); } catch (IOException e) { log.warn("Error closing RaftServer", e); }
        log.info("Ratis node {} stopped", config.self().id());
    }

    // ── routing API ───────────────────────────────────────────────────────

    /**
     * True if this node is the Raft leader for the <em>metadata</em> group.
     * Used for cluster-wide readiness checks and bucket-op routing.
     */
    public boolean isLocalLeader() {
        return isLocalLeaderOf(metaGid);
    }

    /** True if this node is the Raft leader for the given partition's group. */
    public boolean isLocalLeader(int partitionId) {
        return isLocalReplica(partitionId) && isLocalLeaderOf(partGids[partitionId]);
    }

    /**
     * True if this node is a configured replica for the given partition
     * (i.e. it is a member of that partition's Raft group).
     */
    public boolean isLocalReplica(int partitionId) {
        return clients.containsKey(partGids[partitionId]);
    }

    /**
     * HTTP base URL of the metadata-group leader, or {@link Optional#empty()}
     * when this node is the meta leader or leadership is unknown.
     *
     * <p>Used for bucket-op routing and as a cluster-level readiness signal.
     */
    public Optional<String> leaderHttpBaseUrl() {
        if (isLocalLeader()) return Optional.empty();
        return leaderIdFromDivision(metaGid)
                .flatMap(id -> config.peers().stream()
                        .filter(n -> n.id().equals(id))
                        .findFirst()
                        .map(NodeId::httpBase));
    }

    /**
     * HTTP base URL of the leader for the given partition's Raft group, or
     * {@link Optional#empty()} when this node is already the leader.
     *
     * <p>When this node is not a replica for the partition, returns the URL of
     * the first configured replica — that node handles further routing.
     */
    public Optional<String> leaderHttpBaseUrl(int partitionId) {
        if (!isLocalReplica(partitionId)) {
            // Not in this partition's group — proxy to first replica.
            return Optional.of(config.replicas(partitionId).get(0).httpBase());
        }
        if (isLocalLeader(partitionId)) return Optional.empty();
        return leaderIdFromDivision(partGids[partitionId])
                .flatMap(id -> config.peers().stream()
                        .filter(n -> n.id().equals(id))
                        .findFirst()
                        .map(NodeId::httpBase));
    }

    /**
     * Submit a command to the appropriate Raft group and block until committed.
     *
     * <ul>
     *   <li>PUT / DELETE → partition group for {@code cmd.bucket()+cmd.key()}</li>
     *   <li>CREATE_BUCKET / DELETE_BUCKET → metadata group (RF=N)</li>
     * </ul>
     *
     * @throws IOException if the commit fails or this node has no client for the target group
     */
    public void submit(RatisCommand cmd) throws IOException {
        RaftClient client = clientForCommand(cmd);
        RaftClientReply reply = client.io().send(cmd.toMessage());
        if (!reply.isSuccess()) {
            throw new IOException("Raft commit failed for " + cmd.type()
                    + ": " + reply.getException());
        }
    }

    /**
     * Asynchronous variant of {@link #submit}.  Returns a future that
     * completes once the entry is committed by a quorum.
     */
    public CompletableFuture<Void> submitAsync(RatisCommand cmd) {
        RaftClient client;
        try { client = clientForCommand(cmd); }
        catch (IOException e) { return CompletableFuture.failedFuture(e); }
        return client.async().send(cmd.toMessage())
                .thenAccept(reply -> {
                    if (!reply.isSuccess()) {
                        throw new RuntimeException("Raft async commit failed for " + cmd.type()
                                + ": " + reply.getException());
                    }
                });
    }

    /**
     * All configured peers — used by the EC placement ring and cluster
     * readiness checks.
     */
    public Collection<NodeId> activeNodes() {
        return config.peers();
    }

    /**
     * True when all Raft groups managed by this node have a known leader.
     * In EC mode, also checks that enough data nodes are available.
     *
     * <p>Waiting for all groups (not just the meta group) ensures that the
     * cluster is fully operational before accepting requests: it avoids a race
     * where a write is submitted to a partition group whose leader has not yet
     * been identified, causing the client to see the write as a phantom (not
     * yet applied on this node).
     */
    public boolean isClusterReady() {
        EcConfig ec = config.ecConfig();
        if (ec.enabled()) {
            return config.peers().size() >= ec.dataShards();
        }
        for (RaftGroupId gid : stateMachines.keySet()) {
            if (!isLocalLeaderOf(gid) && leaderIdFromDivision(gid).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * ID of the current metadata-group leader, or {@link Optional#empty()}
     * when leadership is unknown.
     */
    public Optional<String> leaderId() {
        if (isLocalLeader()) return Optional.of(config.self().id());
        return leaderIdFromDivision(metaGid);
    }

    /**
     * ID of the leader for the given partition's Raft group, or
     * {@link Optional#empty()} when leadership is unknown.
     */
    public Optional<String> leaderId(int partitionId) {
        if (isLocalLeader(partitionId)) return Optional.of(config.self().id());
        return leaderIdFromDivision(partGids[partitionId]);
    }

    /**
     * Force a snapshot on all groups this node manages (for testing).
     * Returns the snapshot index of the metadata group.
     */
    public long takeSnapshot() throws IOException {
        long lastIdx = -1L;
        for (Q1StateMachine sm : stateMachines.values()) {
            long idx = sm.takeSnapshot();
            if (idx > lastIdx) lastIdx = idx;
        }
        return lastIdx;
    }

    public NodeId self()            { return config.self(); }
    public ClusterConfig config()   { return config; }

    /**
     * Deterministically selects {@code k+m} nodes for the given object key
     * (EC mode).
     */
    public ShardPlacement computeShardPlacement(String bucket, String key) {
        EcConfig ec = config.ecConfig();
        if (!ec.enabled()) throw new IllegalStateException("EC is not enabled");

        List<NodeId> sorted = config.peers().stream()
                .sorted(Comparator.comparing(NodeId::id))
                .toList();
        int n = sorted.size();
        if (n < ec.totalShards()) {
            throw new IllegalStateException(
                    "Need " + ec.totalShards() + " nodes for EC("
                    + ec.dataShards() + "+" + ec.parityShards()
                    + "), but only " + n + " configured");
        }
        int anchor = Math.abs((bucket + '\u0000' + key).hashCode()) % n;
        List<NodeId> selected = new java.util.ArrayList<>(ec.totalShards());
        for (int i = 0; i < ec.totalShards(); i++) {
            selected.add(sorted.get((anchor + i) % n));
        }
        return new ShardPlacement(selected);
    }

    // ── private helpers ───────────────────────────────────────────────────

    /**
     * Builds the Raft properties shared across all groups on this node.
     * The gRPC port is set once; all groups reuse it.
     */
    private RaftProperties buildProperties() {
        RaftProperties props = new RaftProperties();
        GrpcConfigKeys.Server.setPort(props, config.self().raftPort());
        RaftServerConfigKeys.setStorageDir(props, List.of(new File(config.raftDataDir())));

        SizeInBytes maxEntry = SizeInBytes.valueOf("64MB");
        // gRPC transport limit — must fit the largest single log entry.
        GrpcConfigKeys.setMessageSizeMax(props, maxEntry);
        // appenderBufferByteLimit is a HARD per-entry limit in RaftLogBase.appendImpl:
        // any log entry larger than this is rejected with RaftLogIOException.
        int         maxObjMb      = config.maxObjectSizeMb();
        SizeInBytes appenderLimit = SizeInBytes.valueOf(maxObjMb + "MB");
        RaftServerConfigKeys.Log.Appender.setBufferByteLimit(props, appenderLimit);
        // writeBufferSize = DirectByteBuffer allocated per Raft group at startup.
        // Ratis enforces: writeBufferSize >= appenderBufferByteLimit + 8 bytes.
        // Use maxObjectSizeMb+1 MB — just above the threshold, minimising per-group cost.
        // Total direct memory = max_groups_per_node × (maxObjectSizeMb+1) MB.
        // max_groups_per_node = ceil(rf × partitions / N) + 1 (meta).
        // See q1.service.j2 for the JVM flag derivation.
        RaftServerConfigKeys.Log.setWriteBufferSize(props, SizeInBytes.valueOf((maxObjMb + 1) + "MB"));
        RaftServerConfigKeys.Log.setSegmentSizeMax(props, SizeInBytes.valueOf("256MB"));

        RaftServerConfigKeys.Snapshot.setAutoTriggerEnabled(props, true);
        RaftServerConfigKeys.Snapshot.setAutoTriggerThreshold(props, 10_000L);
        return props;
    }

    /**
     * Creates a {@link Q1StateMachine} and a {@link RaftClient} for every
     * group this node participates in, and registers them in
     * {@link #stateMachines} / {@link #clients}.
     */
    private void buildStateMachinesAndGroups(StorageEngine engine, RaftProperties props) {
        // Metadata group — all nodes always participate.
        RaftGroup metaGroup = buildGroup(metaGid);
        stateMachines.put(metaGid, new Q1StateMachine(engine));
        clients.put(metaGid, buildClient(props, metaGroup));

        // Partition groups — only groups where this node is a configured replica.
        for (int p = 0; p < config.numPartitions(); p++) {
            List<NodeId> replicas = config.replicas(p);
            boolean isReplica = replicas.stream()
                    .anyMatch(n -> n.id().equals(config.self().id()));
            if (!isReplica) continue;

            RaftGroup group = buildGroup(partGids[p], replicas);
            stateMachines.put(partGids[p], new Q1StateMachine(engine));
            clients.put(partGids[p], buildClient(props, group));
        }
    }

    /**
     * Builds the {@link RaftGroup} for {@code gid} using all configured peers
     * (used for the metadata group and for reconstructing the group at
     * {@link #start()} time).
     */
    private RaftGroup buildGroup(RaftGroupId gid) {
        // For the meta group we include all peers; for partition groups
        // we need to know the replica set.  Look it up from partGids.
        for (int p = 0; p < partGids.length; p++) {
            if (partGids[p].equals(gid)) {
                return buildGroup(gid, config.replicas(p));
            }
        }
        // Must be the meta group — include all peers.
        return buildGroup(gid, config.peers());
    }

    private RaftGroup buildGroup(RaftGroupId gid, List<NodeId> members) {
        List<RaftPeer> raftPeers = members.stream()
                .map(n -> RaftPeer.newBuilder()
                        .setId(n.id())
                        .setAddress(n.raftAddress())
                        .build())
                .toList();
        return RaftGroup.valueOf(gid, raftPeers);
    }

    private RaftClient buildClient(RaftProperties props, RaftGroup group) {
        return RaftClient.newBuilder()
                .setProperties(props)
                .setRaftGroup(group)
                .setClientId(ClientId.randomId())
                .build();
    }

    private boolean isLocalLeaderOf(RaftGroupId gid) {
        try {
            return server.getDivision(gid).getInfo().isLeader();
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Returns the current leader's peer ID string for the given Raft group,
     * read from the server's own role state (reliable after election).
     */
    private Optional<String> leaderIdFromDivision(RaftGroupId gid) {
        try {
            RaftProtos.RoleInfoProto roleInfo =
                    server.getDivision(gid).getInfo().getRoleInfoProto();
            if (!roleInfo.hasFollowerInfo()) return Optional.empty();
            RaftProtos.ServerRpcProto leaderRpc = roleInfo.getFollowerInfo().getLeaderInfo();
            if (!leaderRpc.hasId()) return Optional.empty();
            String id = leaderRpc.getId().getId().toStringUtf8();
            return id.isEmpty() ? Optional.empty() : Optional.of(id);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Routes a {@link RatisCommand} to the correct group's {@link RaftClient}. */
    private RaftClient clientForCommand(RatisCommand cmd) throws IOException {
        return switch (cmd.type()) {
            case CREATE_BUCKET, DELETE_BUCKET -> clients.get(metaGid);
            case PUT, DELETE -> {
                int p = partitionFor(cmd.bucket(), cmd.key());
                RaftClient c = clients.get(partGids[p]);
                if (c == null) {
                    throw new IOException("Not a replica for partition " + p
                            + " — command should have been proxied");
                }
                yield c;
            }
        };
    }

    private int partitionFor(String bucket, String key) {
        String fullKey = bucket + '\u0000' + key;
        return Math.abs(fullKey.hashCode()) % config.numPartitions();
    }
}
