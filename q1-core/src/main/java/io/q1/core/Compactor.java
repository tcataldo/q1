package io.q1.core;

import io.q1.core.io.FileIOFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReadWriteLock;

/**
 * Performs background compaction of sealed segments for a single {@link Partition}.
 *
 * <p>Two-phase algorithm:
 * <ol>
 *   <li><b>Scan</b> — no lock held; iterates RocksDB to find live keys, reads
 *       their values from the old segment, writes a {@code .compact} temp file.</li>
 *   <li><b>Commit</b> — write lock held (~ms); verifies key freshness, applies a
 *       RocksDB WriteBatch, renames files, updates in-memory structures.</li>
 * </ol>
 *
 * <p>Crash safety: the RocksDB WriteBatch is the commit point.  Partial
 * {@code .compact} files and {@code .dead} files are cleaned up at startup by
 * {@link Partition#openSegments()}.
 */
class Compactor {

    private static final Logger log = LoggerFactory.getLogger(Compactor.class);

    private final int                          partitionId;
    private final Path                         dir;
    private final RocksDbIndex                 index;
    private final List<Segment>                segments; // owned by Partition, guarded by rwLock
    private final ConcurrentMap<Integer, Segment> byId;
    private final ReadWriteLock                rwLock;
    private final FileIOFactory                ioFactory;

    Compactor(int partitionId, Path dir, RocksDbIndex index,
              List<Segment> segments, ConcurrentMap<Integer, Segment> byId,
              ReadWriteLock rwLock, FileIOFactory ioFactory) {
        this.partitionId = partitionId;
        this.dir         = dir;
        this.index       = index;
        this.segments    = segments;
        this.byId        = byId;
        this.rwLock      = rwLock;
        this.ioFactory   = ioFactory;
    }

    /**
     * Returns the sealed segment IDs that exceed {@code threshold} dead ratio,
     * sorted by dead ratio descending, limited to {@code maxSegments}.
     */
    List<Integer> candidates(double threshold, int maxSegments) {
        // Snapshot sealed IDs under read lock (segments is mutated by maybeRoll under write lock)
        List<Integer> sealedIds;
        rwLock.readLock().lock();
        try {
            int n = segments.size();
            if (n <= 1) return List.of(); // only the active segment
            sealedIds = segments.subList(0, n - 1).stream().map(Segment::id).toList();
        } finally {
            rwLock.readLock().unlock();
        }
        if (sealedIds.isEmpty()) return List.of();

        // File sizes for each sealed segment
        Map<Integer, Long> sizes = new HashMap<>();
        for (int segId : sealedIds) {
            Segment s = byId.get(segId);
            if (s != null) sizes.put(segId, s.size());
        }

        // Live bytes per segment — full RocksDB scan, no lock needed (consistent snapshot)
        Set<Integer> sealedSet = new HashSet<>(sealedIds);
        Map<Integer, Long> liveBytes = new HashMap<>();
        for (int segId : sealedIds) liveBytes.put(segId, 0L);

        index.forEachEntry((key, entry) -> {
            if (sealedSet.contains(entry.segmentId()))
                liveBytes.merge(entry.segmentId(), entry.valueLength(), Long::sum);
        });

        return sealedIds.stream()
                .filter(id -> sizes.containsKey(id) && sizes.get(id) > 0)
                .filter(id -> deadRatio(sizes.get(id), liveBytes.getOrDefault(id, 0L)) > threshold)
                .sorted(Comparator.comparingDouble(
                        (Integer id) -> deadRatio(sizes.get(id), liveBytes.getOrDefault(id, 0L)))
                        .reversed())
                .limit(maxSegments)
                .toList();
    }

