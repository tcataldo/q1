package io.q1.core;

import org.rocksdb.BlockBasedTableConfig;
import org.rocksdb.BloomFilter;
import org.rocksdb.Cache;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Persistent index for a single partition backed by an embedded RocksDB instance.
 *
 * <p>The database lives at {@code partitionDir/keyindex/} and survives process restarts,
 * eliminating the need to scan segment files to rebuild the index on startup.
 *
 * <h3>Key encoding</h3>
 * RocksDB keys are the raw UTF-8 bytes of the internal key
 * ({@code bucket\x00objectKey}).  RocksDB's default lexicographic byte order
 * matches the natural string order for valid S3 keys, so prefix iteration
 * requires no custom comparator.
 *
 * <h3>Value encoding</h3>
 * Each value is exactly 20 bytes, big-endian:
 * <pre>
 *   [4B] segmentId   (int)
 *   [8B] valueOffset (long)
 *   [8B] valueLength (long)
 * </pre>
 *
 * <h3>Crash consistency</h3>
 * Callers write to the segment file first, then update this index.  If the process
 * crashes between the two steps, the segment record becomes dead bytes reclaimed at
 * compaction.
 *
 * <h3>Repair checkpoint</h3>
 * A special key {@code 0x00 rchk} (sorts before all S3/internal bucket names) stores
 * the last shard key processed by the background EC repair scanner, enabling resumable
 * partition-level scans.
 */
