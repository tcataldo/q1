package io.q1.cluster.erasure;

/**
 * Matrix over GF(2^8).
 *
 * <p>Used internally by {@link ReedSolomon} to compute the generator matrix
 * and its inverse during decoding.
 */
final class Matrix {

    private final int rows;
    private final int cols;
    private final byte[][] data;

    Matrix(int rows, int cols) {
        this.rows = rows;
        this.cols = cols;
        this.data = new byte[rows][cols];
    }

    int rows() { return rows; }
    int cols() { return cols; }

    int get(int r, int c) { return data[r][c] & 0xFF; }

    void set(int r, int c, int v) { data[r][c] = (byte) v; }

    /** Identity matrix of size n×n. */
    static Matrix identity(int n) {
        Matrix m = new Matrix(n, n);
        for (int i = 0; i < n; i++) m.set(i, i, 1);
        return m;
    }

    /**
     * Vandermonde matrix of size {@code rows × cols}.
     * Entry[r][c] = r^c in GF(2^8), with row 0 being all-ones.
     */
    static Matrix vandermonde(int rows, int cols) {
        Matrix m = new Matrix(rows, cols);
        for (int r = 0; r < rows; r++) {
            int x = r;
            for (int c = 0; c < cols; c++) {
                // x^c: start at 1 for c=0
                int power = 1;
                for (int p = 0; p < c; p++) power = Galois.multiply(power, x);
                m.set(r, c, power);
            }
        }
        return m;
    }

    /** Matrix multiply: this × right. */
    Matrix times(Matrix right) {
        if (cols != right.rows) throw new IllegalArgumentException(
                "Matrix size mismatch: " + rows + "×" + cols + " * " + right.rows + "×" + right.cols);
        Matrix result = new Matrix(rows, right.cols);
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < right.cols; c++) {
                int val = 0;
                for (int k = 0; k < cols; k++) {
                    val ^= Galois.multiply(get(r, k), right.get(k, c));
                }
                result.set(r, c, val);
            }
        }
        return result;
    }

    /** Augment with identity (used to compute inverse via Gaussian elimination). */
    Matrix augment(Matrix right) {
        if (rows != right.rows) throw new IllegalArgumentException("Row count mismatch");
        Matrix result = new Matrix(rows, cols + right.cols);
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++)       result.set(r, c,           get(r, c));
            for (int c = 0; c < right.cols; c++) result.set(r, cols + c, right.get(r, c));
        }
        return result;
    }

    /** Submatrix: rows [r0,r1), cols [c0,c1). */
    Matrix submatrix(int r0, int c0, int r1, int c1) {
        Matrix result = new Matrix(r1 - r0, c1 - c0);
        for (int r = r0; r < r1; r++)
            for (int c = c0; c < c1; c++)
                result.set(r - r0, c - c0, get(r, c));
        return result;
    }

    /**
     * Gaussian elimination to find the inverse of this matrix.
     * @throws IllegalArgumentException if the matrix is not invertible.
     */
    Matrix invert() {
        if (rows != cols) throw new IllegalArgumentException("Only square matrices can be inverted");
        int n = rows;
        Matrix work = augment(identity(n));

        // Forward elimination
        for (int col = 0; col < n; col++) {
            // Pivot: find a non-zero entry in this column at or below the diagonal
            int pivot = -1;
            for (int r = col; r < n; r++) {
                if (work.get(r, col) != 0) { pivot = r; break; }
            }
            if (pivot < 0) throw new IllegalArgumentException("Matrix is not invertible");

            work.swapRows(col, pivot);

            // Scale pivot row so diagonal entry == 1
            int scale = Galois.divide(1, work.get(col, col));
            for (int c = 0; c < work.cols; c++) {
                work.set(col, c, Galois.multiply(work.get(col, c), scale));
            }

            // Eliminate column entries above and below the pivot
            for (int r = 0; r < n; r++) {
                if (r == col) continue;
                int factor = work.get(r, col);
                if (factor == 0) continue;
                for (int c = 0; c < work.cols; c++) {
                    work.set(r, c, work.get(r, c) ^ Galois.multiply(factor, work.get(col, c)));
                }
            }
        }

        // Right half of the augmented matrix is the inverse
        return work.submatrix(0, n, n, 2 * n);
    }

    private void swapRows(int a, int b) {
        byte[] tmp = data[a]; data[a] = data[b]; data[b] = tmp;
    }

    /** Extract row {@code r} as a 1×cols matrix. */
    Matrix row(int r) {
        return submatrix(r, 0, r + 1, cols);
    }
}
