package io.q1.erasure;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for matrix operations over GF(2^8).
 */
class MatrixTest {

    // ── identity ──────────────────────────────────────────────────────────

    @Test
    void identityHasOnesOnDiagonalAndZerosElsewhere() {
        for (int n = 1; n <= 5; n++) {
            Matrix id = Matrix.identity(n);
            assertEquals(n, id.rows());
            assertEquals(n, id.cols());
            for (int r = 0; r < n; r++)
                for (int c = 0; c < n; c++)
                    assertEquals(r == c ? 1 : 0, id.get(r, c),
                            "identity(" + n + ")[" + r + "][" + c + "]");
        }
    }

    @Test
    void timesIdentityIsNoOp() {
        Matrix m = Matrix.vandermonde(4, 4);
        Matrix id = Matrix.identity(4);
        assertMatrixEquals(m, m.times(id));
        assertMatrixEquals(m, id.times(m));
    }

    // ── vandermonde ───────────────────────────────────────────────────────

    @Test
    void vandermonde_rowZeroIsOneFollowedByZeros() {
        // r=0: powers of 0 → [0^0=1, 0^1=0, 0^2=0, ...]
        Matrix v = Matrix.vandermonde(4, 4);
        assertEquals(1, v.get(0, 0));
        for (int c = 1; c < 4; c++)
            assertEquals(0, v.get(0, c), "vandermonde[0][" + c + "] should be 0");
    }

    @Test
    void vandermonde_rowOneIsAllOnes() {
        // r=1: powers of 1 → [1, 1, 1, ...]
        Matrix v = Matrix.vandermonde(4, 4);
        for (int c = 0; c < 4; c++)
            assertEquals(1, v.get(1, c), "vandermonde[1][" + c + "] should be 1");
    }

    @Test
    void vandermonde_rowTwoIsPowersOfTwo() {
        // r=2: [2^0=1, 2^1=2, 2^2=4, 2^3=8] (all < 256, no GF wrap needed)
        Matrix v = Matrix.vandermonde(3, 4);
        assertEquals(1, v.get(2, 0));
        assertEquals(2, v.get(2, 1));
        assertEquals(4, v.get(2, 2));
        assertEquals(8, v.get(2, 3));
    }

    // ── inversion ─────────────────────────────────────────────────────────

    @Test
    void invertIdentityIsIdentity() {
        for (int n = 1; n <= 5; n++) {
            Matrix id = Matrix.identity(n);
            assertMatrixEquals(id, id.invert());
        }
    }

    @Test
    void mTimesItsInverseIsIdentity() {
        // Top k×k submatrix of a Vandermonde matrix must be invertible
        for (int k = 2; k <= 5; k++) {
            Matrix top = Matrix.vandermonde(k + 2, k).submatrix(0, 0, k, k);
            Matrix inv = top.invert();
            Matrix product = top.times(inv);
            assertMatrixEquals(Matrix.identity(k), product,
                    "M * M^-1 should be I for k=" + k);
        }
    }

    @Test
    void invertNonSquareThrows() {
        assertThrows(IllegalArgumentException.class, () -> new Matrix(3, 2).invert());
        assertThrows(IllegalArgumentException.class, () -> new Matrix(2, 3).invert());
    }

    @Test
    void invertZeroMatrixThrows() {
        // All-zero 2×2 matrix is singular
        assertThrows(IllegalArgumentException.class, () -> new Matrix(2, 2).invert());
    }

    @Test
    void knownInverseResult_3x3() {
        // Cross-validated against Backblaze JavaReedSolomon MatrixTest
        // invert([[56,23,98],[3,100,200],[45,201,123]]) == [[175,133,33],[130,13,245],[112,35,126]]
        Matrix m = matrixOf(new int[][]{
                {56,  23,  98},
                { 3, 100, 200},
                {45, 201, 123}
        });
        Matrix inv = m.invert();
        assertMatrixEquals(matrixOf(new int[][]{
                {175, 133,  33},
                {130,  13, 245},
                {112,  35, 126}
        }), inv, "known 3x3 inverse");
        // And M * M^-1 == I
        assertMatrixEquals(Matrix.identity(3), m.times(inv), "M * M^-1 must be I");
    }