    /**
     * Compacts one sealed segment.
     *
     * @return stats, or {@code null} if the segment was concurrently replaced
     */
    CompactionStats compact(int segmentId) throws IOException {
        Segment oldSeg = byId.get(segmentId);
        if (oldSeg == null) {
            log.warn("Partition {}: segment {} disappeared before compaction", partitionId, segmentId);
            return null;
        }
        long originalSize = oldSeg.size();
        Path compactPath  = dir.resolve("segment-%010d.q1.compact".formatted(segmentId));

        // ── Collect live entries for this segment (no lock) ──────────────────
        record LiveEntry(String key, RocksDbIndex.Entry entry) {}
        List<LiveEntry> liveEntries = new ArrayList<>();
        index.forEachEntry((key, entry) -> {
            if (entry.segmentId() == segmentId)
                liveEntries.add(new LiveEntry(key, entry));
        });

        // ── Fast path: fully dead segment ────────────────────────────────────
        if (liveEntries.isEmpty()) {
            rwLock.writeLock().lock();
            try {
                if (byId.get(segmentId) != oldSeg) return null; // replaced concurrently
                Path deadPath = dir.resolve("segment-%010d.q1.dead".formatted(segmentId));
                int idx = segments.indexOf(oldSeg);
                if (idx >= 0) segments.remove(idx);
                byId.remove(segmentId);
                Files.move(oldSeg.path(), deadPath, StandardCopyOption.ATOMIC_MOVE);
                oldSeg.close();
                Files.deleteIfExists(deadPath);
                log.info("Partition {}: removed fully-dead segment {}", partitionId, segmentId);
            } finally {
                rwLock.writeLock().unlock();
            }
            return new CompactionStats(segmentId, originalSize, 0, 0, 0);
        }

        // ── Phase 1: write compact file (no lock) ────────────────────────────
        record Move(String key, RocksDbIndex.Entry oldEntry, RocksDbIndex.Entry newEntry) {}
        List<Move> moves = new ArrayList<>(liveEntries.size());

        Segment compactSeg = new Segment(segmentId, compactPath, ioFactory.open(compactPath));
        try {
            for (LiveEntry le : liveEntries) {
                byte[] value     = oldSeg.read(le.entry().valueOffset(), le.entry().valueLength());
                long   newOffset = compactSeg.append(le.key(), value);
                moves.add(new Move(le.key(), le.entry(),
                        new RocksDbIndex.Entry(segmentId, newOffset, le.entry().valueLength())));
            }
        } finally {
            compactSeg.close(); // fsync
        }

        // ── Phase 2: commit (write lock, ~ms) ────────────────────────────────
        int keysCommitted = 0;
        int keysSkipped   = 0;

        rwLock.writeLock().lock();
        try {
            if (byId.get(segmentId) != oldSeg) {
                Files.deleteIfExists(compactPath);
                return null; // segment replaced concurrently
            }

            // Verify freshness and build WriteBatch
            try (RocksDbIndex.BatchUpdater batch = index.newBatch()) {
                for (Move m : moves) {
                    RocksDbIndex.Entry current = index.get(m.key());
                    if (current == null || !current.equals(m.oldEntry())) {
                        keysSkipped++;
                        continue;
                    }
                    batch.put(m.key(), m.newEntry());
                    keysCommitted++;
                }
                index.applyBatch(batch); // ← commit point
            }

            // Rename: old → .dead, then compact → old path
            Path deadPath = dir.resolve("segment-%010d.q1.dead".formatted(segmentId));
            Files.move(oldSeg.path(), deadPath, StandardCopyOption.ATOMIC_MOVE);
            Files.move(compactPath, oldSeg.path(), StandardCopyOption.ATOMIC_MOVE);

            // Open new Segment at the final (renamed) path
            Segment newSeg = new Segment(segmentId, oldSeg.path(), ioFactory.open(oldSeg.path()));

            // Update in-memory structures
            int idx = segments.indexOf(oldSeg);
            if (idx >= 0) segments.set(idx, newSeg);
            byId.put(segmentId, newSeg);

            // Clean up old file
            oldSeg.close();
            Files.deleteIfExists(deadPath);

            log.info("Partition {}: compacted segment {} — {}/{} keys, {}→{} bytes",
                    partitionId, segmentId, keysCommitted, keysCommitted + keysSkipped,
                    originalSize, newSeg.size());
        } finally {
            rwLock.writeLock().unlock();
        }

        long liveBytes = moves.stream().mapToLong(m -> m.newEntry().valueLength()).sum();
        return new CompactionStats(segmentId, originalSize, liveBytes, keysCommitted, keysSkipped);
    }

    private static double deadRatio(long fileSize, long liveBytes) {
        return fileSize > 0 ? 1.0 - (double) liveBytes / fileSize : 0.0;
    }
}
