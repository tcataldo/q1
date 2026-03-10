package io.q1.cluster;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link EcMetadata} wire-format serialisation.
 */
class EcMetadataTest {

    @Test
    void roundTripWireFormat() {
        EcMetadata meta = new EcMetadata(
                4, 2, 131072L, 32768,
                List.of("node0:10.0.0.1:9000",
                        "node1:10.0.0.2:9000",
                        "node2:10.0.0.3:9000",
                        "node3:10.0.0.4:9000",
                        "node4:10.0.0.5:9000",
                        "node5:10.0.0.6:9000"));

        String wire = meta.toWire();
        EcMetadata parsed = EcMetadata.fromWire(wire);

        assertEquals(meta.k(),            parsed.k());
        assertEquals(meta.m(),            parsed.m());
        assertEquals(meta.originalSize(), parsed.originalSize());
        assertEquals(meta.shardSize(),    parsed.shardSize());
        assertEquals(meta.nodeWireIds(),  parsed.nodeWireIds());
    }

    @Test
    void nodeForShardReturnsCorrectNode() {
        EcMetadata meta = new EcMetadata(
                2, 1, 100L, 50,
                List.of("nodeA:localhost:9000",
                        "nodeB:localhost:9001",
                        "nodeC:localhost:9002"));

        assertEquals(new NodeId("nodeA", "localhost", 9000), meta.nodeForShard(0));
        assertEquals(new NodeId("nodeB", "localhost", 9001), meta.nodeForShard(1));
        assertEquals(new NodeId("nodeC", "localhost", 9002), meta.nodeForShard(2));
    }

    @Test
    void wrongNodeCountThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new EcMetadata(2, 1, 100L, 50,
                        List.of("nodeA:localhost:9000"))); // only 1 wire ID, need k+m=3
    }

    @Test
    void fromWireInvalidThrows() {
        assertThrows(Exception.class, () -> EcMetadata.fromWire("bad|data"));
    }
}
