package io.q1.erasure;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Benchmarks for GF(2^8) arithmetic — the innermost hot path of the codec.
 *
 * <p>Two levels of granularity:
 * <ul>
 *   <li>{@link #multiply} — a single GF multiplication via log/antilog tables.</li>
 *   <li>{@link #mulTableApplyToBuffer} — applying one MUL_TABLE row to a full
 *       shard buffer; this is exactly what {@code encodeParity}'s inner loop does
 *       for each (parity, data-shard) pair.</li>
 * </ul>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1, jvmArgsAppend = {"--enable-preview", "--enable-native-access=ALL-UNNAMED", "--add-modules=jdk.incubator.vector"})
public class GaloisBenchmark {

    /** Shard size matching the 128 KB object / k=4 scenario (32 KB). */
    private static final int BUFFER_SIZE = 32 * 1024;

    private byte[] src;
    private byte[] dst;
    /** A fixed non-zero coefficient, representative of a parity matrix entry. */
    private byte[] mulRow;

    @Setup(Level.Trial)
    public void setup() {
        Random rng = new Random(42);
        src    = new byte[BUFFER_SIZE];
        dst    = new byte[BUFFER_SIZE];
        rng.nextBytes(src);
        // Coefficient 0x3D is a typical non-trivial matrix entry
        mulRow = Galois.MUL_TABLE[0x3D];
    }

    /**
     * Single GF(2^8) multiply via log/antilog tables.
     * Baseline for the scalar cost before any loop unrolling or SIMD.
     */
    @Benchmark
    public int multiply(Blackhole bh) {
        // Iterate all 255*255 non-zero pairs so JIT can't constant-fold,
        // then return one value to prevent dead-code elimination.
        int last = 0;
        for (int a = 1; a < 256; a++) {
            for (int b = 1; b < 256; b++) {
                last = Galois.multiply(a, b);
            }
        }
        bh.consume(last);
        return last;
    }

    /**
     * Apply one MUL_TABLE row to a {@value BUFFER_SIZE}-byte shard buffer.
     * This is the exact inner loop that {@code encodeParity} executes for each
     * (parity shard, data shard) pair — the dominant cost of encoding.
     *
     * <p>Reported as µs per shard; multiply by the number of data shards to get
     * the total encode cost for one parity shard.
     */
    @Benchmark
    public void mulTableApplyToBuffer() {
        for (int i = 0; i < BUFFER_SIZE; i++) {
            dst[i] = mulRow[src[i] & 0xFF];
        }
    }

    /**
     * XOR-accumulate variant: the second and subsequent data shards in
     * {@code encodeParity} use {@code dst[i] ^= mulRow[src[i] & 0xFF]}.
     */
    @Benchmark
    public void mulTableXorAccumulateToBuffer() {
        for (int i = 0; i < BUFFER_SIZE; i++) {
            dst[i] ^= mulRow[src[i] & 0xFF];
        }
    }
}
