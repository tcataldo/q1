package io.q1.cluster.erasure;

/**
 * Arithmetic in GF(2^8) using the primitive polynomial
 * x^8 + x^4 + x^3 + x^2 + 1 (0x11D).
 *
 * <p>Based on the public-domain Backblaze JavaReedSolomon design.
 * Log/antilog tables give O(1) multiply and divide.
 */
final class Galois {

    static final int FIELD_SIZE       = 256;
    static final int FIELD_GENERATOR  = 0x11D; // x^8+x^4+x^3+x^2+1

    /** log[i] = e such that GENERATOR^e == i (undefined for i==0) */
    static final int[] LOG_TABLE = new int[FIELD_SIZE];

    /** exp[e] = GENERATOR^e (mod the primitive polynomial) */
    static final int[] EXP_TABLE = new int[FIELD_SIZE * 2];

    static {
        int x = 1;
        for (int i = 0; i < FIELD_SIZE - 1; i++) {
            EXP_TABLE[i] = x;
            LOG_TABLE[x] = i;
            x <<= 1;
            if (x >= FIELD_SIZE) x ^= FIELD_GENERATOR;
        }
        // Duplicate table so we can index without modular reduction in hot paths
        for (int i = FIELD_SIZE - 1; i < FIELD_SIZE * 2; i++) {
            EXP_TABLE[i] = EXP_TABLE[i - (FIELD_SIZE - 1)];
        }
    }

    /** a * b in GF(2^8). */
    static int multiply(int a, int b) {
        if (a == 0 || b == 0) return 0;
        return EXP_TABLE[LOG_TABLE[a] + LOG_TABLE[b]];
    }

    /** a / b in GF(2^8); b must be non-zero. */
    static int divide(int a, int b) {
        if (b == 0) throw new ArithmeticException("Division by zero in GF(2^8)");
        if (a == 0) return 0;
        int logResult = LOG_TABLE[a] - LOG_TABLE[b];
        if (logResult < 0) logResult += FIELD_SIZE - 1;
        return EXP_TABLE[logResult];
    }

    /** additive inverse == a (XOR with 0 = identity in GF(2)); kept for clarity. */
    static int add(int a, int b) { return a ^ b; }

    static int subtract(int a, int b) { return a ^ b; }

    /** Precomputed full 256x256 multiply table for fast row operations. */
    static final byte[][] MUL_TABLE = buildMulTable();

    private static byte[][] buildMulTable() {
        byte[][] t = new byte[FIELD_SIZE][FIELD_SIZE];
        for (int a = 0; a < FIELD_SIZE; a++) {
            for (int b = 0; b < FIELD_SIZE; b++) {
                t[a][b] = (byte) multiply(a, b);
            }
        }
        return t;
    }

    private Galois() {}
}
