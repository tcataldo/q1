package io.q1.erasure;

/**
 * Reed-Solomon erasure coding over GF(2^8).
 *
 * <p>API is intentionally compatible with the Backblaze JavaReedSolomon library:
 * <ul>
 *   <li>{@link #create(int, int)} — factory method.</li>
 *   <li>{@link #encodeParity(byte[][], int, int)} — fill parity shards in-place.</li>
 *   <li>{@link #decodeMissing(byte[][], boolean[], int, int)} — reconstruct
 *       missing shards in-place given any k present shards.</li>
 * </ul>
 *
 * <p>Uses a systematic Vandermonde-based generator matrix:
 * the top {@code k} rows form the identity matrix so data shards pass through
 * unchanged, and the bottom {@code m} rows define the parity.
 *
 * <p>Implementation based on the public-domain Backblaze design.
 */
public final class ReedSolomon {

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

        // parity[i] = sum over j of encodeMatrix[dataShards+i][j] * dataShards[j]
        for (int p = 0; p < parityShards; p++) {
            byte[] parityRow = shards[dataShards + p];
            boolean first = true;
            for (int d = 0; d < dataShards; d++) {
                int coef = encodeMatrix.get(dataShards + p, d);
                byte[] src = shards[d];
                byte[] mulRow = Galois.MUL_TABLE[coef];
                if (first) {
                    for (int i = offset; i < offset + byteCount; i++) {
                        parityRow[i] = mulRow[src[i] & 0xFF];
                    }
                    first = false;
                } else {
                    for (int i = offset; i < offset + byteCount; i++) {
                        parityRow[i] ^= mulRow[src[i] & 0xFF];
                    }
                }
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

        // Collect indices of present shards (take first k)
        int[] presentIndices = new int[dataShards];
        int count = 0;
        for (int i = 0; i < totalShards && count < dataShards; i++) {
            if (shardPresent[i]) presentIndices[count++] = i;
        }
        if (count < dataShards) {
            throw new IllegalArgumentException(
                    "Not enough shards: need " + dataShards + ", got " + count);
        }

        // Build the k×k submatrix from the rows corresponding to present shards
        Matrix subMatrix = new Matrix(dataShards, dataShards);
        for (int r = 0; r < dataShards; r++) {
            for (int c = 0; c < dataShards; c++) {
                subMatrix.set(r, c, encodeMatrix.get(presentIndices[r], c));
            }
        }

        Matrix decodeMatrix = subMatrix.invert();

        // Collect k present shards as inputs
        byte[][] subShards = new byte[dataShards][];
        for (int r = 0; r < dataShards; r++) {
            subShards[r] = shards[presentIndices[r]];
        }

        // Decode into fresh buffers to avoid aliasing between inputs and outputs.
        // (e.g. shards[1] may be both an input shard and an output shard,
        //  so writing to it before reading it would corrupt the computation.)
        int shardLen = subShards[0].length;
        byte[][] decoded = new byte[dataShards][shardLen];
        multiplyRows(decodeMatrix, subShards, decoded, offset, byteCount);

        // Copy decoded data shards back into shards[]
        for (int d = 0; d < dataShards; d++) {
            System.arraycopy(decoded[d], offset, shards[d], offset, byteCount);
        }

        // Re-encode parity for any missing parity shards
        encodeParity(shards, offset, byteCount);
    }

    // ── private helpers ───────────────────────────────────────────────────

    /**
     * For each output row {@code r}: output[r][i] = sum_c(matrix[r][c] * inputs[c][i]).
     */
    private static void multiplyRows(Matrix matrix, byte[][] inputs, byte[][] outputs,
                                     int offset, int byteCount) {
        int numRows = matrix.rows();
        int numCols = matrix.cols();
        for (int r = 0; r < numRows; r++) {
            byte[] out = outputs[r];
            boolean first = true;
            for (int c = 0; c < numCols; c++) {
                int coef = matrix.get(r, c);
                byte[] src = inputs[c];
                byte[] mulRow = Galois.MUL_TABLE[coef];
                if (first) {
                    for (int i = offset; i < offset + byteCount; i++) {
                        out[i] = mulRow[src[i] & 0xFF];
                    }
                    first = false;
                } else {
                    for (int i = offset; i < offset + byteCount; i++) {
                        out[i] ^= mulRow[src[i] & 0xFF];
                    }
                }
            }
        }
    }

    /**
     * Build the (k+m) × k generator matrix.
     *
     * <p>Start from a Vandermonde matrix V of size (k+m) × k.
     * Let V_top be its top k rows; compute V_top^-1.
     * The generator matrix is G = V_top^-1 × V, which makes the top k rows
     * the identity (data shards pass through unchanged).
     */
    private static Matrix buildEncodeMatrix(int k, int m) {
        // V is (k+m) × k; G = V · V_top^-1 makes the first k rows the identity
        Matrix vand   = Matrix.vandermonde(k + m, k);
        Matrix top    = vand.submatrix(0, 0, k, k);      // k × k
        Matrix topInv = top.invert();                      // k × k
        return vand.times(topInv);                         // (k+m)×k · k×k = (k+m)×k
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
