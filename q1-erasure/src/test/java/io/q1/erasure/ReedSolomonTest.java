package io.q1.erasure;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the raw {@link ReedSolomon} codec (encodeParity / decodeMissing).
 *
 * These operate at a lower level than ErasureCoderTest: no padding, no high-level
 * encode/decode wrapper — just the mathematical core.
 */
class ReedSolomonTest {

    private static final Random RNG = new Random(0xDEADBEEFL);

    // ── edge cases ────────────────────────────────────────────────────────

    @Test
    void encodeZeroLengthShards() {
        // Must not throw; zero-length shards are a valid degenerate case
        ReedSolomon rs = ReedSolomon.create(2, 1);
        byte[][] shards = new byte[3][0];
        assertDoesNotThrow(() -> rs.encodeParity(shards, 0, 0));
    }

    // ── construction ──────────────────────────────────────────────────────

    @Test
    void createRejectsZeroOrNegativeDataShards() {
        assertThrows(IllegalArgumentException.class, () -> ReedSolomon.create(0, 1));
        assertThrows(IllegalArgumentException.class, () -> ReedSolomon.create(-1, 2));
    }

    @Test
    void createRejectsZeroOrNegativeParityShards() {
        assertThrows(IllegalArgumentException.class, () -> ReedSolomon.create(2, 0));
        assertThrows(IllegalArgumentException.class, () -> ReedSolomon.create(3, -1));
    }

    @Test
    void createSucceedsForValidParams() {
        assertDoesNotThrow(() -> ReedSolomon.create(1, 1));
        assertDoesNotThrow(() -> ReedSolomon.create(10, 4));
    }

    // ── encoding: mathematical properties ────────────────────────────────

    @Test
    void encodeForK1M1_parityEqualsData() {
        // For k=1, m=1 the encode matrix is [[1],[1]]:
        // parity coef = 1, so parity[i] = 1 * data[i] = data[i].
        ReedSolomon rs = ReedSolomon.create(1, 1);
        byte[] data = {0x42, 0x17, (byte) 0xFF, 0x00};
        byte[][] shards = {data.clone(), new byte[data.length]};

        rs.encodeParity(shards, 0, data.length);

        assertArrayEquals(shards[0], shards[1], "k=1,m=1: parity must equal data");
    }

    @Test
    void encodeDoesNotModifyDataShards() {
        ReedSolomon rs = ReedSolomon.create(3, 2);
        int sz = 32;
        byte[][] shards = allRandomShards(5, sz);
        byte[][] origData = new byte[3][sz];
        for (int i = 0; i < 3; i++) origData[i] = shards[i].clone();

        rs.encodeParity(shards, 0, sz);

        for (int i = 0; i < 3; i++)
            assertArrayEquals(origData[i], shards[i], "data shard " + i + " must be unchanged");
    }

    @Test
    void encodeProducesNonZeroParity() {
        // For random non-zero data, parity should be non-zero (with overwhelming probability)
        ReedSolomon rs = ReedSolomon.create(2, 2);
        int sz = 64;
        byte[][] shards = allRandomShards(4, sz);

        rs.encodeParity(shards, 0, sz);

        // Both parity shards should not be all-zeros
        assertFalse(allZero(shards[2]), "parity shard 2 should not be all zeros");
        assertFalse(allZero(shards[3]), "parity shard 3 should not be all zeros");
    }

    @Test
    void encodingIsDeterministic() {
        ReedSolomon rs = ReedSolomon.create(2, 1);
        int sz = 16;
        byte[] d0 = randomBytes(sz);
        byte[] d1 = randomBytes(sz);

        byte[][] run1 = {d0.clone(), d1.clone(), new byte[sz]};
        byte[][] run2 = {d0.clone(), d1.clone(), new byte[sz]};
        rs.encodeParity(run1, 0, sz);
        rs.encodeParity(run2, 0, sz);

        assertArrayEquals(run1[2], run2[2], "same inputs must produce same parity");
    }

    // ── encoding: offset / byteCount ─────────────────────────────────────

    @Test
    void encodeRespectsOffsetAndByteCount() {
        ReedSolomon rs = ReedSolomon.create(2, 1);
        int sz = 12;
        byte[][] shards = allRandomShards(3, sz);
        byte[] sentinel = new byte[sz];
        Arrays.fill(sentinel, (byte) 0x55);
        shards[2] = sentinel.clone();

        // Encode only the middle 4 bytes [4..7]
        rs.encodeParity(shards, 4, 4);

        // Bytes outside [4..7) must still be 0x55
        for (int i = 0; i < 4; i++)
            assertEquals((byte) 0x55, shards[2][i], "byte " + i + " before range must be untouched");
        for (int i = 8; i < sz; i++)
            assertEquals((byte) 0x55, shards[2][i], "byte " + i + " after range must be untouched");
    }

