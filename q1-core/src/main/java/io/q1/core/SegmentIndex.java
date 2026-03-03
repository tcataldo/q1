package io.q1.core;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory index for a single partition: maps an internal key
 * ({@code bucket\x00objectKey}) to the location of its value bytes on disk.
 *
 * All operations are thread-safe.
 */
public final class SegmentIndex {

    /**
     * Points to the value bytes of an object within a specific segment file.
     *
     * @param segmentId   the {@link Segment#id()} that holds the data
     * @param valueOffset byte offset in that segment where value bytes start
     * @param valueLength number of value bytes
     */
    public record Entry(int segmentId, long valueOffset, long valueLength) {}

    private final ConcurrentHashMap<String, Entry> map = new ConcurrentHashMap<>();

    public void put(String key, Entry entry)     { map.put(key, entry); }
    public Entry  get(String key)                { return map.get(key); }
    public void   remove(String key)             { map.remove(key); }
    public boolean contains(String key)          { return map.containsKey(key); }
    public int    size()                         { return map.size(); }

    /** Live, concurrent-safe view of all keys in this index. */
    public Set<String> keySet()                  { return map.keySet(); }

    /** Keys that start with the given prefix, sorted. */
    public List<String> keysWithPrefix(String prefix) {
        return map.keySet().stream()
                .filter(k -> k.startsWith(prefix))
                .sorted()
                .toList();
    }
}
