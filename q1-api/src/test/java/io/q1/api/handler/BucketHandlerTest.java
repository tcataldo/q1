package io.q1.api.handler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the static helper methods of {@link BucketHandler} that can be
 * exercised without an HTTP server.
 */
class BucketHandlerTest {

    // ── escape ────────────────────────────────────────────────────────────

    @Test
    void escape_null_returnsEmpty() {
        assertEquals("", BucketHandler.escape(null));
    }

    @Test
    void escape_noSpecialChars_unchanged() {
        assertEquals("hello/world-123_ok", BucketHandler.escape("hello/world-123_ok"));
    }

    @Test
    void escape_ampersand() {
        assertEquals("a&amp;b", BucketHandler.escape("a&b"));
    }

    @Test
    void escape_lessThan() {
        assertEquals("&lt;tag", BucketHandler.escape("<tag"));
    }

    @Test
    void escape_greaterThan() {
        assertEquals("tag&gt;", BucketHandler.escape("tag>"));
    }

    @Test
    void escape_allSpecialChars() {
        assertEquals("&lt;a&gt;&amp;b", BucketHandler.escape("<a>&b"));
    }

    @Test
    void escape_emptyString() {
        assertEquals("", BucketHandler.escape(""));
    }

    @Test
    void escape_onlyAmpersands() {
        assertEquals("&amp;&amp;&amp;", BucketHandler.escape("&&&"));
    }
}