    // ── decoding: round-trip ──────────────────────────────────────────────

    @Test
    void encodeDecodeRoundTrip_k2m1_noMissingShards() {
        ReedSolomon rs = ReedSolomon.create(2, 1);
        int sz = 64;
        byte[][] shards = allRandomShards(3, sz);
        byte[] orig0 = shards[0].clone();
        byte[] orig1 = shards[1].clone();

        rs.encodeParity(shards, 0, sz);
        // decodeMissing with all present should be a no-op on data
        rs.decodeMissing(shards, new boolean[]{true, true, true}, 0, sz);

        assertArrayEquals(orig0, shards[0], "data shard 0 unchanged after no-op decode");
        assertArrayEquals(orig1, shards[1], "data shard 1 unchanged after no-op decode");
    }

    @Test
    void decodeReconstructsMissingFirstDataShard() {
        ReedSolomon rs = ReedSolomon.create(2, 1);
        int sz = 48;
        byte[][] shards = allRandomShards(3, sz);
        rs.encodeParity(shards, 0, sz);

        byte[] expected = shards[0].clone();
        shards[0] = new byte[sz]; // zero out shard 0

        rs.decodeMissing(shards, new boolean[]{false, true, true}, 0, sz);

        assertArrayEquals(expected, shards[0], "shard 0 must be reconstructed");
    }

    @Test
    void decodeReconstructsMissingParityShard() {
        ReedSolomon rs = ReedSolomon.create(2, 1);
        int sz = 32;
        byte[][] shards = allRandomShards(3, sz);
        rs.encodeParity(shards, 0, sz);

        byte[] expectedParity = shards[2].clone();
        shards[2] = new byte[sz];

        rs.decodeMissing(shards, new boolean[]{true, true, false}, 0, sz);

        assertArrayEquals(expectedParity, shards[2], "parity shard must be reconstructed");
    }

    @Test
    void decodeReconstructsTwoMissingShards_k4m2() {
        ReedSolomon rs = ReedSolomon.create(4, 2);
        int sz = 128;
        byte[][] shards = allRandomShards(6, sz);
        rs.encodeParity(shards, 0, sz);

        byte[] expected1 = shards[1].clone();
        byte[] expected4 = shards[4].clone();
        shards[1] = new byte[sz];
        shards[4] = new byte[sz];

        rs.decodeMissing(shards, new boolean[]{true, false, true, true, false, true}, 0, sz);

        assertArrayEquals(expected1, shards[1], "data shard 1 must be reconstructed");
        assertArrayEquals(expected4, shards[4], "parity shard 4 must be reconstructed");
    }

    @Test
    void decodeWithAllMissingParityShards_k3m2() {
        // As long as k data shards remain, we can decode even if all m parity are gone
        ReedSolomon rs = ReedSolomon.create(3, 2);
        int sz = 64;
        byte[][] shards = allRandomShards(5, sz);
        rs.encodeParity(shards, 0, sz);

        byte[] expectedP0 = shards[3].clone();
        byte[] expectedP1 = shards[4].clone();
        shards[3] = new byte[sz];
        shards[4] = new byte[sz];

        rs.decodeMissing(shards, new boolean[]{true, true, true, false, false}, 0, sz);

        assertArrayEquals(expectedP0, shards[3], "parity shard 3");
        assertArrayEquals(expectedP1, shards[4], "parity shard 4");
    }

    @Test
    void decodeWithOnlyParityShardsAndSomeDataMissing() {
        // k=2,m=2: lose both data shards, recover from 2 parity shards
        ReedSolomon rs = ReedSolomon.create(2, 2);
        int sz = 32;
        byte[][] shards = allRandomShards(4, sz);
        rs.encodeParity(shards, 0, sz);

        byte[] exp0 = shards[0].clone();
        byte[] exp1 = shards[1].clone();
        shards[0] = new byte[sz];
        shards[1] = new byte[sz];

        rs.decodeMissing(shards, new boolean[]{false, false, true, true}, 0, sz);

        assertArrayEquals(exp0, shards[0], "data shard 0 from parity only");
        assertArrayEquals(exp1, shards[1], "data shard 1 from parity only");
    }

    // ── decoding: error handling ──────────────────────────────────────────

    @Test
    void decodeMissing_notEnoughShardsThrows() {
        ReedSolomon rs = ReedSolomon.create(3, 2);
        int sz = 16;
        byte[][] shards = new byte[5][sz];
        // Only 2 of 3 required present
        assertThrows(IllegalArgumentException.class,
                () -> rs.decodeMissing(shards, new boolean[]{true, true, false, false, false}, 0, sz));
    }

    // ── checkShards validation ────────────────────────────────────────────

