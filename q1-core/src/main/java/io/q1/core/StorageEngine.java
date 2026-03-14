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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.SequencedSet;
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
     * Paginated listing of objects in {@code bucket}.
     *
     * <p>Keys are read directly from RocksDB (no segment scan). The {@code afterKey}
     * parameter is the first object key to include (inclusive); pass {@code null} to
     * start from the beginning. When {@code delimiter} is non-null, keys that contain
     * the delimiter after the prefix are collapsed into {@link ListResult#commonPrefixes()}
     * entries; both contents and common-prefixes count toward {@code maxKeys}.
     *
     * <p>If the result is truncated, {@link ListResult#nextKey()} holds the first
     * object key of the next page and should be Base64-encoded into a continuation
     * token by the caller.
     */
    /**
     * Internal bucket used by EC mode to store erasure-coded shards.
     * Shard keys have the form {@code {userBucket}/{objectKey}/{shardIdx:02d}}.
     * Listed transparently so that EC objects appear in normal bucket listings.
     */
    private static final String EC_SHARD_BUCKET = "__q1_ec_shards__";

    public ListResult listPaginated(String bucket, String prefix, String delimiter,
                                    int maxKeys, String afterKey) {
        String userPrefix  = prefix == null ? "" : prefix;
        String fullPrefix  = fullKey(bucket, userPrefix);
        String fullAfter   = afterKey != null ? fullKey(bucket, afterKey) : null;
        int    strip       = bucket.length() + 1;

        // ── regular (non-EC) keys ─────────────────────────────────────────
        // scanKeysFrom seeks directly in RocksDB — no segment file is touched.
        List<String> directKeys = Arrays.stream(partitions)
                .flatMap(p -> p.scanKeysFrom(fullAfter, fullPrefix, Integer.MAX_VALUE).stream())
                .sorted()
                .map(k -> k.substring(strip))
                .toList();

        // ── EC shard keys → unique object keys ───────────────────────────
        // Shard key in EC_SHARD_BUCKET: "{bucket}/{objectKey}/{shardIdx:02d}"
        // We scan with prefix "{bucket}/{userPrefix}" and extract unique object keys
        // by stripping the leading "{bucket}/" and the trailing "/{shardIdx}".
        String shardPrefix     = fullKey(EC_SHARD_BUCKET, bucket + "/" + userPrefix);
        String shardAfter      = afterKey != null
                ? fullKey(EC_SHARD_BUCKET, bucket + "/" + afterKey) : null;
        int    shardBucketStrip = EC_SHARD_BUCKET.length() + 1 + bucket.length() + 1; // strip "BUCKET\x00{bucket}/"
        List<String> ecKeys = Arrays.stream(partitions)
                .flatMap(p -> p.scanKeysFrom(shardAfter, shardPrefix, Integer.MAX_VALUE).stream())
                .sorted()
                .map(k -> k.substring(shardBucketStrip))    // "{objectKey}/{shardIdx:02d}"
                .map(k -> k.substring(0, k.length() - 3))   // strip "/{NN}"
                .distinct()
                .toList();

        // Merge direct + EC keys, re-sort, deduplicate
        List<String> allKeys;
        if (directKeys.isEmpty()) {
            allKeys = ecKeys;
        } else if (ecKeys.isEmpty()) {
            allKeys = directKeys;
        } else {
            allKeys = new ArrayList<>(directKeys.size() + ecKeys.size());
            allKeys.addAll(directKeys);
            allKeys.addAll(ecKeys);
            allKeys = allKeys.stream().sorted().distinct().toList();
        }

        String  effectivePrefix = prefix == null ? "" : prefix;
        boolean hasDelimiter    = delimiter != null && !delimiter.isEmpty();

        List<String>         contents       = new ArrayList<>();
        List<String>         commonPrefixes = new ArrayList<>();
        SequencedSet<String> seenCPs        = new LinkedHashSet<>();
        int    count   = 0;
        String nextKey = null;

        for (String key : allKeys) {
            if (count >= maxKeys) {
                nextKey = key;
                break;
            }
            if (hasDelimiter) {
                int delimIdx = key.indexOf(delimiter, effectivePrefix.length());
                if (delimIdx >= 0) {
                    String cp = key.substring(0, delimIdx + delimiter.length());
                    if (seenCPs.add(cp)) {
                        commonPrefixes.add(cp);
                        count++;
                    }
                    continue; // additional keys under a seen CP don't count
                }
            }
            contents.add(key);
            count++;
        }

        return new ListResult(contents, commonPrefixes, nextKey != null, nextKey);
    }

    /** Result of a paginated listing. Both {@code contents} and {@code commonPrefixes} are
     *  already sorted lexicographically. {@code nextKey} is non-null iff {@code truncated}. */
    public record ListResult(
            List<String> contents,
            List<String> commonPrefixes,
            boolean      truncated,
            String       nextKey) {}

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
