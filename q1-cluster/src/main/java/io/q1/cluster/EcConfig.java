package io.q1.cluster;

/**
 * Erasure-coding configuration.
 *
 * <p>When {@link #dataShards()} is 0 (the default), EC is disabled and the
 * cluster falls back to plain {@code Q1_RF}-based replication.
 *
 * <p>Example: k=4, m=2 → 6 total shards, tolerate 2 node failures,
 * 1.5× storage overhead (vs 3× for RF=3).
 */
public record EcConfig(int dataShards, int parityShards) {

    public EcConfig {
        if (dataShards < 0)   throw new IllegalArgumentException("dataShards >= 0");
        if (parityShards < 0) throw new IllegalArgumentException("parityShards >= 0");
        if (dataShards > 0 && parityShards == 0)
            throw new IllegalArgumentException("parityShards >= 1 when EC is enabled");
    }

    /** Convenience: k=0 → EC disabled, plain replication. */
    public static EcConfig disabled() {
        return new EcConfig(0, 0);
    }

    /** True when erasure coding is active (k > 0). */
    public boolean enabled() {
        return dataShards > 0;
    }

    /** Total shards per object: k data + m parity. */
    public int totalShards() {
        return dataShards + parityShards;
    }
}
