package io.q1.cluster;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ErasureCoder}: encode/decode round-trips and
 * degraded-mode reconstruction with missing shards.
 */
class ErasureCoderTest {

    private static final Random RNG = new Random(42);

    // ── basic round-trip ──────────────────────────────────────────────────

    @Test
    void encodeDecodeRoundTrip_k2m1() throws IOException {
        ErasureCoder coder = coder(2, 1);
        byte[] data = randomBytes(1024);

        byte[][] shards = coder.encode(data);
        assertEquals(3, shards.length);
        // all shards same size
        int sz = shards[0].length;
        for (byte[] s : shards) assertEquals(sz, s.length);

        byte[] recovered = coder.decode(shards, data.length);
        assertArrayEquals(data, recovered, "Round-trip must reproduce original bytes");
    }

    @Test
    void encodeDecodeRoundTrip_k4m2() throws IOException {
        ErasureCoder coder = coder(4, 2);
        byte[] data = randomBytes(128 * 1024); // 128 KiB — typical email

        byte[][] shards = coder.encode(data);
        assertEquals(6, shards.length);

        byte[] recovered = coder.decode(shards, data.length);
        assertArrayEquals(data, recovered);
    }

    @Test
    void encodeDecodeSmallObject() throws IOException {
        ErasureCoder coder = coder(2, 1);
        byte[] data = "hello".getBytes();

        byte[][] shards = coder.encode(data);
        byte[] recovered = coder.decode(shards, data.length);
        assertArrayEquals(data, recovered);
    }

    @Test
    void encodeDecodeSingleByte() throws IOException {
        ErasureCoder coder = coder(2, 1);
        byte[] data = new byte[]{ 0x42 };

        byte[][] shards = coder.encode(data);
        byte[] recovered = coder.decode(shards, data.length);
        assertArrayEquals(data, recovered);
    }

    // ── degraded-mode reconstruction ──────────────────────────────────────

    @Test
    void reconstructWithOneMissingShard_k2m1() throws IOException {
        ErasureCoder coder = coder(2, 1);
        byte[] data = randomBytes(4096);
        byte[][] shards = coder.encode(data);

        // Drop shard 0 (first data shard)
        byte[][] degraded = Arrays.copyOf(shards, shards.length);
        degraded[0] = null;

        byte[] recovered = coder.decode(degraded, data.length);
        assertArrayEquals(data, recovered, "Must reconstruct with k=2 shards when 1 of 3 is missing");
    }

    @Test
    void reconstructWithParityShardMissing_k2m1() throws IOException {
        ErasureCoder coder = coder(2, 1);
        byte[] data = randomBytes(4096);
        byte[][] shards = coder.encode(data);

        // Drop the parity shard (index 2)
        byte[][] degraded = Arrays.copyOf(shards, shards.length);
        degraded[2] = null;

        byte[] recovered = coder.decode(degraded, data.length);
        assertArrayEquals(data, recovered, "Must still decode when only parity is missing");
    }

    @Test
    void reconstructWithTwoMissingShards_k4m2() throws IOException {
        ErasureCoder coder = coder(4, 2);
        byte[] data = randomBytes(64 * 1024);
        byte[][] shards = coder.encode(data);

        // Drop shards 1 and 4 (one data, one parity)
        byte[][] degraded = Arrays.copyOf(shards, shards.length);
        degraded[1] = null;
        degraded[4] = null;

        byte[] recovered = coder.decode(degraded, data.length);
        assertArrayEquals(data, recovered, "k=4,m=2 must recover from any 2 missing shards");
    }

    @Test
    void decodeFails_whenTooManyMissing() {
        ErasureCoder coder = coder(2, 1);
        byte[] data = randomBytes(512);
        byte[][] shards = coder.encode(data);

        // Drop 2 shards — only 1 remains, k=2 requires at least 2
        byte[][] degraded = Arrays.copyOf(shards, shards.length);
        degraded[0] = null;
        degraded[1] = null;

        assertThrows(IOException.class, () -> coder.decode(degraded, data.length),
                "decode must throw when fewer than k shards are present");
    }

    // ── shard size invariants ─────────────────────────────────────────────

    @Test
    void shardSizeIsCeilDivision() {
        ErasureCoder coder = coder(3, 1);
        // 10 bytes, k=3 → ceil(10/3) = 4 bytes per shard
        byte[][] shards = coder.encode(new byte[10]);
        assertEquals(4, shards[0].length);
    }

    @Test
    void allShardsHaveSameSize() {
        ErasureCoder coder = coder(4, 2);
        byte[][] shards = coder.encode(randomBytes(131071)); // prime length, not divisible by k
        int sz = shards[0].length;
        for (byte[] s : shards) assertEquals(sz, s.length, "All shards must be the same size");
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private static ErasureCoder coder(int k, int m) {
        return new ErasureCoder(new EcConfig(k, m));
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RNG.nextBytes(b);
        return b;
    }
}
