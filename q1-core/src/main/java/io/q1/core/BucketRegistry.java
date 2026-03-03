package io.q1.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which buckets exist.  Persisted as a Java {@link Properties} file
 * ({@code buckets.properties}) where each key is a bucket name and the value
 * is the creation epoch-millisecond.
 *
 * All public methods are thread-safe.
 */
public final class BucketRegistry {

    private static final Logger log = LoggerFactory.getLogger(BucketRegistry.class);

    private final Path file;
    private final ConcurrentHashMap<String, Long> buckets = new ConcurrentHashMap<>();

    public BucketRegistry(Path file) throws IOException {
        this.file = file;
        if (Files.exists(file)) {
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(file)) {
                p.load(in);
            }
            p.forEach((k, v) -> buckets.put((String) k, Long.parseLong((String) v)));
            log.debug("Loaded {} bucket(s) from {}", buckets.size(), file);
        }
    }

    /**
     * @return {@code true} if the bucket was created, {@code false} if it already existed.
     */
    public synchronized boolean create(String name) {
        if (buckets.containsKey(name)) return false;
        buckets.put(name, System.currentTimeMillis());
        persist();
        return true;
    }

    /**
     * @return {@code true} if the bucket existed and was removed.
     */
    public synchronized boolean delete(String name) {
        if (!buckets.remove(name, buckets.getOrDefault(name, -1L))) return false;
        // remove returned false means key wasn't present with that value — re-check
        if (buckets.containsKey(name)) return false;
        persist();
        return true;
    }

    public boolean exists(String name) {
        return buckets.containsKey(name);
    }

    /** Creation time of the bucket, or {@code null} if it does not exist. */
    public Instant createdAt(String name) {
        Long ms = buckets.get(name);
        return ms == null ? null : Instant.ofEpochMilli(ms);
    }

    public List<String> list() {
        return new ArrayList<>(buckets.keySet()).stream().sorted().toList();
    }

    // ── private ───────────────────────────────────────────────────────────

    private void persist() {
        Properties p = new Properties();
        buckets.forEach((k, v) -> p.setProperty(k, v.toString()));
        try (OutputStream out = Files.newOutputStream(file,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            p.store(out, "Q1 bucket registry — do not edit manually");
        } catch (IOException e) {
            log.error("Failed to persist bucket registry", e);
        }
    }
}
