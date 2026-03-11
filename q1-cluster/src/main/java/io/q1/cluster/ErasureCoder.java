package io.q1.cluster;

import io.q1.erasure.ReedSolomon;

import java.io.IOException;

/**
 * Encodes and decodes objects using Reed-Solomon erasure coding via the
 * <a href="https://github.com/Backblaze/JavaReedSolomon">Backblaze JavaReedSolomon</a>
 * library.
 *
 * <h3>Encoding</h3>
 * An object of arbitrary size is:
 * <ol>
 *   <li>Padded to the nearest multiple of {@code k} bytes.</li>
 *   <li>Split into {@code k} equal-size data shards.</li>
 *   <li>Passed to {@link ReedSolomon#encodeParity} which fills the {@code m}
 *       parity shards in-place.</li>
 * </ol>
 * Result: {@code byte[k+m][shardSize]}.
 *
 * <h3>Decoding</h3>
 * Given a {@code byte[k+m][]} where missing shards are {@code null}:
 * <ol>
 *   <li>At least {@code k} shards must be present.</li>
 *   <li>Missing shards are allocated as zero arrays.</li>
 *   <li>{@link ReedSolomon#decodeMissing} reconstructs them in-place.</li>
 *   <li>Data shards are concatenated and the original size is applied to
 *       strip padding.</li>
 * </ol>
 */
public final class ErasureCoder {

    private final ReedSolomon codec;
    private final int k;
    private final int m;

    public ErasureCoder(EcConfig cfg) {
        this.k     = cfg.dataShards();
        this.m     = cfg.parityShards();
        this.codec = ReedSolomon.create(k, m);
    }

    /**
     * Encode {@code data} into {@code k+m} equal-size shards.
     *
     * @return {@code byte[k+m][shardSize]} — shards 0..k-1 are data,
     *         shards k..k+m-1 are Reed-Solomon parity.
     */
    public byte[][] encode(byte[] data) {
        // shardSize = ceil(len / k)
        int shardSize = (data.length + k - 1) / k;

        byte[][] shards = new byte[k + m][shardSize];

        // Copy data into the first k shards (last shard may be zero-padded)
        for (int i = 0; i < k; i++) {
            int start = i * shardSize;
            int len   = Math.min(shardSize, data.length - start);
            if (len > 0) {
                System.arraycopy(data, start, shards[i], 0, len);
            }
            // remaining bytes already zero (Java array initialisation)
        }

        codec.encodeParity(shards, 0, shardSize);
        return shards;
    }

    /**
     * Reconstruct the original object from at least {@code k} shards.
     *
     * @param shards       {@code byte[k+m][]} — {@code null} entries mean missing.
     * @param originalSize size of the original object before padding.
     * @return the original bytes, without padding.
     * @throws IOException if fewer than {@code k} shards are present.
     */
    public byte[] decode(byte[][] shards, long originalSize) throws IOException {
        if (shards.length != k + m) {
            throw new IllegalArgumentException(
                    "Expected " + (k + m) + " shard slots, got " + shards.length);
        }

        // Determine shard size from the first non-null shard
        int shardSize = -1;
        int presentCount = 0;
        for (byte[] s : shards) {
            if (s != null) {
                shardSize = s.length;
                presentCount++;
            }
        }
        if (presentCount < k) {
            throw new IOException(
                    "Cannot decode: only " + presentCount + " of " + k + " required shards present");
        }
        if (shardSize < 0) {
            throw new IOException("All shards are null");
        }

        boolean[] present = new boolean[k + m];
        for (int i = 0; i < k + m; i++) {
            present[i] = shards[i] != null;
            if (shards[i] == null) {
                shards[i] = new byte[shardSize]; // allocate for decodeMissing
            }
        }

        if (presentCount < k + m) {
            codec.decodeMissing(shards, present, 0, shardSize);
        }

        // Reassemble: concatenate the k data shards, then trim to originalSize
        byte[] result = new byte[(int) originalSize];
        int pos = 0;
        for (int i = 0; i < k && pos < originalSize; i++) {
            int toCopy = (int) Math.min(shardSize, originalSize - pos);
            System.arraycopy(shards[i], 0, result, pos, toCopy);
            pos += toCopy;
        }
        return result;
    }

    public int dataShards()   { return k; }
    public int parityShards() { return m; }
    public int totalShards()  { return k + m; }
}
