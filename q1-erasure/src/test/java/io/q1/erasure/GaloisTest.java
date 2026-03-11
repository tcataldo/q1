package io.q1.erasure;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exhaustive tests for GF(2^8) field arithmetic.
 * These lock down the mathematical properties that ReedSolomon relies on.
 */
class GaloisTest {

    // ── zero / identity ───────────────────────────────────────────────────

    @Test
    void multiplyByZeroIsZero() {
        for (int a = 0; a < 256; a++) {
            assertEquals(0, Galois.multiply(0, a), "0 * " + a);
            assertEquals(0, Galois.multiply(a, 0), a + " * 0");
        }
    }

    @Test
    void multiplyByOneIsIdentity() {
        for (int a = 0; a < 256; a++) {
            assertEquals(a, Galois.multiply(1, a), "1 * " + a);
            assertEquals(a, Galois.multiply(a, 1), a + " * 1");
        }
    }

    @Test
    void divideByItselfIsOne() {
        for (int a = 1; a < 256; a++) {
            assertEquals(1, Galois.divide(a, a), a + " / " + a);
        }
    }

    @Test
    void divideZeroByAnythingIsZero() {
        for (int b = 1; b < 256; b++) {
            assertEquals(0, Galois.divide(0, b), "0 / " + b);
        }
    }

    // ── inverses ──────────────────────────────────────────────────────────

    @Test
    void multiplyThenDivideRoundTrips() {
        // (a * b) / b == a for all non-zero a, b
        for (int a = 1; a < 256; a++) {
            for (int b = 1; b < 256; b++) {
                int product = Galois.multiply(a, b);
                assertEquals(a, Galois.divide(product, b),
                        "(" + a + " * " + b + ") / " + b + " should be " + a);
            }
        }
    }

    @Test
    void addIsItsOwnInverse() {
        // In GF(2^8) char=2, so a + a = 0 for all a
        for (int a = 0; a < 256; a++) {
            assertEquals(0, Galois.add(a, a), a + " + " + a + " should be 0");
        }
    }

    @Test
    void addEqualsSubtract() {
        // In GF(2^8) addition and subtraction are the same (both are XOR)
        for (int a = 0; a < 256; a++) {
            for (int b = 0; b < 256; b++) {
                assertEquals(Galois.add(a, b), Galois.subtract(a, b),
                        "add and subtract must agree for a=" + a + " b=" + b);
            }
        }
    }

    // ── commutativity / associativity / distributivity ────────────────────

    @Test
    void multiplyIsCommutative() {
        for (int a = 0; a < 256; a++) {
            for (int b = 0; b < 256; b++) {
                assertEquals(Galois.multiply(a, b), Galois.multiply(b, a),
                        a + " * " + b + " should equal " + b + " * " + a);
            }
        }
    }

    @Test
    void multiplyIsAssociative() {
        // exhaustive: 256^3 = 16M ops, ~tens of ms
        for (int a = 0; a < 256; a++) {
            for (int b = 0; b < 256; b++) {
                for (int c = 0; c < 256; c++) {
                    assertEquals(
                            Galois.multiply(a, Galois.multiply(b, c)),
                            Galois.multiply(Galois.multiply(a, b), c),
                            "associativity failed: a=" + a + " b=" + b + " c=" + c);
                }
            }
        }
    }

    @Test
    void multiplyDistributesOverAdd() {
        // a * (b + c) == a*b + a*c — exhaustive
        for (int a = 0; a < 256; a++) {
            for (int b = 0; b < 256; b++) {
                for (int c = 0; c < 256; c++) {
                    assertEquals(
                            Galois.multiply(a, Galois.add(b, c)),
                            Galois.add(Galois.multiply(a, b), Galois.multiply(a, c)),
                            "distributivity failed: a=" + a + " b=" + b + " c=" + c);
                }
            }
        }
    }

    @Test
    void multiplicativeInverse() {
        // For all non-zero a: a * (1/a) == 1
        for (int a = 1; a < 256; a++) {
            int inv = Galois.divide(1, a);
            assertEquals(1, Galois.multiply(a, inv),
                    "a * (1/a) should be 1 for a=" + a);
        }
    }

    // ── MUL_TABLE consistency ─────────────────────────────────────────────

    @Test
    void mulTableMatchesMultiply() {
        for (int a = 0; a < 256; a++) {
            for (int b = 0; b < 256; b++) {
                byte expected = (byte) Galois.multiply(a, b);
                assertEquals(expected, Galois.MUL_TABLE[a][b],
                        "MUL_TABLE[" + a + "][" + b + "] mismatch");
            }
        }
    }

    // ── error handling ────────────────────────────────────────────────────

    @Test
    void divideByZeroThrows() {
        assertThrows(ArithmeticException.class, () -> Galois.divide(1, 0));
        assertThrows(ArithmeticException.class, () -> Galois.divide(0, 0));
    }

    // ── known values ──────────────────────────────────────────────────────

    @Test
    void knownMultiplicationValues() {
        // Cross-validated against the Backblaze Python reference implementation
        assertEquals(12, Galois.multiply(3, 4));
        assertEquals(21, Galois.multiply(7, 7));
        assertEquals(41, Galois.multiply(23, 45));

        // Powers of the generator 2
        assertEquals(2,  Galois.multiply(2, 1));
        assertEquals(4,  Galois.multiply(2, 2));
        assertEquals(8,  Galois.multiply(2, 4));
        // 2 * 128: 0x80 << 1 = 0x100, XOR 0x11D → 0x1D = 29
        assertEquals(29, Galois.multiply(2, 128));
    }
}
