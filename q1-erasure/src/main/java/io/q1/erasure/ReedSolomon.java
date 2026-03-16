// Copyright 2015, Backblaze, Inc.  All rights reserved.
// Licensed under the MIT License. See LICENSE in the project root for details.

package io.q1.erasure;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * Reed-Solomon erasure coding over GF(2^8).
 *
 * <p>The hot path — multiplying every byte of a shard by a GF(2^8) coefficient — uses
 * the <b>nibble-split</b> technique to enable SIMD execution:
 *
 * <pre>
 *   GF.multiply(c, x) = GF.multiply(c, x_lo) XOR GF.multiply(c, x_hi &lt;&lt; 4)
 * </pre>
 *
 * <p>Each half is a 16-entry table lookup, which the Java Vector API compiles to
 * {@code vpshufb} on AVX2 hardware (32 bytes/cycle), versus an un-vectorisable
 * 256-entry scatter/gather in the naïve approach.
 *
 * <p>Per-coefficient nibble tables ({@link #LO_NIBBLES}, {@link #HI_NIBBLES}) are
 * pre-computed once at class load, replicated to fill the preferred SIMD register
 * width ({@link #SPECIES}).
 */
public final class ReedSolomon {

    // ── Vector API setup ──────────────────────────────────────────────────

    private static final VectorSpecies<Byte> SPECIES = ByteVector.SPECIES_PREFERRED;
    private static final int                 VEC_LEN = SPECIES.length();

    /**
     * Nibble lookup tables for all 256 GF(2^8) coefficients, replicated to
     * {@link #VEC_LEN} so each table exactly fills one SIMD register.
     *
     * <pre>
     *   LO_NIBBLES[c][j] = GF.multiply(c,  j &amp; 0x0F)
     *   HI_NIBBLES[c][j] = GF.multiply(c, (j &amp; 0x0F) &lt;&lt; 4)
     * </pre>
     *
     * Memory: 256 × VEC_LEN × 2 bytes (16 KB for AVX2 / 32 KB for AVX-512).
     */
    private static final byte[][] LO_NIBBLES = new byte[256][VEC_LEN];
    private static final byte[][] HI_NIBBLES = new byte[256][VEC_LEN];

    static {
        for (int coef = 0; coef < 256; coef++) {
            for (int j = 0; j < VEC_LEN; j++) {
                LO_NIBBLES[coef][j] = (byte) Galois.multiply(coef,  j & 0x0F);
                HI_NIBBLES[coef][j] = (byte) Galois.multiply(coef, (j & 0x0F) << 4);
            }
        }
    }

    // ── codec state ───────────────────────────────────────────────────────

    private final int    dataShards;
    private final int    parityShards;
    private final int    totalShards;
    private final Matrix encodeMatrix;   // (k+m) × k generator matrix

    private ReedSolomon(int dataShards, int parityShards) {
        this.dataShards   = dataShards;
        this.parityShards = parityShards;
        this.totalShards  = dataShards + parityShards;
        this.encodeMatrix = buildEncodeMatrix(dataShards, parityShards);
    }

    public static ReedSolomon create(int dataShards, int parityShards) {
        if (dataShards <= 0)   throw new IllegalArgumentException("dataShards must be > 0");
        if (parityShards <= 0) throw new IllegalArgumentException("parityShards must be > 0");
        return new ReedSolomon(dataShards, parityShards);
    }

    // ── encoding ──────────────────────────────────────────────────────────

    /**
     * Fill the parity shards (indices {@code dataShards..totalShards-1}) in-place.
     *
     * @param shards    {@code byte[totalShards][shardSize]} — data shards must be populated.
     * @param offset    starting byte offset within each shard.
     * @param byteCount number of bytes to process.
     */
    public void encodeParity(byte[][] shards, int offset, int byteCount) {
        checkShards(shards, offset, byteCount);

        for (int p = 0; p < parityShards; p++) {
            byte[] parityRow = shards[dataShards + p];
            for (int d = 0; d < dataShards; d++) {
                int coef = encodeMatrix.get(dataShards + p, d);
                mulRow(coef, shards[d], parityRow, offset, byteCount, /* xorAcc= */ d > 0);
            }
        }
    }

    // ── decoding ──────────────────────────────────────────────────────────

    /**
     * Reconstruct missing shards in-place.  At least {@code dataShards} entries
     * in {@code shardPresent} must be {@code true}.
     *
     * @param shards       {@code byte[totalShards][shardSize]} — missing shards
     *                     must be pre-allocated (zero-filled).
     * @param shardPresent which shards are available.
     * @param offset       byte offset within each shard.
     * @param byteCount    number of bytes to process.
     */
    public void decodeMissing(byte[][] shards, boolean[] shardPresent,
                               int offset, int byteCount) {
        checkShards(shards, offset, byteCount);

        int[] presentIndices = new int[dataShards];
        int count = 0;
        for (int i = 0; i < totalShards && count < dataShards; i++) {
            if (shardPresent[i]) presentIndices[count++] = i;
        }
        if (count < dataShards) {
            throw new IllegalArgumentException(
                    "Not enough shards: need " + dataShards + ", got " + count);
        }

        Matrix subMatrix = new Matrix(dataShards, dataShards);
        for (int r = 0; r < dataShards; r++) {
            for (int c = 0; c < dataShards; c++) {
                subMatrix.set(r, c, encodeMatrix.get(presentIndices[r], c));
            }
        }
        Matrix decodeMatrix = subMatrix.invert();

        byte[][] subShards = new byte[dataShards][];
        for (int r = 0; r < dataShards; r++) {
            subShards[r] = shards[presentIndices[r]];
        }

        int shardLen = subShards[0].length;
        byte[][] decoded = new byte[dataShards][shardLen];
        multiplyRows(decodeMatrix, subShards, decoded, offset, byteCount);

        for (int d = 0; d < dataShards; d++) {
            System.arraycopy(decoded[d], offset, shards[d], offset, byteCount);
        }

        encodeParity(shards, offset, byteCount);
    }

    // ── private helpers ───────────────────────────────────────────────────

    /**
     * For each output row {@code r}: {@code output[r][i] = Σ_c matrix[r][c] * inputs[c][i]}.
     */
    private static void multiplyRows(Matrix matrix, byte[][] inputs, byte[][] outputs,
                                     int offset, int byteCount) {
        int numRows = matrix.rows();
        int numCols = matrix.cols();
        for (int r = 0; r < numRows; r++) {
            for (int c = 0; c < numCols; c++) {
                mulRow(matrix.get(r, c), inputs[c], outputs[r], offset, byteCount, /* xorAcc= */ c > 0);
            }
        }
    }

    /**
     * Vectorised GF(2^8) multiply-accumulate on a byte array.
     *
     * <p>For each index {@code i} in {@code [offset, offset+byteCount)}:
     * <pre>
     *   dst[i]  = GF.multiply(coef, src[i])         // xorAcc = false
     *   dst[i] ^= GF.multiply(coef, src[i])         // xorAcc = true
     * </pre>
     *
     * <p><b>Nibble split</b>: splits each source byte into low and high 4-bit nibbles
     * and performs two 16-entry SIMD lookups (→ {@code vpshufb} on AVX2), processing
     * {@link #VEC_LEN} bytes per iteration.  A scalar loop handles the tail.
     */
    private static void mulRow(int coef, byte[] src, byte[] dst,
                                int offset, int byteCount, boolean xorAcc) {
        final ByteVector loTbl      = ByteVector.fromArray(SPECIES, LO_NIBBLES[coef], 0);
        final ByteVector hiTbl      = ByteVector.fromArray(SPECIES, HI_NIBBLES[coef], 0);
        final ByteVector nibbleMask = ByteVector.broadcast(SPECIES, (byte) 0x0F);
        final int        bound      = offset + SPECIES.loopBound(byteCount);

        // In JDK 25 Vector API:
        //   - no ByteVector.xor(v) shorthand → use lanewise(VectorOperators.XOR, v)
        //   - no 0-arg toShuffle()           → use indices.selectFrom(table)
        //     where receiver = index vector, argument = table vector
        if (!xorAcc) {
            for (int i = offset; i < bound; i += VEC_LEN) {
                final ByteVector data    = ByteVector.fromArray(SPECIES, src, i);
                final ByteVector lo      = data.and(nibbleMask);
                final ByteVector hi      = data.lanewise(VectorOperators.LSHR, 4).and(nibbleMask);
                lo.selectFrom(loTbl)
                  .lanewise(VectorOperators.XOR, hi.selectFrom(hiTbl))
                  .intoArray(dst, i);
            }
        } else {
            for (int i = offset; i < bound; i += VEC_LEN) {
                final ByteVector data    = ByteVector.fromArray(SPECIES, src, i);
                final ByteVector lo      = data.and(nibbleMask);
                final ByteVector hi      = data.lanewise(VectorOperators.LSHR, 4).and(nibbleMask);
                final ByteVector product = lo.selectFrom(loTbl)
                                             .lanewise(VectorOperators.XOR, hi.selectFrom(hiTbl));
                ByteVector.fromArray(SPECIES, dst, i)
                           .lanewise(VectorOperators.XOR, product)
                           .intoArray(dst, i);
            }
        }

        // Scalar tail for remaining bytes (< VEC_LEN)
        final byte[] fullRow = Galois.MUL_TABLE[coef];
        for (int i = bound; i < offset + byteCount; i++) {
            final byte v = fullRow[src[i] & 0xFF];
            dst[i] = xorAcc ? (byte) (dst[i] ^ v) : v;
        }
    }

    private static Matrix buildEncodeMatrix(int k, int m) {
        Matrix vand   = Matrix.vandermonde(k + m, k);
        Matrix top    = vand.submatrix(0, 0, k, k);
        Matrix topInv = top.invert();
        return vand.times(topInv);
    }

    private void checkShards(byte[][] shards, int offset, int byteCount) {
        if (shards.length != totalShards) {
            throw new IllegalArgumentException(
                    "Expected " + totalShards + " shards, got " + shards.length);
        }
        for (int i = 0; i < totalShards; i++) {
            if (shards[i] != null && shards[i].length < offset + byteCount) {
                throw new IllegalArgumentException(
                        "Shard " + i + " is too short: " + shards[i].length
                                + " < " + (offset + byteCount));
            }
        }
    }
}
