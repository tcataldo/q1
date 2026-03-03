package io.q1.core;

import io.q1.core.io.NioFileIOFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SegmentTest {

    @TempDir Path tmp;

    private Segment open(String name) throws Exception {
        Path p = tmp.resolve(name);
        return new Segment(1, p, NioFileIOFactory.INSTANCE.open(p));
    }

    // ── append / read ─────────────────────────────────────────────────────

    @Test
    void appendAndReadBack() throws Exception {
        Segment seg   = open("data.q1");
        byte[]  value = "Hello, Q1!".getBytes();

        long offset = seg.append("my-key", value);
        byte[] read = seg.read(offset, value.length);

        assertArrayEquals(value, read);
        seg.close();
    }

    @Test
    void appendEmptyValue() throws Exception {
        Segment seg    = open("empty.q1");
        long    offset = seg.append("k", new byte[0]);
        assertArrayEquals(new byte[0], seg.read(offset, 0));
        seg.close();
    }

    @Test
    void multipleAppendsReturnDifferentOffsets() throws Exception {
        Segment seg = open("multi.q1");

        long o1 = seg.append("key1", "aaa".getBytes());
        long o2 = seg.append("key2", "bbbbb".getBytes());

        assertTrue(o2 > o1, "Second offset should be after first");
        assertArrayEquals("aaa".getBytes(),   seg.read(o1, 3));
        assertArrayEquals("bbbbb".getBytes(), seg.read(o2, 5));
        seg.close();
    }

    // ── scan ──────────────────────────────────────────────────────────────

    @Test
    void scanDataAndTombstone() throws Exception {
        Segment    seg       = open("scan.q1");
        List<Byte> flagsSeen = new ArrayList<>();

        seg.append("key1", "value1".getBytes());
        seg.appendTombstone("key1");

        seg.scan((key, flags, segId, valOffset, valLen) -> flagsSeen.add(flags));

        assertEquals(2, flagsSeen.size());
        assertEquals(Segment.FLAG_DATA, flagsSeen.get(0));
        assertEquals(Segment.FLAG_TOMB, flagsSeen.get(1));
        seg.close();
    }

    @Test
    void scanReportsCorrectKeys() throws Exception {
        Segment     seg  = open("keys.q1");
        List<String> keys = new ArrayList<>();

        seg.append("alpha", "A".getBytes());
        seg.append("beta",  "B".getBytes());
        seg.appendTombstone("gamma");

        seg.scan((key, flags, segId, valOffset, valLen) -> keys.add(key));

        assertEquals(List.of("alpha", "beta", "gamma"), keys);
        seg.close();
    }

    // ── scanStream ────────────────────────────────────────────────────────

    @Test
    void scanStreamRoundTrip() throws Exception {
        Segment seg = open("stream.q1");
        seg.append("alpha", "AAA".getBytes());
        seg.append("beta",  "BBB".getBytes());
        seg.appendTombstone("gamma");
        seg.close();

        List<String> keys  = new ArrayList<>();
        List<Byte>   flags = new ArrayList<>();
        List<byte[]> vals  = new ArrayList<>();

        try (InputStream in = Files.newInputStream(tmp.resolve("stream.q1"))) {
            Segment.scanStream(in, (key, f, value) -> {
                keys.add(key); flags.add(f); vals.add(value);
            });
        }

        assertEquals(List.of("alpha", "beta", "gamma"), keys);
        assertEquals(Segment.FLAG_DATA, flags.get(0));
        assertEquals(Segment.FLAG_DATA, flags.get(1));
        assertEquals(Segment.FLAG_TOMB, flags.get(2));
        assertArrayEquals("AAA".getBytes(), vals.get(0));
        assertArrayEquals("BBB".getBytes(), vals.get(1));
    }

    @Test
    void scanStreamEmpty() throws Exception {
        List<String> keys = new ArrayList<>();
        Segment.scanStream(InputStream.nullInputStream(), (k, f, v) -> keys.add(k));
        assertTrue(keys.isEmpty());
    }

    @Test
    void scanStreamTruncatedHeaderIsSafe() {
        // A stream shorter than HEADER_SIZE must not throw
        byte[] partial = new byte[10];
        partial[0] = 0x51; // starts like MAGIC but cut short
        assertDoesNotThrow(() ->
                Segment.scanStream(new ByteArrayInputStream(partial), (k, f, v) -> {}));
    }

    @Test
    void scanStreamTruncatedBodyIsSafe() throws Exception {
        // Write a complete record to a buffer, then truncate it mid-value
        Segment seg = open("trunc.q1");
        seg.append("key", "12345678901234567890".getBytes()); // 20-byte value
        seg.close();

        byte[] full = Files.readAllBytes(tmp.resolve("trunc.q1"));
        // Keep header + key but cut off half the value
        byte[] partial = new byte[full.length - 10];
        System.arraycopy(full, 0, partial, 0, partial.length);

        List<String> keys = new ArrayList<>();
        assertDoesNotThrow(() ->
                Segment.scanStream(new ByteArrayInputStream(partial), (k, f, v) -> keys.add(k)));
        // Truncated record must NOT be delivered to the visitor
        assertTrue(keys.isEmpty());
    }
}
