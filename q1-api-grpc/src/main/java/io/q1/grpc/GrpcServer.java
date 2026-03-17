package io.q1.grpc;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.q1.cluster.RatisCluster;
import io.q1.core.StorageEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Lifecycle wrapper for the Q1 internal gRPC server.
 *
 * <p>Hosts {@link ShardServiceImpl} and {@link AdminServiceImpl} on a single port.
 * Started after the HTTP server is up; stopped before the StorageEngine is closed.
 *
 * <p>Configured via {@code Q1_GRPC_PORT} (default {@code 7000}).
 */
public final class GrpcServer implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(GrpcServer.class);

    private final Server server;
    private final int    port;

    /**
     * @param port    gRPC listen port
     * @param engine  local storage engine (serves ShardServiceImpl)
     * @param cluster Raft cluster handle, or {@code null} in standalone mode
     */
    public GrpcServer(int port, StorageEngine engine, RatisCluster cluster) {
        this.port   = port;
        this.server = ServerBuilder.forPort(port)
                .addService(new ShardServiceImpl(engine))
                .addService(new AdminServiceImpl(cluster))
                .build();
    }

    public void start() throws IOException {
        server.start();
        log.info("gRPC server listening on port {}", port);
    }

    @Override
    public void close() throws IOException {
        server.shutdown();
        try {
            if (!server.awaitTermination(5, TimeUnit.SECONDS)) {
                server.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            server.shutdownNow();
        }
        log.info("gRPC server stopped");
    }
}
