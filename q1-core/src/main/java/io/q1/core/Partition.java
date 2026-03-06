package io.q1.core;

import io.q1.core.io.FileIOFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Owns one contiguous key range.  Each partition has:
 * <ul>
 *   <li>A directory of append-only {@link Segment} files.</li>
 *   <li>One <em>active</em> segment that accepts writes.</li>
 *   <li>A persistent {@link RocksDbIndex} that survives restarts — no segment
 *       scan is needed at startup.</li>
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

    /** Serialises all write operations (put, delete, maybeRoll). */
    private final ReentrantLock writeLock = new ReentrantLock();

    /** All segments in creation order (includes the active one at the end). */
    private final List<Segment>                segments = new ArrayList<>();
    private final ConcurrentMap<Integer, Segment> byId  = new ConcurrentHashMap<>();
    private final RocksDbIndex                 index;
    private volatile Segment                   active;

    public Partition(int id, Path dir, FileIOFactory ioFactory) throws IOException {
        this.id        = id;
        this.dir       = dir;
        this.ioFactory = ioFactory;
        Files.createDirectories(dir);
        this.index = new RocksDbIndex(dir.resolve("keyindex"));
        openSegments();
        log.debug("Partition {} ready: {} segment(s), ~{} key(s)", id, segments.size(), index.size());
    }

    // ── public API ────────────────────────────────────────────────────────

    public void put(String key, byte[] value) throws IOException {
        writeLock.lock();
        try {
            maybeRoll();
            long valueOffset = active.append(key, value);
            index.put(key, new RocksDbIndex.Entry(active.id(), valueOffset, value.length));
        } finally {
            writeLock.unlock();
        }
    }

    public byte[] get(String key) throws IOException {
        RocksDbIndex.Entry e = index.get(key);
        if (e == null) return null;
        Segment seg = byId.get(e.segmentId());
        if (seg == null) throw new IllegalStateException("Segment " + e.segmentId() + " missing");
        return seg.read(e.valueOffset(), e.valueLength());
    }

    public boolean exists(String key) throws IOException {
        return index.contains(key);
    }

    public void delete(String key) throws IOException {
        writeLock.lock();
        try {
            if (!index.contains(key)) return;
            maybeRoll();
            active.appendTombstone(key);
            index.remove(key);
        } finally {
            writeLock.unlock();
        }
    }

    /** All keys whose full internal form starts with {@code prefix}. */
    public List<String> keysWithPrefix(String prefix) throws IOException {
        return index.keysWithPrefix(prefix);
    }

    // ── sync / catchup API ────────────────────────────────────────────────

    /**
     * Returns the current write position of this partition.
     * A follower sends this to the leader to indicate where it left off.
     */
    public SyncState syncState(int partitionId) {
        if (segments.isEmpty()) return SyncState.empty(partitionId);
        return new SyncState(partitionId, active.id(), active.size());
    }

    /**
     * Opens a stream of raw segment-record bytes starting from
     * ({@code fromSegmentId}, {@code fromOffset}).
     *
     * <p>{@code fromSegmentId == 0} means "stream from the very beginning".
     * If the requested segment is no longer present (rolled away), the stream
     * starts from the oldest available segment so the follower gets a full copy.
     *
     * <p>The stream is a concatenation of bounded snapshots of segment files;
     * it does not grow as new writes arrive.  The caller must close it.
     */
    public InputStream openSyncStream(int fromSegmentId, long fromOffset) throws IOException {
        List<InputStream> parts = new ArrayList<>();
        List<Segment>     snap;
        writeLock.lock();
        try { snap = List.copyOf(segments); } finally { writeLock.unlock(); }

        if (fromSegmentId == 0) {
            // Follower has nothing — stream everything
            for (Segment seg : snap) addSegmentStream(parts, seg, 0);
        } else {
            boolean found = false;
            for (Segment seg : snap) {
                if (!found) {
                    if (seg.id() == fromSegmentId) {
                        found = true;
                        addSegmentStream(parts, seg, fromOffset);
                    }
                } else {
                    addSegmentStream(parts, seg, 0);
                }
            }
            if (!found) {
                // Follower's last segment was compacted away — full resync
                log.warn("Partition {}: segment {} not found, falling back to full resync", id, fromSegmentId);
                for (Segment seg : snap) addSegmentStream(parts, seg, 0);
            }
        }

        return parts.isEmpty()
                ? InputStream.nullInputStream()
                : new SequenceInputStream(Collections.enumeration(parts));
    }

    @Override
    public void close() throws IOException {
        for (Segment s : segments) s.close();
        index.close();
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

    private static void addSegmentStream(List<InputStream> parts, Segment seg, long fromOffset)
            throws IOException {
        long size = seg.size(); // snapshot — new writes may happen but we don't chase them
        if (size <= fromOffset) return;
        InputStream is = Files.newInputStream(seg.path(), StandardOpenOption.READ);
        if (fromOffset > 0) is.skipNBytes(fromOffset);
        parts.add(bounded(is, size - fromOffset));
    }

    /** Wraps an InputStream to read at most {@code limit} bytes. */
    private static InputStream bounded(InputStream in, long limit) {
        return new InputStream() {
            long remaining = limit;

            @Override public int read() throws IOException {
                if (remaining <= 0) return -1;
                int b = in.read();
                if (b >= 0) remaining--;
                return b;
            }

            @Override public int read(byte[] buf, int off, int len) throws IOException {
                if (remaining <= 0) return -1;
                int n = in.read(buf, off, (int) Math.min(len, remaining));
                if (n > 0) remaining -= n;
                return n;
            }

            @Override public void close() throws IOException { in.close(); }
        };
    }
}
