package io.q1.grpc;

import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.q1.cluster.NodeId;
import io.q1.cluster.ShardClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * gRPC-based {@link ShardClient} implementation.
 *
 * <p>Maintains one {@link ManagedChannel} per peer node (keyed by node ID),
 * created lazily on first use. Each call uses a 5-second deadline, matching
 * the timeout of the HTTP implementation.
 *
 * <p>Must be {@link #close()}d when the server shuts down to release channel
 * resources.
 */
public final class GrpcShardClient implements ShardClient, Closeable {

    private static final Logger log = LoggerFactory.getLogger(GrpcShardClient.class);

    private static final long DEADLINE_SECONDS = 5;

    private final Map<String, ManagedChannel> channels;

    /** Production constructor: channels are created lazily from node addresses. */
    public GrpcShardClient(List<NodeId> peers) {
        this.channels = new ConcurrentHashMap<>();
        // Pre-register peers so channel lookup does not need the full NodeId later
        for (NodeId peer : peers) {
            channels.put(peer.id(),
                    ManagedChannelBuilder.forTarget(peer.grpcAddress())
                            .usePlaintext()
                            .build());
        }
    }

    /** Package-private constructor for tests: accepts pre-built channels by node ID. */
    GrpcShardClient(Map<String, ManagedChannel> prebuiltChannels) {
        this.channels = new ConcurrentHashMap<>(prebuiltChannels);
    }

    // ── ShardClient ───────────────────────────────────────────────────────────

    @Override
    public void putShard(NodeId node, int shardIndex, String bucket, String key,
                         byte[] shardData) throws IOException {
        try {
            stubFor(node).putShard(PutShardRequest.newBuilder()
                    .setBucket(bucket)
                    .setKey(key)
                    .setShardIndex(shardIndex)
                    .setPayload(ByteString.copyFrom(shardData))
                    .build());
        } catch (io.grpc.StatusRuntimeException e) {
            throw new IOException("gRPC putShard failed for shard " + shardIndex + " on " + node, e);
        }
    }

    @Override
    public byte[] getShard(NodeId node, int shardIndex, String bucket, String key) throws IOException {
        try {
            GetShardResponse resp = stubFor(node).getShard(GetShardRequest.newBuilder()
                    .setBucket(bucket)
                    .setKey(key)
                    .setShardIndex(shardIndex)
                    .build());
            return resp.getFound() ? resp.getPayload().toByteArray() : null;
        } catch (io.grpc.StatusRuntimeException e) {
            throw new IOException("gRPC getShard failed for shard " + shardIndex + " on " + node, e);
        }
    }

    @Override
    public boolean shardExists(NodeId node, int shardIndex, String bucket, String key) throws IOException {
        try {
            HeadShardResponse resp = stubFor(node).headShard(HeadShardRequest.newBuilder()
                    .setBucket(bucket)
                    .setKey(key)
                    .setShardIndex(shardIndex)
                    .build());
            return resp.getExists();
        } catch (io.grpc.StatusRuntimeException e) {
            throw new IOException("gRPC headShard failed for shard " + shardIndex + " on " + node, e);
        }
    }

    @Override
    public void deleteShard(NodeId node, int shardIndex, String bucket, String key) throws IOException {
        try {
            stubFor(node).deleteShard(DeleteShardRequest.newBuilder()
                    .setBucket(bucket)
                    .setKey(key)
                    .setShardIndex(shardIndex)
                    .build());
        } catch (io.grpc.StatusRuntimeException e) {
            throw new IOException("gRPC deleteShard failed for shard " + shardIndex + " on " + node, e);
        }
    }

    // ── lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void close() {
        int count = channels.size();
        channels.values().forEach(ch -> {
            ch.shutdown();
            try {
                if (!ch.awaitTermination(5, TimeUnit.SECONDS)) ch.shutdownNow();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                ch.shutdownNow();
            }
        });
        channels.clear();
        log.info("gRPC shard client closed ({} channels)", count);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ShardServiceGrpc.ShardServiceBlockingStub stubFor(NodeId node) {
        ManagedChannel ch = channels.computeIfAbsent(node.id(), k ->
                ManagedChannelBuilder.forTarget(node.grpcAddress())
                        .usePlaintext()
                        .build());
        return ShardServiceGrpc.newBlockingStub(ch)
                .withDeadlineAfter(DEADLINE_SECONDS, TimeUnit.SECONDS);
    }
}
