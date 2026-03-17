package io.q1.cluster;

import org.apache.ratis.client.RaftClient;
import org.apache.ratis.conf.Parameters;
import org.apache.ratis.conf.RaftProperties;
import org.apache.ratis.util.SizeInBytes;
import org.apache.ratis.grpc.GrpcFactory;
import org.apache.ratis.grpc.GrpcConfigKeys;
import org.apache.ratis.proto.RaftProtos;
import org.apache.ratis.protocol.ClientId;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Manages the embedded Apache Ratis node for Q1 cluster coordination.
 *
 * <h3>Design</h3>
 * One Raft group covers the entire cluster (all partitions share one leader).
 * All writes go through {@link #submit}, which blocks until the entry is
 * committed to a quorum. The {@link Q1StateMachine} applies each committed
 * entry to the local {@link io.q1.core.StorageEngine} on every node.
 *
 * <h3>Leader routing</h3>
 * Non-leader nodes return a 307 redirect (see {@link #leaderHttpBaseUrl()}).
 * Only the leader calls {@link #submit}.
 *
 * <h3>Membership</h3>
 * Peers are configured statically at startup via {@link ClusterConfig#peers()}.
 * Dynamic membership change is not supported in this version.
 */
public final class RatisCluster implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(RatisCluster.class);

    /**
     * Fixed Raft group UUID.  All nodes in the same cluster must share this
     * value.  Override with the {@code Q1_RAFT_GROUP_ID} environment variable.
     */
    private static final String DEFAULT_GROUP_UUID = "51310001-0000-0000-0000-000000000001";

    private final ClusterConfig   config;
    private final RaftGroupId     groupId;
    private final RaftGroup       group;
    private final RaftPeerId      selfId;
    private final RaftServer      server;
    private final RaftClient      client;

    public RatisCluster(ClusterConfig config, Q1StateMachine stateMachine) throws IOException {
        this.config = config;

        String groupUuid = config.raftGroupId() != null
                ? config.raftGroupId()
                : System.getenv().getOrDefault("Q1_RAFT_GROUP_ID", DEFAULT_GROUP_UUID);
        this.groupId = RaftGroupId.valueOf(UUID.fromString(groupUuid));
        this.selfId  = RaftPeerId.valueOf(config.self().id());

        List<RaftPeer> raftPeers = config.peers().stream()
                .map(n -> RaftPeer.newBuilder()
                        .setId(n.id())
                        .setAddress(n.raftAddress())
                        .build())
                .toList();

        this.group = RaftGroup.valueOf(groupId, raftPeers);

        RaftProperties props = new RaftProperties();
        GrpcConfigKeys.Server.setPort(props, config.self().raftPort());
        RaftServerConfigKeys.setStorageDir(props,
                List.of(new File(config.raftDataDir())));

        // Raise the per-entry size cap from the default 4 MiB to 64 MiB so that
        // large objects (e.g. email attachments) replicate without error.
        // Four limits must be raised in concert:
        //   1. gRPC max message size      — transport layer
        //   2. Log.Appender.bufferByteLimit — per-entry cap checked in RaftLogBase.appendImpl
        //   3. Raft log write buffer      — serialisation buffer before fsync
        //   4. Raft log segment size      — kept well above the max entry size
        SizeInBytes maxEntry = SizeInBytes.valueOf("64MB");
        GrpcConfigKeys.setMessageSizeMax(props, maxEntry);
        RaftServerConfigKeys.Log.Appender.setBufferByteLimit(props, maxEntry);
        // write.buffer.size must be strictly > appender.buffer.byte-limit + 8
        RaftServerConfigKeys.Log.setWriteBufferSize(props, SizeInBytes.valueOf("128MB"));
        RaftServerConfigKeys.Log.setSegmentSizeMax(props, SizeInBytes.valueOf("256MB"));

        // Take a snapshot automatically every N committed log entries so that
        // the log does not grow unboundedly and restarts replay only recent entries.
        RaftServerConfigKeys.Snapshot.setAutoTriggerEnabled(props, true);
        RaftServerConfigKeys.Snapshot.setAutoTriggerThreshold(props, 10_000L);

        Files.createDirectories(Path.of(config.raftDataDir()));
        File raftDir = new File(config.raftDataDir());
        String[] children = raftDir.list();
        RaftStorage.StartupOption startupOption = (children != null && children.length > 0)
                ? RaftStorage.StartupOption.RECOVER
                : RaftStorage.StartupOption.FORMAT;

        this.server = RaftServer.newBuilder()
                .setGroup(group)
                .setServerId(selfId)
                .setStateMachine(stateMachine)
                .setProperties(props)
                .setOption(startupOption)
                .build();

        this.client = RaftClient.newBuilder()
                .setProperties(props)
                .setRaftGroup(group)
                .setClientId(ClientId.randomId())
                .build();
    }

    // ── lifecycle ─────────────────────────────────────────────────────────

    public void start() throws IOException {
        server.start();
        log.info("Ratis node {} started (group={}, raftPort={})",
                config.self().id(), groupId, config.self().raftPort());
    }

    @Override
    public void close() {
        try { client.close(); } catch (IOException e) { log.warn("Error closing RaftClient", e); }
        try { server.close(); } catch (IOException e) { log.warn("Error closing RaftServer", e); }
        log.info("Ratis node {} stopped", config.self().id());
    }

    // ── routing API ───────────────────────────────────────────────────────

    /** True if this node is currently the Raft leader. */
    public boolean isLocalLeader() {
        try {
            return server.getDivision(groupId).getInfo().isLeader();
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * HTTP base URL of the current Raft leader, or {@link Optional#empty()} when
     * this node is the leader (no redirect needed) or leadership is unknown.
     *
     * <p>Uses the last leader ID known to the {@link RaftClient} (updated after
     * each {@link #submit} call).
     */
    public Optional<String> leaderHttpBaseUrl() {
        if (isLocalLeader()) return Optional.empty();
        // Primary: server-side leader info from Raft consensus (reliable after election)
        Optional<String> serverSide = leaderIdFromServer()
                .flatMap(id -> config.peers().stream()
                        .filter(n -> n.id().equals(id))
                        .findFirst()
                        .map(NodeId::httpBase));
        if (serverSide.isPresent()) return serverSide;
        // Fallback: client's cached leader — guard against self-redirect
        RaftPeerId leaderId = client.getLeaderId();
        if (leaderId == null || leaderId.equals(selfId)) return Optional.empty();
        return config.peers().stream()
                .filter(n -> n.id().equals(leaderId.toString()))
                .findFirst()
                .map(NodeId::httpBase);
    }

    /**
     * Returns the leader's peer ID string from the Raft server's own role state
     * (populated from consensus heartbeats — reliable after election).
     */
    private Optional<String> leaderIdFromServer() {
        try {
            RaftProtos.RoleInfoProto roleInfo =
                    server.getDivision(groupId).getInfo().getRoleInfoProto();
            if (!roleInfo.hasFollowerInfo()) return Optional.empty();
            RaftProtos.ServerRpcProto leaderRpc = roleInfo.getFollowerInfo().getLeaderInfo();
            if (!leaderRpc.hasId()) return Optional.empty();
            String id = leaderRpc.getId().getId().toStringUtf8();
            return id.isEmpty() ? Optional.empty() : Optional.of(id);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * Submit a command to the Raft log and block until it is committed.
     *
     * <p>Must only be called on the leader node (after the 307 redirect in
     * {@link io.q1.api.S3Router} has ensured we are the leader).
     *
     * @throws IOException if the commit fails or the node is not the leader
     */
    public void submit(RatisCommand cmd) throws IOException {
        RaftClientReply reply = client.io().send(cmd.toMessage());
        if (!reply.isSuccess()) {
            throw new IOException("Raft commit failed for " + cmd.type()
                    + ": " + reply.getException());
        }
    }

    /**
     * Submit a command to the Raft log asynchronously.
     *
     * <p>Returns a {@link CompletableFuture} that completes when the entry is
     * committed by a quorum.  Callers should wait on the future before
     * responding to the client, but can overlap other local work in between.
     */
    public CompletableFuture<Void> submitAsync(RatisCommand cmd) {
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
     * True when the cluster has a leader (Raft group is operational).
     * In EC mode, also checks that enough nodes are available ({@code >= k}).
     */
    public boolean isClusterReady() {
        EcConfig ec = config.ecConfig();
        if (ec.enabled()) {
            return config.peers().size() >= ec.dataShards();
        }
        // Ready as soon as we know who the leader is
        return isLocalLeader() || leaderHttpBaseUrl().isPresent();
    }

    /**
     * Returns the ID of the current Raft leader, or {@link Optional#empty()} if
     * leadership is unknown (e.g. during an election).
     */
    public Optional<String> leaderId() {
        if (isLocalLeader()) return Optional.of(config.self().id());
        return leaderIdFromServer();
    }

    public NodeId self() { return config.self(); }

    public ClusterConfig config() { return config; }

    /**
     * Deterministically selects {@code k+m} nodes for the given object key
     * (EC mode). Uses the same ring algorithm as the previous etcd-based design.
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
}
