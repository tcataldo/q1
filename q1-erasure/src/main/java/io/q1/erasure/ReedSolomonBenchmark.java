package io.q1.erasure;

import org.openjdk.jmh.annotations.*;

import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Benchmarks for Reed-Solomon encode and decode at the shard level.
 *
 * <p>Object size is fixed at 128 KB (email sweet-spot).  Three codec
 * configurations are tested, differing in how many data/parity shards the
 * object is split into:
 *
 * <pre>
 *  config    k   m   shardSize   overhead
 *  k2m1      2   1   64 KB       +50 %
 *  k4m2      4   2   32 KB       +50 %
 *  k10m4    10   4   13 KB       +40 %
 * </pre>
 *
 * <h2>Running</h2>
 * <pre>
 *   mvn package -pl q1-erasure -DskipTests
 *   java -jar q1-erasure/target/benchmarks.jar
 *
 *   # quick smoke-run (2 warm-up + 3 measurement forks):
 *   java -jar q1-erasure/target/benchmarks.jar -wi 2 -i 3 -f 1
 *
 *   # encode only, JSON output for tooling:
 *   java -jar q1-erasure/target/benchmarks.jar encode -rf json -rff results.json
 * </pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1, jvmArgsAppend = {"--enable-preview", "--enable-native-access=ALL-UNNAMED"})
public class ReedSolomonBenchmark {

    private static final int OBJECT_SIZE = 128 * 1024; // 128 KB

    /**
     * k/m pairs to benchmark.  Each drives a different shard size for the same
     * 128 KB object, exercising different trade-offs between CPU and network cost.
     */
    @Param({"2/1", "4/2", "10/4"})
    private String config;

    private ReedSolomon rs;
    private int k;
    private int m;
    private int shardSize;

    /** Fresh data shards for encode (parity slots pre-allocated, zero-filled). */
    private byte[][] encodeShards;

    /**
     * Pre-encoded shards for decode benchmarks.
     * Reset to this state before each decode invocation.
     */
    private byte[][] encodedReference;

    /** Working copy mutated by each decode invocation. */
    private byte[][] decodeShards;
    private boolean[] present;

    @Setup(Level.Trial)
    public void setup() {
        String[] parts = config.split("/");
        k = Integer.parseInt(parts[0]);
        m = Integer.parseInt(parts[1]);
        rs = ReedSolomon.create(k, m);
        shardSize = (OBJECT_SIZE + k - 1) / k;

        Random rng = new Random(42);

        // Encode shards: data filled, parity zeroed
        encodeShards = new byte[k + m][shardSize];
        for (int i = 0; i < k; i++) rng.nextBytes(encodeShards[i]);

        // Build the reference encoded set (used to reset decode state)
        encodedReference = new byte[k + m][shardSize];
        for (int i = 0; i < k; i++)
            System.arraycopy(encodeShards[i], 0, encodedReference[i], 0, shardSize);
        rs.encodeParity(encodedReference, 0, shardSize);

        decodeShards = new byte[k + m][shardSize];
        present = new boolean[k + m];
    }

    // ── encode ────────────────────────────────────────────────────────────

    /**
     * Encode: compute m parity shards from k data shards.
     * Parity buffers are overwritten each call — no per-invocation reset needed.
     */
    @Benchmark
    public void encode() {
        rs.encodeParity(encodeShards, 0, shardSize);
    }

    // ── decode ────────────────────────────────────────────────────────────

    /**
     * Degraded read: shard 0 (first data shard) is lost.
     * This is the most common real-world scenario (one disk/node failure).
     */
    @Benchmark
    public void decode_oneMissing() {
        resetDecodeShards();
        Arrays.fill(decodeShards[0], (byte) 0);
        present[0] = false;
        rs.decodeMissing(decodeShards, present, 0, shardSize);
    }

    /**
     * Two-failure scenario: shards 0 and k (first data + first parity) lost.
     * Tests the matrix inversion path with a more complex decode matrix.
     */
    @Benchmark
    public void decode_twoMissing() {
        resetDecodeShards();
        Arrays.fill(decodeShards[0], (byte) 0);
        Arrays.fill(decodeShards[k], (byte) 0);
        present[0] = false;
        present[k] = false;
        rs.decodeMissing(decodeShards, present, 0, shardSize);
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private void resetDecodeShards() {
        for (int i = 0; i < k + m; i++)
            System.arraycopy(encodedReference[i], 0, decodeShards[i], 0, shardSize);
        Arrays.fill(present, true);
    }
}
