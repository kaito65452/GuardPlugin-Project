package id.saryu.libcore.util;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;

/**
 * Simple LRU / TTL cache for blacklist match results and similar lookups.
 * Thread-safe, bounded size, time-based expiration.
 */
public class TtlCache<K, V> {

    private final int maxSize;
    private final long ttlMillis;
    private final Map<K, CacheEntry<V>> map = new LinkedHashMap<>();

    public TtlCache(int maxSize, long ttlMillis) {
        this.maxSize = maxSize;
        this.ttlMillis = ttlMillis;
    }

    public synchronized @SuppressWarnings("unchecked") V get(K key) {
        CacheEntry<V> e = map.get(key);
        if (e == null) return null;
        if (System.currentTimeMillis() - e.putTime > ttlMillis) {
            map.remove(key);
            return null;
        }
        return e.value;
    }

    public synchronized void put(K key, V value) {
        if (map.size() >= maxSize && !map.containsKey(key)) {
            // Evict oldest.
            var it = map.entrySet().iterator();
            if (it.hasNext()) it.remove();
        }
        map.put(key, new CacheEntry<>(value, System.currentTimeMillis()));
    }

    public synchronized void remove(K key) {
        map.remove(key);
    }

    public synchronized void clear() {
        map.clear();
    }

    public synchronized int size() {
        return map.size();
    }

    private static class CacheEntry<V> {
        final V value;
        final long putTime;
        CacheEntry(V value, long putTime) { this.value = value; this.putTime = putTime; }
    }
}
