package io.q1.grpc;

import com.google.protobuf.ByteString;
import io.grpc.stub.StreamObserver;
import io.q1.cluster.ShardStorage;
import io.q1.core.StorageEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * gRPC server-side implementation of {@link ShardServiceGrpc}.
 *
 * <p>Stores and retrieves EC shards from the local {@link StorageEngine} under
 * the internal bucket defined by {@link ShardStorage#SHARD_BUCKET}.
 * This is the gRPC counterpart of the HTTP {@code ShardHandler}.
 */
public final class ShardServiceImpl extends ShardServiceGrpc.ShardServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(ShardServiceImpl.class);

    private final StorageEngine engine;

    public ShardServiceImpl(StorageEngine engine) {
        this.engine = engine;
    }

    @Override
    public void putShard(PutShardRequest req, StreamObserver<PutShardResponse> obs) {
        try {
            engine.put(ShardStorage.SHARD_BUCKET,
                    ShardStorage.shardKey(req.getBucket(), req.getKey(), req.getShardIndex()),
                    req.getPayload().toByteArray());
            log.debug("gRPC PutShard {}/{}/{:02d} ({} bytes)",
                    req.getBucket(), req.getKey(), req.getShardIndex(), req.getPayload().size());
            obs.onNext(PutShardResponse.getDefaultInstance());
            obs.onCompleted();
        } catch (IOException e) {
            log.error("PutShard failed for {}/{}/{}", req.getBucket(), req.getKey(), req.getShardIndex(), e);
            obs.onError(io.grpc.Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void getShard(GetShardRequest req, StreamObserver<GetShardResponse> obs) {
        try {
            byte[] data = engine.get(ShardStorage.SHARD_BUCKET,
                    ShardStorage.shardKey(req.getBucket(), req.getKey(), req.getShardIndex()));
            GetShardResponse.Builder resp = GetShardResponse.newBuilder();
            if (data != null) {
                resp.setFound(true).setPayload(ByteString.copyFrom(data));
            }
            obs.onNext(resp.build());
            obs.onCompleted();
        } catch (IOException e) {
            log.error("GetShard failed for {}/{}/{}", req.getBucket(), req.getKey(), req.getShardIndex(), e);
            obs.onError(io.grpc.Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void headShard(HeadShardRequest req, StreamObserver<HeadShardResponse> obs) {
        try {
            boolean exists = engine.exists(ShardStorage.SHARD_BUCKET,
                    ShardStorage.shardKey(req.getBucket(), req.getKey(), req.getShardIndex()));
            obs.onNext(HeadShardResponse.newBuilder().setExists(exists).build());
            obs.onCompleted();
        } catch (IOException e) {
            log.error("HeadShard failed for {}/{}/{}", req.getBucket(), req.getKey(), req.getShardIndex(), e);
            obs.onError(io.grpc.Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void deleteShard(DeleteShardRequest req, StreamObserver<DeleteShardResponse> obs) {
        try {
            engine.delete(ShardStorage.SHARD_BUCKET,
                    ShardStorage.shardKey(req.getBucket(), req.getKey(), req.getShardIndex()));
            log.debug("gRPC DeleteShard {}/{}/{:02d}", req.getBucket(), req.getKey(), req.getShardIndex());
            obs.onNext(DeleteShardResponse.getDefaultInstance());
            obs.onCompleted();
        } catch (IOException e) {
            log.error("DeleteShard failed for {}/{}/{}", req.getBucket(), req.getKey(), req.getShardIndex(), e);
            obs.onError(io.grpc.Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }
}
