package io.q1.grpc;

import io.grpc.stub.StreamObserver;
import io.q1.cluster.RatisCluster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * gRPC server-side implementation of {@link AdminServiceGrpc}.
 *
 * <p>Reports node health and identity. Designed to be consumed by the future
 * admin CLI ({@code q1-admin}).
 *
 * <p>Works in both standalone mode ({@code cluster == null}) and cluster mode.
 */
public final class AdminServiceImpl extends AdminServiceGrpc.AdminServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(AdminServiceImpl.class);

    private final RatisCluster cluster; // null in standalone mode

    public AdminServiceImpl(RatisCluster cluster) {
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
}
