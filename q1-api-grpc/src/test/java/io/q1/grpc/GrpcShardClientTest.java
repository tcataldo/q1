package io.q1.grpc;

import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.q1.cluster.NodeId;
import io.q1.cluster.ShardStorage;
import io.q1.core.StorageEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests {@link GrpcShardClient} end-to-end against an in-process
 * {@link ShardServiceImpl} backed by a real {@link StorageEngine}.
 *
 * <p>Uses gRPC's {@link InProcessServerBuilder} / {@link InProcessChannelBuilder}
 * to avoid real network I/O while still exercising the full serialisation and
 * service-dispatch path.
 */
class GrpcShardClientTest {

    @TempDir Path tmpDir;

    private StorageEngine  engine;
    private Server         inProcServer;
    private ManagedChannel inProcChannel;
    private GrpcShardClient client;
    private NodeId          node;

    @BeforeEach
    void setUp() throws Exception {
        engine = new StorageEngine(tmpDir, 4);
        engine.createBucket(ShardStorage.SHARD_BUCKET);

        String serverName = InProcessServerBuilder.generateName();

        inProcServer = InProcessServerBuilder
                .forName(serverName)
                .directExecutor()
                .addService(new ShardServiceImpl(engine))
                .build()
                .start();

        inProcChannel = InProcessChannelBuilder
                .forName(serverName)
                .directExecutor()
                .build();

        node   = new NodeId("test-node", "localhost", 9000, 6000, 7000);
        client = new GrpcShardClient(Map.of(node.id(), inProcChannel));
    }

    @AfterEach
    void tearDown() throws Exception {
        client.close();
        inProcChannel.shutdownNow();
        inProcServer.shutdownNow();
        engine.close();
    }

    // ── put + get round-trip ──────────────────────────────────────────────────

    @Test
    void putAndGetRoundTrip() throws IOException {
        byte[] payload = {1, 2, 3, 4, 5};

        client.putShard(node, 0, "bucket", "key/obj", payload);

        byte[] got = client.getShard(node, 0, "bucket", "key/obj");

        assertNotNull(got);
        assertArrayEquals(payload, got);
    }

    @Test
    void getMissingShardReturnsNull() throws IOException {
        byte[] got = client.getShard(node, 0, "bucket", "nonexistent");

        assertNull(got);
    }

    // ── head ──────────────────────────────────────────────────────────────────

    @Test
    void headReflectsExistence() throws IOException {
        assertFalse(client.shardExists(node, 1, "bucket", "obj"));

        client.putShard(node, 1, "bucket", "obj", new byte[]{9});

        assertTrue(client.shardExists(node, 1, "bucket", "obj"));
    }

    // ── delete ────────────────────────────────────────────────────────────────

    @Test
    void deleteRemovesShard() throws IOException {
        client.putShard(node, 0, "bucket", "x", new byte[]{42});

        client.deleteShard(node, 0, "bucket", "x");

        assertNull(client.getShard(node, 0, "bucket", "x"));
    }

    @Test
    void deleteIsIdempotent() throws IOException {
        client.putShard(node, 0, "bucket", "x", new byte[]{7});
        client.deleteShard(node, 0, "bucket", "x");

        // Second delete on absent shard must not throw
        assertDoesNotThrow(() -> client.deleteShard(node, 0, "bucket", "x"));
    }

    // ── multi-shard fan-out ────────────────────────────────────────────────────

    @Test
    void multipleShardsForSameObject() throws IOException {
        for (int i = 0; i < 4; i++) {
            client.putShard(node, i, "bkt", "obj", new byte[]{(byte) i});
        }

        for (int i = 0; i < 4; i++) {
            byte[] got = client.getShard(node, i, "bkt", "obj");
            assertNotNull(got, "shard " + i + " should exist");
            assertEquals(i, got[0] & 0xFF, "shard " + i + " payload mismatch");
        }
    }

    @Test
    void shardIndexIsolation() throws IOException {
        // Shards for the same key at different indexes must not collide
        client.putShard(node, 0, "bkt", "obj", new byte[]{10});
        client.putShard(node, 1, "bkt", "obj", new byte[]{20});

        assertArrayEquals(new byte[]{10}, client.getShard(node, 0, "bkt", "obj"));
        assertArrayEquals(new byte[]{20}, client.getShard(node, 1, "bkt", "obj"));
    }

    // ── large payload ─────────────────────────────────────────────────────────

    @Test
    void largePayloadRoundTrip() throws IOException {
        byte[] large = new byte[256 * 1024]; // 256 KB
        for (int i = 0; i < large.length; i++) large[i] = (byte) i;

        client.putShard(node, 0, "bkt", "big", large);

        byte[] got = client.getShard(node, 0, "bkt", "big");
        assertArrayEquals(large, got);
    }
}