    @Test
    void knownInverseResult_5x5() {
        // Cross-validated against Backblaze JavaReedSolomon MatrixTest (inverse2)
        Matrix m = matrixOf(new int[][]{
                {1, 0, 0, 0, 0},
                {0, 1, 0, 0, 0},
                {0, 0, 0, 1, 0},
                {0, 0, 0, 0, 1},
                {7, 7, 6, 6, 1}
        });
        Matrix inv = m.invert();
        assertMatrixEquals(matrixOf(new int[][]{
                {  1,   0,   0,   0,   0},
                {  0,   1,   0,   0,   0},
                {123, 123,   1, 122, 122},
                {  0,   0,   1,   0,   0},
                {  0,   0,   0,   1,   0}
        }), inv, "known 5x5 inverse");
        assertMatrixEquals(Matrix.identity(5), m.times(inv), "M * M^-1 must be I");
    }

    @Test
    void knownMultiplyResult_2x2() {
        // GF(2^8) multiply of [[1,2],[3,4]] * [[5,6],[7,8]]
        // Cross-validated against Backblaze: result == [[11,22],[19,42]]
        Matrix m1 = matrixOf(new int[][]{{1, 2}, {3, 4}});
        Matrix m2 = matrixOf(new int[][]{{5, 6}, {7, 8}});
        assertMatrixEquals(matrixOf(new int[][]{{11, 22}, {19, 42}}), m1.times(m2),
                "GF(2^8) matrix multiply");
    }

    @Test
    void invertSingularMatrixThrows() {
        // Matrix with two identical rows is singular
        Matrix m = new Matrix(2, 2);
        m.set(0, 0, 5); m.set(0, 1, 3);
        m.set(1, 0, 5); m.set(1, 1, 3); // same row
        assertThrows(IllegalArgumentException.class, m::invert);
    }

    // ── submatrix ─────────────────────────────────────────────────────────

    @Test
    void submatrixExtractsCorrectRegion() {
        Matrix m = new Matrix(4, 4);
        int val = 1;
        for (int r = 0; r < 4; r++)
            for (int c = 0; c < 4; c++)
                m.set(r, c, val++);

        Matrix sub = m.submatrix(1, 1, 3, 3);
        assertEquals(2, sub.rows());
        assertEquals(2, sub.cols());
        assertEquals(m.get(1, 1), sub.get(0, 0));
        assertEquals(m.get(1, 2), sub.get(0, 1));
        assertEquals(m.get(2, 1), sub.get(1, 0));
        assertEquals(m.get(2, 2), sub.get(1, 1));
    }

    @Test
    void submatrixSingleCell() {
        Matrix m = Matrix.identity(3);
        Matrix sub = m.submatrix(1, 1, 2, 2);
        assertEquals(1, sub.rows());
        assertEquals(1, sub.cols());
        assertEquals(1, sub.get(0, 0));
    }

    // ── augment ───────────────────────────────────────────────────────────

    @Test
    void augmentWithIdentityProducesCorrectDimensions() {
        Matrix a = Matrix.vandermonde(3, 3);
        Matrix aug = a.augment(Matrix.identity(3));
        assertEquals(3, aug.rows());
        assertEquals(6, aug.cols());
    }

    @Test
    void augmentPreservesLeftAndRightHalves() {
        Matrix a = Matrix.vandermonde(3, 3);
        Matrix id = Matrix.identity(3);
        Matrix aug = a.augment(id);

        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                assertEquals(a.get(r, c),  aug.get(r, c),     "left half");
                assertEquals(id.get(r, c), aug.get(r, 3 + c), "right half");
            }
        }
    }

    @Test
    void augmentRowMismatchThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new Matrix(2, 3).augment(new Matrix(3, 3)));
    }

    // ── multiply ──────────────────────────────────────────────────────────

    @Test
    void timesDimensionMismatchThrows() {
        Matrix a = new Matrix(2, 3);
        Matrix b = new Matrix(2, 3); // a.cols=3 != b.rows=2
        assertThrows(IllegalArgumentException.class, () -> a.times(b));
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private static Matrix matrixOf(int[][] values) {
        Matrix m = new Matrix(values.length, values[0].length);
        for (int r = 0; r < values.length; r++)
            for (int c = 0; c < values[0].length; c++)
                m.set(r, c, values[r][c]);
        return m;
    }

    private static void assertMatrixEquals(Matrix expected, Matrix actual) {
        assertMatrixEquals(expected, actual, "");
    }

    private static void assertMatrixEquals(Matrix expected, Matrix actual, String msg) {
        assertEquals(expected.rows(), actual.rows(), msg + " rows");
        assertEquals(expected.cols(), actual.cols(), msg + " cols");
        for (int r = 0; r < expected.rows(); r++)
            for (int c = 0; c < expected.cols(); c++)
                assertEquals(expected.get(r, c), actual.get(r, c),
                        msg + " [" + r + "][" + c + "]");
    }
}
