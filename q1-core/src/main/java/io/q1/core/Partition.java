package io.q1.core;

import io.q1.core.io.FileIOFactory;
import org.rocksdb.Cache;

import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

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
 *
 * <h3>Concurrency model</h3>
 * A {@link ReentrantReadWriteLock} serialises all mutations:
 * <ul>
 *   <li>Write lock — {@code put}, {@code delete}, {@code applySyncBatch},
 *       compaction Phase 2.</li>
 *   <li>Read lock — {@code get} (index + segment read must be atomic w.r.t.
 *       compaction replacing segments).</li>
 *   <li>No lock — {@code exists}, {@code keysWithPrefix}, {@code syncState}
 *       (index-only or volatile reads, safe without locking).</li>
 * </ul>
 */
public final class Partition implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(Partition.class);

    /** Default roll-over threshold: 1 GiB. */
    static final long DEFAULT_MAX_SEGMENT_SIZE = 1L << 30;

    private final int           id;
    private final Path          dir;
    private final FileIOFactory ioFactory;
    private final long          maxSegmentSize;

    /**
     * Guards all write operations (put, delete, maybeRoll) and compaction Phase 2.
     * Read lock is held by {@code get()} to prevent reading stale segment references
     * during compaction.
     */
    private final ReadWriteLock rwLock = new ReentrantReadWriteLock();

    /** All segments in creation order (includes the active one at the end). */
    private final List<Segment>                   segments = new ArrayList<>();
    private final ConcurrentMap<Integer, Segment> byId     = new ConcurrentHashMap<>();
    private final RocksDbIndex                    index;
    private volatile Segment                      active;

    private final Compactor compactor;

    public Partition(int id, Path dir, FileIOFactory ioFactory, Cache sharedCache) throws IOException {
        this(id, dir, ioFactory, sharedCache, DEFAULT_MAX_SEGMENT_SIZE);
    }

    /** Full constructor — {@code maxSegmentSize} exposed for testing with small values. */
    public Partition(int id, Path dir, FileIOFactory ioFactory, Cache sharedCache,
                     long maxSegmentSize) throws IOException {
        this.id             = id;
        this.dir            = dir;
        this.ioFactory      = ioFactory;
        this.maxSegmentSize = maxSegmentSize;
        Files.createDirectories(dir);
        this.index     = new RocksDbIndex(dir.resolve("keyindex"), sharedCache);
        openSegments();
        this.compactor = new Compactor(id, dir, index, segments, byId, rwLock, ioFactory);
        log.debug("Partition {} ready: {} segment(s), ~{} key(s)", id, segments.size(), index.size());
    }

    // ── public API ────────────────────────────────────────────────────────

    public void put(String key, byte[] value) throws IOException {
        rwLock.writeLock().lock();
        try {
            maybeRoll();
            int  keyLen      = key.getBytes(StandardCharsets.UTF_8).length;
            long valueOffset = active.append(key, value);
            index.put(key, new RocksDbIndex.Entry(active.id(), valueOffset, value.length, keyLen));
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public byte[] get(String key) throws IOException {
        // Read lock prevents compaction from swapping out the segment between
        // index.get() and seg.read() — see Compactor for the write-lock side.
        rwLock.readLock().lock();
        try {
            RocksDbIndex.Entry e = index.get(key);
            if (e == null) return null;
            Segment seg = byId.get(e.segmentId());
            if (seg == null) throw new IllegalStateException("Segment " + e.segmentId() + " missing");
            return seg.read(e.valueOffset(), e.valueLength(), e.keyLen());
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public boolean exists(String key) throws IOException {
        return index.contains(key);
    }

    public void delete(String key) throws IOException {
        rwLock.writeLock().lock();
        try {
            if (!index.contains(key)) return;
            maybeRoll();
            active.appendTombstone(key);
            index.remove(key);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /** All keys whose full internal form starts with {@code prefix}. */
    public List<String> keysWithPrefix(String prefix) throws IOException {
        return index.keysWithPrefix(prefix);
    }

    /**
     * Returns up to {@code limit} internal keys that have the given {@code prefix},
     * starting at {@code fromKey} (inclusive, or start of prefix range if null).
     * Used by the background EC repair scanner.
     */
    public List<String> scanKeysFrom(String fromKey, String prefix, int limit) {
        return index.scanKeysFrom(fromKey, prefix, limit);
    }

    /**
     * Like {@link #scanKeysFrom} but also returns the value length (object size)
     * of each entry as a {@code Map.Entry<internalKey, valueLength>}.
     * Used by the listing path to surface real object sizes.
     */
    public List<java.util.Map.Entry<String, Long>> scanSizesFrom(
            String fromKey, String prefix, int limit) {
        return index.scanSizesFrom(fromKey, prefix, limit);
    }

    /** Returns the EC repair checkpoint for this partition, or {@code null} if unset. */
    public String getRepairCheckpoint() throws IOException {
        return index.getRepairCheckpoint();
    }

    /** Persists the EC repair checkpoint for this partition. */
    public void setRepairCheckpoint(String key) throws IOException {
        index.setRepairCheckpoint(key);
    }

    // ── test helpers ──────────────────────────────────────────────────────

    /** Forces the active segment to roll over. Package-private, test use only. */
    void forceRoll() throws IOException {
        rwLock.writeLock().lock();
        try {
            active = newSegment();
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    // ── compaction API ────────────────────────────────────────────────────

    /** Lightweight snapshot of partition metrics (segment count, disk usage, index size). */
    public record Stats(int segmentCount, long totalSizeBytes, long liveKeyCount) {}

    /**
     * Returns a consistent snapshot of this partition's storage metrics.
     * Acquires the read lock briefly so the segment list is stable.
     */
    public Stats stats() {
        rwLock.readLock().lock();
        try {
            int  segCount   = segments.size();
            long totalBytes = segments.stream().mapToLong(Segment::size).sum();
            long keyCount   = index.size();
            return new Stats(segCount, totalBytes, keyCount);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /** Summary returned by {@link #compactIfNeeded}. */
    public record CompactionRun(int compacted, int intact) {}

    /**
     * Selects sealed segments whose dead-byte ratio exceeds {@code threshold},
     * then compacts up to {@code maxSegments} of them (worst first).
     *
     * @return {@link CompactionRun} with counts of segments compacted and left intact
     */
    public CompactionRun compactIfNeeded(double threshold, int maxSegments) throws IOException {
        return compactIfNeeded(threshold, maxSegments, null);
    }

    /**
     * Like {@link #compactIfNeeded(double, int)} but calls {@code beforeEach}
     * before every individual segment compaction — used by callers to apply
     * rate limiting without coupling {@link Partition} to any specific library.
     */
    public CompactionRun compactIfNeeded(double threshold, int maxSegments,
                                         Runnable beforeEach) throws IOException {
        int sealed     = compactor.sealedCount();
        List<Integer> candidates = compactor.candidates(threshold, maxSegments);
        int compacted  = 0;
        for (int segId : candidates) {
            if (beforeEach != null) beforeEach.run();
            log.info("Partition {}: starting compaction of segment {}", id, segId);
            CompactionStats stats = compactor.compact(segId);
            if (stats != null) {
                compacted++;
                log.info("Partition {}: compaction done — seg={} keys={} skipped={} {}→{} bytes",
                        id, stats.segmentId(), stats.keysCompacted(), stats.keysSkipped(),
                        stats.originalSize(), stats.liveBytes());
            }
        }
        return new CompactionRun(compacted, Math.max(0, sealed - candidates.size()));
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
     */
    public InputStream openSyncStream(int fromSegmentId, long fromOffset) throws IOException {
        List<InputStream> parts = new ArrayList<>();
        List<Segment>     snap;
        rwLock.readLock().lock();
        try { snap = List.copyOf(segments); } finally { rwLock.readLock().unlock(); }

        if (fromSegmentId == 0) {
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
                log.warn("Partition {}: segment {} not found, falling back to full resync", id, fromSegmentId);
                for (Segment seg : snap) addSegmentStream(parts, seg, 0);
            }
        }

        return parts.isEmpty()
                ? InputStream.nullInputStream()
                : new SequenceInputStream(Collections.enumeration(parts));
    }

    /**
     * Applies a sync stream from the leader in a single RocksDB write batch.
     */
    public void applySyncBatch(InputStream in) throws IOException {
        record Rec(String key, byte flags, byte[] value) {}
        List<Rec> records = new ArrayList<>();
        Segment.scanStream(in, (key, flags, value) -> records.add(new Rec(key, flags, value)));

        rwLock.writeLock().lock();
        try (RocksDbIndex.BatchUpdater batch = index.newBatch()) {
            for (Rec r : records) {
                maybeRoll();
                if (r.flags() == Segment.FLAG_TOMB) {
                    active.appendTombstone(r.key());
                    batch.remove(r.key());
                } else {
                    int  keyLen      = r.key().getBytes(StandardCharsets.UTF_8).length;
                    long valueOffset = active.append(r.key(), r.value());
                    batch.put(r.key(), new RocksDbIndex.Entry(active.id(), valueOffset, r.value().length, keyLen));
                }
            }
            index.applyBatch(batch);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Flush the active segment's writes to the underlying storage device.
     * Called before a Raft snapshot is recorded so that all applied entries
     * are durably on disk before Ratis is allowed to purge those log entries.
     */
    public void force() throws IOException {
        rwLock.readLock().lock();
        try {
            active.force();
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @Override
    public void close() throws IOException {
        for (Segment s : segments) s.close();
        index.close();
    }

    // ── private helpers ───────────────────────────────────────────────────

    private void openSegments() throws IOException {
        // 1. Delete leftover .dead files
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".dead"))
                  .forEach(p -> {
                      try { Files.deleteIfExists(p); }
                      catch (IOException e) { throw new RuntimeException(e); }
                  });
        }

        // 2. Recover .compact files (crash between WriteBatch and rename)
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().matches("segment-\\d{10}\\.q1\\.compact"))
                  .sorted()
                  .forEach(p -> {
                      try {
                          int  segId  = parseCompactSegmentId(p.getFileName().toString());
                          Path target = dir.resolve("segment-%010d.q1".formatted(segId));
                          if (index.hasEntriesForSegment(segId)) {
                              // WriteBatch committed but rename was interrupted — finish it
                              Files.move(p, target, StandardCopyOption.REPLACE_EXISTING);
                              log.info("Partition {}: recovered compact segment {}", id, segId);
                          } else {
                              Files.deleteIfExists(p);
                              log.debug("Partition {}: deleted incomplete compact file {}", id, p.getFileName());
                          }
                      } catch (IOException e) { throw new RuntimeException(e); }
                  });
        }

        // 3. Load normal .q1 segments
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().matches("segment-\\d{10}\\.q1"))
                  .sorted()
                  .forEach(p -> {
                      try {
                          int     segId = parseSegmentId(p.getFileName().toString());
                          Segment s     = new Segment(segId, p, ioFactory.open(p));
                          segments.add(s);
                          byId.put(segId, s);
                      } catch (IOException e) { throw new RuntimeException(e); }
                  });
        }

        if (segments.isEmpty()) {
            active = newSegment();
        } else {
            active = segments.getLast();
        }
    }

    private void maybeRoll() throws IOException {
        if (active.size() >= maxSegmentSize) {
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

    private static int parseCompactSegmentId(String filename) {
        // "segment-0000000001.q1.compact" → 1
        return Integer.parseInt(filename.substring("segment-".length(),
                filename.length() - ".q1.compact".length()));
    }

    private static void addSegmentStream(List<InputStream> parts, Segment seg, long fromOffset)
            throws IOException {
        long size = seg.size();
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
