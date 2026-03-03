package io.q1.core;

import io.q1.core.io.FileIOFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Owns one contiguous key range.  Each partition has:
 * <ul>
 *   <li>A directory of append-only {@link Segment} files.</li>
 *   <li>One <em>active</em> segment that accepts writes.</li>
 *   <li>An in-memory {@link SegmentIndex} rebuilt at startup by scanning
 *       all segment files.</li>
 * </ul>
 *
 * A new active segment is rolled over once the current one exceeds
 * {@link #MAX_SEGMENT_SIZE} bytes.
 */
public final class Partition implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(Partition.class);

    /** Roll over to a new segment at 1 GiB. */
    static final long MAX_SEGMENT_SIZE = 1L << 30;

    private final int           id;
    private final Path          dir;
    private final FileIOFactory ioFactory;

    /** All segments in creation order (includes the active one at the end). */
    private final List<Segment>                segments = new ArrayList<>();
    private final ConcurrentMap<Integer, Segment> byId  = new ConcurrentHashMap<>();
    private final SegmentIndex                 index    = new SegmentIndex();
    private       Segment                      active;

    public Partition(int id, Path dir, FileIOFactory ioFactory) throws IOException {
        this.id        = id;
        this.dir       = dir;
        this.ioFactory = ioFactory;
        Files.createDirectories(dir);
        openSegments();
        rebuildIndex();
        log.debug("Partition {} ready: {} segment(s), {} key(s)", id, segments.size(), index.size());
    }

    // ── public API ────────────────────────────────────────────────────────

    public void put(String key, byte[] value) throws IOException {
        maybeRoll();
        long valueOffset = active.append(key, value);
        index.put(key, new SegmentIndex.Entry(active.id(), valueOffset, value.length));
    }

    public byte[] get(String key) throws IOException {
        SegmentIndex.Entry e = index.get(key);
        if (e == null) return null;
        Segment seg = byId.get(e.segmentId());
        if (seg == null) throw new IllegalStateException("Segment " + e.segmentId() + " missing");
        return seg.read(e.valueOffset(), e.valueLength());
    }

    public boolean exists(String key) {
        return index.contains(key);
    }

    public void delete(String key) throws IOException {
        if (!index.contains(key)) return;
        maybeRoll();
        active.appendTombstone(key);
        index.remove(key);
    }

    /** All keys whose full internal form starts with {@code prefix}. */
    public List<String> keysWithPrefix(String prefix) {
        return index.keysWithPrefix(prefix);
    }

    @Override
    public void close() throws IOException {
        for (Segment s : segments) s.close();
    }

    // ── private helpers ───────────────────────────────────────────────────

    private void openSegments() throws IOException {
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().matches("segment-\\d{10}\\.q1"))
                  .sorted()
                  .forEach(p -> {
                      try {
                          int segId = parseSegmentId(p.getFileName().toString());
                          Segment s = new Segment(segId, p, ioFactory.open(p));
                          segments.add(s);
                          byId.put(segId, s);
                      } catch (IOException e) {
                          throw new RuntimeException(e);
                      }
                  });
        }
        if (segments.isEmpty()) {
            active = newSegment();
        } else {
            active = segments.getLast();
        }
    }

    private void rebuildIndex() throws IOException {
        for (Segment seg : segments) {
            seg.scan((key, flags, segId, valueOffset, valueLength) -> {
                if (flags == Segment.FLAG_TOMB) {
                    index.remove(key);
                } else {
                    index.put(key, new SegmentIndex.Entry(segId, valueOffset, valueLength));
                }
            });
        }
    }

    private void maybeRoll() throws IOException {
        if (active.size() >= MAX_SEGMENT_SIZE) {
            log.info("Partition {} rolling segment {} (size={})", id, active.id(), active.size());
            active = newSegment();
        }
    }

    private Segment newSegment() throws IOException {
        int nextId = segments.isEmpty() ? 1 : segments.getLast().id() + 1;
        Path p     = dir.resolve("segment-%010d.q1".formatted(nextId));
        Segment s  = new Segment(nextId, p, ioFactory.open(p));
        segments.add(s);
        byId.put(nextId, s);
        return s;
    }

    private static int parseSegmentId(String filename) {
        // "segment-0000000001.q1" → 1
        return Integer.parseInt(filename.substring("segment-".length(), filename.length() - ".q1".length()));
    }
}
