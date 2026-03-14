package io.q1.core;

import io.q1.core.io.FileIOFactory;
import io.q1.core.io.NioFileIOFactory;
import org.rocksdb.Cache;
import org.rocksdb.LRUCache;
import org.rocksdb.RocksDB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Top-level storage engine.  Routes object operations to one of {@code N}
 * {@link Partition}s determined by the full key's hash code.
 *
 * <p>The internal key format is {@code bucket + '\x00' + objectKey}.
 * The null-byte separator cannot appear in a valid S3 key, so the
 * concatenation is unambiguous.
 *
 * <p>All I/O is synchronous and suitable for calling from
 * {@link Thread#isVirtual() virtual threads}.
 *
 * <h3>Data directory layout</h3>
 * <pre>
 * dataDir/
 *   buckets.properties
 *   p00/  segment-0000000001.q1  …
 *   p01/  …
 *   …
 *   p15/
 * </pre>
 */
public final class StorageEngine implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(StorageEngine.class);

    private static final int    DEFAULT_PARTITIONS  = 16;
    private static final long   BLOCK_CACHE_BYTES   = 256L << 20; // 256 MiB shared across all partitions

    private static final double COMPACT_THRESHOLD   =
            Double.parseDouble(System.getenv().getOrDefault("Q1_COMPACT_THRESHOLD",   "0.5"));
    private static final long   COMPACT_INTERVAL_S  =
            Long.parseLong  (System.getenv().getOrDefault("Q1_COMPACT_INTERVAL_S",   "300"));
    private static final int    COMPACT_MAX_SEGMENTS =
            Integer.parseInt(System.getenv().getOrDefault("Q1_COMPACT_MAX_SEGMENTS", "4"));

    static { RocksDB.loadLibrary(); }

    private final Partition[]              partitions;
    private final BucketRegistry           buckets;
    private final Cache                    sharedCache;
    private final ScheduledExecutorService compactionScheduler =
            Executors.newSingleThreadScheduledExecutor(
                    Thread.ofVirtual().name("compactor").factory());

    public StorageEngine(Path dataDir) throws IOException {
        this(dataDir, DEFAULT_PARTITIONS, NioFileIOFactory.INSTANCE);
    }

    public StorageEngine(Path dataDir, int numPartitions) throws IOException {
        this(dataDir, numPartitions, NioFileIOFactory.INSTANCE);
    }

    /** Full constructor with explicit I/O factory and partition count. */
    public StorageEngine(Path dataDir, int numPartitions, FileIOFactory ioFactory) throws IOException {
        Files.createDirectories(dataDir);
        this.buckets     = new BucketRegistry(dataDir.resolve("buckets.properties"));
        this.sharedCache = new LRUCache(BLOCK_CACHE_BYTES);
        this.partitions  = new Partition[numPartitions];
        for (int i = 0; i < numPartitions; i++) {
            partitions[i] = new Partition(i, dataDir.resolve("p%02d".formatted(i)), ioFactory, sharedCache);
        }
        log.info("StorageEngine ready: {} partition(s), io={}", numPartitions,
                ioFactory.getClass().getSimpleName());
        compactionScheduler.scheduleWithFixedDelay(
                this::runCompaction, COMPACT_INTERVAL_S, COMPACT_INTERVAL_S, TimeUnit.SECONDS);
    }

    // ── bucket operations ─────────────────────────────────────────────────

    /** @return {@code false} if the bucket already exists */
    public boolean createBucket(String bucket)   { return buckets.create(bucket); }

    /** @return {@code false} if the bucket did not exist */
    public boolean deleteBucket(String bucket)   { return buckets.delete(bucket); }

    public boolean       bucketExists(String bucket) { return buckets.exists(bucket); }
    public List<String>  listBuckets()               { return buckets.list(); }
    public Instant       bucketCreatedAt(String bucket) { return buckets.createdAt(bucket); }

    // ── object operations ─────────────────────────────────────────────────

    public void put(String bucket, String key, byte[] value) throws IOException {
        partition(bucket, key).put(fullKey(bucket, key), value);
    }

    /** @return the raw value bytes, or {@code null} if not found */
    public byte[] get(String bucket, String key) throws IOException {
        return partition(bucket, key).get(fullKey(bucket, key));
    }

    public boolean exists(String bucket, String key) throws IOException {
        return partition(bucket, key).exists(fullKey(bucket, key));
    }

    public void delete(String bucket, String key) throws IOException {
        partition(bucket, key).delete(fullKey(bucket, key));
    }

    /**
     * List keys in {@code bucket} whose key starts with {@code prefix}.
     * {@code prefix} may be {@code null} or empty to list everything.
     * Queries all partitions (no shard-local shortcuts yet).
     */
    public List<String> list(String bucket, String prefix) throws IOException {
        String fullPrefix = fullKey(bucket, prefix == null ? "" : prefix);
        int    strip      = bucket.length() + 1; // strip "bucket\x00"
        try {
            return Arrays.stream(partitions)
                    .flatMap(p -> {
                        try {
                            return p.keysWithPrefix(fullPrefix).stream();
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                    .map(k -> k.substring(strip))
                    .sorted()
                    .toList();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    /** Number of partitions this engine manages. */
    public int numPartitions() {
        return partitions.length;
    }

    // ── repair scan API ───────────────────────────────────────────────────

    /**
     * Returns up to {@code limit} internal keys in {@code partitionId} that have
     * the given {@code prefix}, starting at {@code fromKey} (inclusive, or start
     * of prefix range if null).  Used by the background EC repair scanner.
     */
    public List<String> scanKeysFrom(int partitionId, String fromKey, String prefix, int limit) {
        return partitions[partitionId].scanKeysFrom(fromKey, prefix, limit);
    }

    /**
     * Returns the EC repair checkpoint for {@code partitionId}, or {@code null}
     * if no checkpoint has been saved yet.
     */
    public String getRepairCheckpoint(int partitionId) throws IOException {
        return partitions[partitionId].getRepairCheckpoint();
    }

    /**
     * Persists the EC repair checkpoint for {@code partitionId}.
     * Pass {@code null} to reset (next scan starts from the beginning).
     */
    public void setRepairCheckpoint(int partitionId, String key) throws IOException {
        partitions[partitionId].setRepairCheckpoint(key);
    }

    // ── sync / catchup API ────────────────────────────────────────────────

    /**
     * Current write position of partition {@code partitionId}.
     * Sent by a follower to the leader to indicate where it left off.
     */
    public SyncState partitionSyncState(int partitionId) {
        return partitions[partitionId].syncState(partitionId);
    }

    /**
     * Opens a byte stream of raw segment records for {@code partitionId},
     * starting from ({@code fromSegmentId}, {@code fromOffset}).
     * Called by the leader's sync endpoint to serve a catching-up follower.
     * The caller is responsible for closing the stream.
     */
    public InputStream openSyncStream(int partitionId, int fromSegmentId, long fromOffset)
            throws IOException {
        return partitions[partitionId].openSyncStream(fromSegmentId, fromOffset);
    }

    /**
     * Parse a sync stream and apply every record to {@code partitionId}'s
     * local storage.  Called on the follower side during catchup.
     *
     * <p>All index mutations are flushed as a single RocksDB
     * {@link org.rocksdb.WriteBatch} instead of N individual writes.
     */
    public void applySyncStream(int partitionId, InputStream in) throws IOException {
        partitions[partitionId].applySyncBatch(in);
    }

    @Override
    public void close() throws IOException {
        compactionScheduler.shutdownNow();
        try {
            compactionScheduler.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (Partition p : partitions) p.close();
        sharedCache.close();
        log.info("StorageEngine closed");
    }

    // ── compaction ────────────────────────────────────────────────────────

    private void runCompaction() {
        int totalCompacted = 0;
        int totalIntact    = 0;
        for (Partition p : partitions) {
            try {
                Partition.CompactionRun run = p.compactIfNeeded(COMPACT_THRESHOLD, COMPACT_MAX_SEGMENTS);
                totalCompacted += run.compacted();
                totalIntact    += run.intact();
            } catch (Exception e) {
                log.error("Compaction error in partition", e);
            }
        }
        log.info("Compaction pass: {} segment(s) compacted, {} segment(s) intact",
                totalCompacted, totalIntact);
    }

    // ── routing ───────────────────────────────────────────────────────────

    private Partition partition(String bucket, String key) {
        int hash = fullKey(bucket, key).hashCode();
        return partitions[Math.abs(hash) % partitions.length];
    }

    private static String fullKey(String bucket, String key) {
        return bucket + '\u0000' + key;
    }
}
