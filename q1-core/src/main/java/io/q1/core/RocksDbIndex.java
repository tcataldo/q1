package io.q1.core;

import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
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
 */
public final class RocksDbIndex implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(RocksDbIndex.class);

    private static final int VALUE_BYTES = 4 + 8 + 8; // segmentId + valueOffset + valueLength

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

    private final RocksDB db;

    /**
     * Open (or create) the RocksDB index at {@code dbDir}.
     * The directory is created automatically if it does not exist.
     *
     * @throws IOException if RocksDB cannot be opened
     */
    public RocksDbIndex(Path dbDir) throws IOException {
        try (Options opts = new Options().setCreateIfMissing(true)) {
            db = RocksDB.open(opts, dbDir.toAbsolutePath().toString());
        } catch (RocksDBException e) {
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

    @Override
    public void close() {
        db.close();
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