    @Test
    void encodeParity_wrongShardCountThrows() {
        ReedSolomon rs = ReedSolomon.create(2, 1); // expects 3 shards
        assertThrows(IllegalArgumentException.class,
                () -> rs.encodeParity(new byte[2][8], 0, 8));
        assertThrows(IllegalArgumentException.class,
                () -> rs.encodeParity(new byte[4][8], 0, 8));
    }

    @Test
    void encodeParity_shardTooShortThrows() {
        ReedSolomon rs = ReedSolomon.create(2, 1);
        // shard[2] is only 3 bytes, but offset+byteCount=4
        byte[][] shards = {new byte[4], new byte[4], new byte[3]};
        assertThrows(IllegalArgumentException.class,
                () -> rs.encodeParity(shards, 0, 4));
    }

    @Test
    void decodeMissing_wrongShardCountThrows() {
        ReedSolomon rs = ReedSolomon.create(2, 1);
        byte[][] shards = new byte[2][8];
        boolean[] present = {true, true};
        assertThrows(IllegalArgumentException.class,
                () -> rs.decodeMissing(shards, present, 0, 8));
    }

    // ── cross-validation against Backblaze Python reference ───────────────

    @Test
    void knownParityValues_k5m5() {
        // Values copied from Backblaze JavaReedSolomon testOneEncode,
        // themselves derived from the Python RS reference implementation.
        // Guards against "consistently wrong" regressions in the codec.
        ReedSolomon rs = ReedSolomon.create(5, 5);
        byte[][] shards = new byte[10][];
        shards[0] = new byte[]{0, 1};
        shards[1] = new byte[]{4, 5};
        shards[2] = new byte[]{2, 3};
        shards[3] = new byte[]{6, 7};
        shards[4] = new byte[]{8, 9};
        for (int i = 5; i < 10; i++) shards[i] = new byte[2];

        rs.encodeParity(shards, 0, 2);

        assertArrayEquals(new byte[]{12, 13}, shards[5]);
        assertArrayEquals(new byte[]{10, 11}, shards[6]);
        assertArrayEquals(new byte[]{14, 15}, shards[7]);
        assertArrayEquals(new byte[]{90, 91}, shards[8]);
        assertArrayEquals(new byte[]{94, 95}, shards[9]);
    }

    // ── exhaustive subset reconstruction ─────────────────────────────────

    @Test
    void allPossibleMissingSubsets_k3m3() {
        // For k=3, m=3 (6 total shards): test every combination of up to 3
        // missing shards. C(6,0)+C(6,1)+C(6,2)+C(6,3) = 42 decode attempts.
        ReedSolomon rs = ReedSolomon.create(3, 3);
        int sz = 16;
        byte[][] encoded = allRandomShards(6, sz);
        rs.encodeParity(encoded, 0, sz);

        for (int nMissing = 0; nMissing <= 3; nMissing++) {
            for (int[] subset : allSubsets(nMissing, 6)) {
                byte[][] test = deepCopy(encoded, 6);
                boolean[] present = new boolean[6];
                Arrays.fill(present, true);
                for (int i : subset) {
                    Arrays.fill(test[i], (byte) 0);
                    present[i] = false;
                }
                rs.decodeMissing(test, present, 0, sz);
                for (int i = 0; i < 6; i++) {
                    assertArrayEquals(encoded[i], test[i],
                            "shard " + i + " with missing=" + Arrays.toString(subset));
                }
            }
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    /** All subsets of size {@code n} from indices [0, total). */
    private static java.util.List<int[]> allSubsets(int n, int total) {
        java.util.List<int[]> result = new java.util.ArrayList<>();
        collectSubsets(n, 0, total, new int[n], 0, result);
        return result;
    }

    private static void collectSubsets(int n, int start, int total,
                                       int[] current, int depth,
                                       java.util.List<int[]> result) {
        if (depth == n) {
            result.add(current.clone());
            return;
        }
        for (int i = start; i < total - (n - depth - 1); i++) {
            current[depth] = i;
            collectSubsets(n, i + 1, total, current, depth + 1, result);
        }
    }

    private static byte[][] deepCopy(byte[][] src, int n) {
        byte[][] copy = new byte[n][];
        for (int i = 0; i < n; i++) copy[i] = src[i].clone();
        return copy;
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RNG.nextBytes(b);
        return b;
    }

    /** Allocate {@code n} shards of {@code sz} bytes each, all filled with random data. */
    private static byte[][] allRandomShards(int n, int sz) {
        byte[][] shards = new byte[n][sz];
        for (byte[] s : shards) RNG.nextBytes(s);
        return shards;
    }

    private static boolean allZero(byte[] b) {
        for (byte v : b) if (v != 0) return false;
        return true;
    }
}
