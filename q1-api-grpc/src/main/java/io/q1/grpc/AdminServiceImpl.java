package io.q1.grpc;

import io.grpc.stub.StreamObserver;
import io.q1.cluster.RatisCluster;
import io.q1.core.Partition;
import io.q1.core.StorageEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * gRPC server-side implementation of {@link AdminServiceGrpc}.
 *
 * <p>Reports node health, identity, and partition metrics. Designed to be
 * consumed by the future admin CLI ({@code q1-admin}).
 *
 * <p>Works in both standalone mode ({@code cluster == null}) and cluster mode.
 */
public final class AdminServiceImpl extends AdminServiceGrpc.AdminServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(AdminServiceImpl.class);

    private final StorageEngine engine;
    private final RatisCluster  cluster; // null in standalone mode

    public AdminServiceImpl(StorageEngine engine, RatisCluster cluster) {
        this.engine  = engine;
        this.cluster = cluster;
    }

    @Override
    public void getHealth(HealthRequest request, StreamObserver<HealthResponse> obs) {
        HealthResponse.Builder resp = HealthResponse.newBuilder();
        if (cluster == null) {
            resp.setStatus(HealthResponse.Status.HEALTHY)
                .setNodeId("standalone")
                .setIsLeader(true);
        } else {
            resp.setStatus(cluster.isClusterReady()
                            ? HealthResponse.Status.HEALTHY
                            : HealthResponse.Status.DEGRADED)
                .setNodeId(cluster.self().id())
                .setIsLeader(cluster.isLocalLeader());
        }
        obs.onNext(resp.build());
        obs.onCompleted();
    }

    @Override
    public void getNodeInfo(NodeInfoRequest request, StreamObserver<NodeInfoResponse> obs) {
        NodeInfoResponse.Builder resp = NodeInfoResponse.newBuilder();
        if (cluster == null) {
            resp.setNodeId("standalone").setIsLeader(true);
        } else {
            var self = cluster.self();
            resp.setNodeId(self.id())
                .setHost(self.host())
                .setHttpPort(self.port())
                .setRaftPort(self.raftPort())
                .setGrpcPort(self.grpcPort())
                .setIsLeader(cluster.isLocalLeader())
                .setNumPartitions(cluster.config().numPartitions());
        }
        obs.onNext(resp.build());
        obs.onCompleted();
    }

    @Override
    public void listPartitions(ListPartitionsRequest request,
                               StreamObserver<ListPartitionsResponse> obs) {
        ListPartitionsResponse.Builder resp = ListPartitionsResponse.newBuilder();

        // ── cluster status & peers ────────────────────────────────────────
        if (cluster == null) {
            resp.setClusterStatus(ListPartitionsResponse.ClusterStatus.CLUSTER_HEALTHY)
                .setLeaderId("standalone")
                .addPeers(PeerInfo.newBuilder()
                        .setNodeId("standalone")
                        .setIsLeader(true)
                        .setIsSelf(true)
                        .build());
        } else {
            boolean ready    = cluster.isClusterReady();
            String  leaderId = cluster.leaderId().orElse("");
            String  selfId   = cluster.self().id();

            resp.setClusterStatus(ready
                    ? ListPartitionsResponse.ClusterStatus.CLUSTER_HEALTHY
                    : ListPartitionsResponse.ClusterStatus.CLUSTER_DEGRADED)
                .setLeaderId(leaderId);

            for (var peer : cluster.activeNodes()) {
                resp.addPeers(PeerInfo.newBuilder()
                        .setNodeId(peer.id())
                        .setHost(peer.host())
                        .setHttpPort(peer.port())
                        .setGrpcPort(peer.grpcPort())
                        .setIsSelf(peer.id().equals(selfId))
                        .setIsLeader(peer.id().equals(leaderId))
                        .build());
            }
        }

        // ── per-partition stats ───────────────────────────────────────────
        for (int i = 0; i < engine.numPartitions(); i++) {
            Partition.Stats s = engine.partitionStats(i);
            resp.addPartitions(PartitionInfo.newBuilder()
                    .setPartitionId(i)
                    .setSegmentCount(s.segmentCount())
                    .setTotalSizeBytes(s.totalSizeBytes())
                    .setLiveKeyCount(s.liveKeyCount())
                    .build());
        }

        obs.onNext(resp.build());
        obs.onCompleted();
    }
}