public final class RocksDbIndex implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(RocksDbIndex.class);

    private static final int    VALUE_BYTES        = 4 + 8 + 8; // segmentId + valueOffset + valueLength
    private static final int    BLOOM_BITS_PER_KEY = 10;

    /** Reserved RocksDB key for the EC repair checkpoint (prefix 0x00 sorts before all bucket names). */
    private static final byte[] REPAIR_CHK_KEY     = {0x00, 'r', 'c', 'h', 'k'};

    static {
        RocksDB.loadLibrary();
    }

    /**
     * Points to the value bytes of an object within a specific segment file.
     *
     * @param segmentId   the segment file id
     * @param valueOffset byte offset in that segment where value bytes start
     * @param valueLength number of value bytes
     */
    public record Entry(int segmentId, long valueOffset, long valueLength) {}

    private final RocksDB     db;
    // Held for the lifetime of db so C++ doesn't free the underlying object prematurely.
    private final BloomFilter bloomFilter;

    /**
     * Open (or create) the RocksDB index at {@code dbDir}.
     * The directory is created automatically if it does not exist.
     *
     * <p>Tuning applied for the Q1 workload (60 M small keys, point-lookup heavy):
     * <ul>
     *   <li>Bloom filter (10 bits/key) — avoids disk reads for absent-key lookups.</li>
     *   <li>{@code blockCache} shared across all partitions (caller owns it).</li>
     *   <li>Dynamic level compaction — bounds space amplification as the index grows.</li>
     * </ul>
     *
     * @param blockCache shared LRU block cache owned by the caller (not closed here)
     * @throws IOException if RocksDB cannot be opened
     */
    public RocksDbIndex(Path dbDir, Cache blockCache) throws IOException {
        bloomFilter = new BloomFilter(BLOOM_BITS_PER_KEY);

        BlockBasedTableConfig tableConfig = new BlockBasedTableConfig()
                .setFilterPolicy(bloomFilter)
                .setBlockCache(blockCache);

        try (Options opts = new Options()
                .setCreateIfMissing(true)
                .setTableFormatConfig(tableConfig)
                .setLevelCompactionDynamicLevelBytes(true)) {
            db = RocksDB.open(opts, dbDir.toAbsolutePath().toString());
        } catch (RocksDBException e) {
            bloomFilter.close();
            throw new IOException("Failed to open RocksDB index at " + dbDir, e);
        }
        log.debug("RocksDbIndex opened at {}", dbDir);
    }

    // ── write operations ──────────────────────────────────────────────────

    public void put(String key, Entry entry) throws IOException {
        try {
            db.put(encode(key), toBytes(entry));
        } catch (RocksDBException e) {
            throw new IOException("RocksDB put failed for key: " + key, e);
        }
    }

    public void remove(String key) throws IOException {
        try {
            db.delete(encode(key));
        } catch (RocksDBException e) {
            throw new IOException("RocksDB delete failed for key: " + key, e);
        }
    }

    // ── read operations ───────────────────────────────────────────────────

    public Entry get(String key) throws IOException {
        try {
            byte[] raw = db.get(encode(key));
            return raw == null ? null : fromBytes(raw);
        } catch (RocksDBException e) {
            throw new IOException("RocksDB get failed for key: " + key, e);
        }
    }

    public boolean contains(String key) throws IOException {
        try {
            byte[] holder = new byte[VALUE_BYTES];
            return db.get(encode(key), holder) != RocksDB.NOT_FOUND;
        } catch (RocksDBException e) {
            throw new IOException("RocksDB contains failed for key: " + key, e);
        }
    }

    /**
     * Approximate count of live keys (uses RocksDB's internal estimator).
     * Only used for logging — not guaranteed to be exact.
     */
    public long size() {
        try {
            return db.getLongProperty("rocksdb.estimate-num-keys");
        } catch (RocksDBException e) {
            log.warn("Could not read rocksdb.estimate-num-keys", e);
            return -1L;
        }
    }

    /**
     * Returns all keys whose byte prefix matches {@code prefix}, in lexicographic order.
     *
     * <p>Uses a RocksDB seek iterator. Because the internal key format is
     * {@code bucket\x00objectKey} and RocksDB orders keys by unsigned byte value,
     * byte-lexicographic order coincides with string-lexicographic order for
     * ASCII bucket names (guaranteed by S3 naming rules).
     */
    public List<String> keysWithPrefix(String prefix) throws IOException {
        byte[]       prefixBytes = encode(prefix);
        List<String> result      = new ArrayList<>();

        try (RocksIterator it = db.newIterator()) {
            for (it.seek(prefixBytes); it.isValid(); it.next()) {
                byte[] rawKey = it.key();
                if (!startsWith(rawKey, prefixBytes)) break;
                result.add(new String(rawKey, StandardCharsets.UTF_8));
            }
        }
        return Collections.unmodifiableList(result);
    }

    // ── range scan (used by repair scanner) ──────────────────────────────

    /**
     * Returns up to {@code limit} keys whose UTF-8 bytes have the given prefix,
     * starting at {@code fromKey} (inclusive).  If {@code fromKey} is {@code null}
     * the scan starts at the beginning of the prefix range.
     *
     * <p>To advance past the last returned key on the next call, append a
     * {@code '\0'} byte to it — {@code lastKey + "\u0000"} sorts immediately
     * after {@code lastKey} in unsigned lexicographic order.
     */
    public List<String> scanKeysFrom(String fromKey, String prefix, int limit) {
        byte[] seekBytes   = fromKey != null ? encode(fromKey) : encode(prefix);
        byte[] prefixBytes = encode(prefix);
        List<String> result = new ArrayList<>();
        try (RocksIterator it = db.newIterator()) {
            for (it.seek(seekBytes); it.isValid() && result.size() < limit; it.next()) {
                byte[] rawKey = it.key();
                if (!startsWith(rawKey, prefixBytes)) break;
                result.add(new String(rawKey, StandardCharsets.UTF_8));
            }
        }
        return Collections.unmodifiableList(result);
    }

    // ── repair checkpoint ──────────────────────────────────────────────────

    /**
     * Returns the repair checkpoint (the next shard internal-key to scan from),
     * or {@code null} if no checkpoint has been saved yet (scan from the beginning).
     */
    public String getRepairCheckpoint() throws IOException {
        try {
            byte[] raw = db.get(REPAIR_CHK_KEY);
            return raw == null ? null : new String(raw, StandardCharsets.UTF_8);
        } catch (RocksDBException e) {
            throw new IOException("RocksDB getRepairCheckpoint failed", e);
        }
    }

    /**
     * Persists the repair checkpoint.  Pass {@code null} to reset (scan will
     * restart from the beginning of the shard key space on next invocation).
     */
    public void setRepairCheckpoint(String key) throws IOException {
        try {
            if (key == null) {
                db.delete(REPAIR_CHK_KEY);
            } else {
                db.put(REPAIR_CHK_KEY, key.getBytes(StandardCharsets.UTF_8));
            }
        } catch (RocksDBException e) {
            throw new IOException("RocksDB setRepairCheckpoint failed", e);
        }
    }

    // ── full-scan helpers (used by Compactor) ─────────────────────────────

    /**
     * Iterates every live entry in the index.  Uses a RocksDB snapshot iterator,
     * so it is consistent and safe to call without holding any application lock.
     * The consumer must not throw checked exceptions.
     */
    public void forEachEntry(BiConsumer<String, Entry> consumer) {
        try (RocksIterator it = db.newIterator()) {
            for (it.seekToFirst(); it.isValid(); it.next()) {
                consumer.accept(
                        new String(it.key(), StandardCharsets.UTF_8),
                        fromBytes(it.value()));
            }
        }
    }

    /**
     * Returns {@code true} if at least one live entry points to {@code segmentId}.
     * Used during crash recovery to decide whether to recover or discard a
     * {@code .compact} file.
     */
    public boolean hasEntriesForSegment(int segmentId) {
        try (RocksIterator it = db.newIterator()) {
            for (it.seekToFirst(); it.isValid(); it.next()) {
                if (fromBytes(it.value()).segmentId() == segmentId) return true;
            }
        }
        return false;
    }

    // ── batch write API ───────────────────────────────────────────────────

    /**
     * A write batch that accumulates index mutations and flushes them in one
     * RocksDB write via {@link #applyBatch}.  Must be closed after use.
     */
    public final class BatchUpdater implements Closeable {
        private final WriteBatch batch = new WriteBatch();

        private BatchUpdater() {}

        public void put(String key, Entry entry) throws IOException {
            try { batch.put(encode(key), toBytes(entry)); }
            catch (RocksDBException e) { throw new IOException("WriteBatch put failed", e); }
        }

        public void remove(String key) throws IOException {
            try { batch.delete(encode(key)); }
            catch (RocksDBException e) { throw new IOException("WriteBatch delete failed", e); }
        }

        @Override public void close() { batch.close(); }
    }

    /** Creates a new empty {@link BatchUpdater}. */
    public BatchUpdater newBatch() { return new BatchUpdater(); }

    /** Flushes all mutations accumulated in {@code b} as a single atomic RocksDB write. */
    public void applyBatch(BatchUpdater b) throws IOException {
        try (WriteOptions opts = new WriteOptions()) {
            db.write(opts, b.batch);
        } catch (RocksDBException e) {
            throw new IOException("RocksDB batch write failed", e);
        }
    }

    @Override
    public void close() {
        db.close();
        bloomFilter.close();
        log.debug("RocksDbIndex closed");
    }

    // ── serialization ─────────────────────────────────────────────────────

    private static byte[] encode(String key) {
        return key.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] toBytes(Entry e) {
        return ByteBuffer.allocate(VALUE_BYTES)
                .putInt(e.segmentId())
                .putLong(e.valueOffset())
                .putLong(e.valueLength())
                .array();
    }

    private static Entry fromBytes(byte[] raw) {
        ByteBuffer bb = ByteBuffer.wrap(raw);
        return new Entry(bb.getInt(), bb.getLong(), bb.getLong());
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) return false;
        }
        return true;
    }
}
