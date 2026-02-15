package com.codeabbot.rmcache;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.lang.foreign.MemorySegment;

/**
 * High-performance off-heap cache for billion-scale data.
 *
 * @author Rabindra Meher
 */
public interface OffHeapCache<K, V> extends AutoCloseable {

    /** Put a value into the cache. */
    void put(K key, V value);

    /** Put a value with custom TTL. */
    void put(K key, V value, Duration ttl);

    /** Put a value with custom priority. */
    void put(K key, V value, short priority);

    /** Put a value with custom TTL and priority. */
    void put(K key, V value, Duration ttl, short priority);

    /**
     * Get a value from the cache.
     * 
     * @return the value, or null if not found or expired
     */
    V get(K key);

    /**
     * Remove a value from the cache.
     * 
     * @return true if the key was present
     */
    boolean remove(K key);

    /**
     * Put a value only if the key is not already present.
     * 
     * @param key   the key
     * @param value the value
     * @return true if the value was inserted, false if key already existed
     */
    boolean putIfAbsent(K key, V value);

    /**
     * Put a value with TTL only if the key is not already present.
     * 
     * @param key   the key
     * @param value the value
     * @param ttl   time-to-live
     * @return true if the value was inserted, false if key already existed
     */
    boolean putIfAbsent(K key, V value, Duration ttl);

    /**
     * Compute a value for the key if it's not already present.
     * The loader function is called only if the key is absent.
     * 
     * @param key    the key
     * @param loader function to compute the value
     * @return the existing value if present, or the newly computed value
     */
    V computeIfAbsent(K key, Function<K, V> loader);

    /**
     * Compute a value with TTL for the key if it's not already present.
     * 
     * @param key    the key
     * @param loader function to compute the value
     * @param ttl    time-to-live for the computed value
     * @return the existing value if present, or the newly computed value
     */
    V computeIfAbsent(K key, Function<K, V> loader, Duration ttl);

    /**
     * Put all entries from the map into the cache.
     * 
     * @param entries map of key-value pairs to insert
     */
    void putAll(java.util.Map<K, V> entries);

    /**
     * Put all entries from the map with a TTL.
     * 
     * @param entries map of key-value pairs to insert
     * @param ttl     time-to-live for all entries
     */
    void putAll(java.util.Map<K, V> entries, Duration ttl);

    /**
     * Get all values for the given keys.
     * Only returns entries that exist (non-null values).
     * 
     * @param keys collection of keys to retrieve
     * @return map of found key-value pairs
     */
    java.util.Map<K, V> getAll(java.util.Collection<K> keys);

    /** Put a value into the cache asynchronously. */
    CompletableFuture<Void> putAsync(K key, V value);

    /**
     * Get a value from the cache asynchronously.
     * 
     * @return a future that completes with the value, or null if not found or
     *         expired
     */
    CompletableFuture<V> getAsync(K key);

    /** Check if a key exists in the cache. */
    boolean contains(K key);

    /** Number of entries in the cache. */
    int size();

    /**
     * Get a value and process it directly off-heap without allocation.
     * 
     * @param processor function that receives the value's MemorySegment and returns
     *                  a result
     * @return result of processor, or null if key not found
     */
    <T> T getZeroCopy(K key, Function<MemorySegment, T> processor);

    /**
     * Get a zero-copy view of a value for large data access.
     */
    CacheValueView getView(K key);

    /** Clear all entries. */
    void clear();

    /** Get cache statistics. */
    CacheStats getStats();

    /** Get all keys (snapshot). */
    Set<K> getKeys();

    @Override
    void close();

    /**
     * Cache statistics.
     */
    record CacheStats(
            long hits,
            long misses,
            long evictions,
            int size,
            long memoryUsedBytes,
            long memoryTotalBytes) {
        public double hitRate() {
            long total = hits + misses;
            return (total == 0) ? 0.0 : (double) hits / total;
        }

        public double missRate() {
            return 1.0 - hitRate();
        }

        public double memoryUsagePercent() {
            return (memoryTotalBytes == 0) ? 0.0 : (double) memoryUsedBytes / memoryTotalBytes * 100.0;
        }
    }
}
