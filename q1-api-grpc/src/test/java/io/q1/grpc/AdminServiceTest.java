package io.q1.grpc;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.q1.core.StorageEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests {@link AdminServiceImpl} end-to-end in standalone mode (no cluster)
 * using an in-process gRPC server.
 */
class AdminServiceTest {

    @TempDir Path tmpDir;

    private StorageEngine  engine;
    private Server         server;
    private ManagedChannel channel;
    private AdminServiceGrpc.AdminServiceBlockingStub stub;

    @BeforeEach
    void setUp() throws Exception {
        engine = new StorageEngine(tmpDir, 4);

        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name)
                .directExecutor()
                .addService(new AdminServiceImpl(engine, null)) // standalone: no cluster
                .build()
                .start();

        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        stub    = AdminServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void tearDown() throws Exception {
        channel.shutdownNow();
        server.shutdownNow();
        engine.close();
    }

    @Test
    void listPartitionsReturnsAllPartitions() {
        ListPartitionsResponse resp = stub.listPartitions(ListPartitionsRequest.newBuilder().build());

        assertEquals(4, resp.getPartitionsCount());
        for (int i = 0; i < 4; i++) {
            PartitionInfo p = resp.getPartitions(i);
            assertEquals(i, p.getPartitionId());
            assertEquals(1, p.getSegmentCount());  // one empty segment created on open
            assertEquals(0, p.getLiveKeyCount());
        }
    }

    @Test
    void listPartitionsClusterStatusHealthyInStandalone() {
        ListPartitionsResponse resp = stub.listPartitions(ListPartitionsRequest.newBuilder().build());

        assertEquals(ListPartitionsResponse.ClusterStatus.CLUSTER_HEALTHY, resp.getClusterStatus());
        assertEquals("standalone", resp.getLeaderId());
        assertEquals(1, resp.getPeersCount());
        assertTrue(resp.getPeers(0).getIsLeader());
        assertTrue(resp.getPeers(0).getIsSelf());
    }

    @Test
    void listPartitionsSizeGrowsAfterWrite() throws Exception {
        engine.createBucket("b");
        engine.put("b", "k", "hello".getBytes());

        ListPartitionsResponse resp = stub.listPartitions(ListPartitionsRequest.newBuilder().build());

        long totalSize = resp.getPartitionsList().stream()
                .mapToLong(PartitionInfo::getTotalSizeBytes)
                .sum();
        assertTrue(totalSize > 0, "total size should be > 0 after a write");

        long totalKeys = resp.getPartitionsList().stream()
                .mapToLong(PartitionInfo::getLiveKeyCount)
                .sum();
        assertEquals(1, totalKeys);
    }
}
