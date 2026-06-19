/*
 * Copyright 2026 Rabindra Meher
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.codeabbot.rmcache;

import java.time.Duration;
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
     *
     * <p><b>Not atomic.</b> The sequence {@code get() → loader.apply() →
     * putIfAbsent()} has a TOCTOU window: another thread can insert the same
     * key between the miss and the conditional put. When this happens the loader
     * is invoked but its result is discarded. For expensive loaders (DB queries,
     * RPCs), use external per-key synchronization (e.g., Striped locks) if
     * duplicate computation is unacceptable.
     *
     * @param key    the key
     * @param loader function to compute the value
     * @return the existing value if present, or the newly computed value
     */
    V computeIfAbsent(K key, Function<K, V> loader);

    /**
     * Compute a value with TTL for the key if it's not already present.
     *
     * <p><b>Not atomic.</b> See {@link #computeIfAbsent(Object, Function)} for
     * concurrency caveats.
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
     * <b>EXPERIMENTAL.</b> Get a value and process it directly off-heap without allocation.
     *
     * <p>The {@code MemorySegment} passed to the processor points to live off-heap
     * memory. If another thread evicts or updates (realloc) the same entry while
     * the processor is executing, the segment may reference freed or reallocated
     * memory. Callers must accept this risk or use external synchronization.
     *
     * @param key       the cache key
     * @param processor function that receives the value's MemorySegment and returns
     *                  a result. Must not store the segment reference.
     * @return result of processor, or null if key not found
     */
    <T> T getZeroCopy(K key, Function<MemorySegment, T> processor);

    /**
     * <b>EXPERIMENTAL.</b> Get a zero-copy view of a value for large data access.
     *
     * <p>See {@link CacheValueView} for safety constraints. The view must be
     * consumed immediately and closed promptly. Do not store it or pass it
     * to another thread.
     *
     * @return a view into the off-heap value, or null if not found
     */
    CacheValueView getView(K key);

    /**
     * Remove all entries from the cache.
     *
     * <p><b>Performance warning:</b> This method iterates every slot in the
     * entry pool (O(slotCapacity)) to free allocated blocks and update eviction
     * state. At large scales (millions of entries) this can block the calling
     * thread for a significant duration. Prefer letting eviction reclaim entries
     * naturally, or call from a maintenance thread if a full reset is required.
     */
    void clear();

    /** Get cache statistics. */
    CacheStats getStats();

    /**
     * Cache instance name — supplied via {@link CacheBuilder#withCacheName(String)}.
     * Used for logging and {@code toString}; has no effect on cache behavior.
     */
    String getCacheName();

    @Override
    void close();

    /**
     * Release thread-local buffers held by the calling thread.
     *
     * <p>RMCache allocates per-thread buffers (4KB-256KB) for zero-allocation
     * serialization. In application servers with long-lived thread pools
     * (Tomcat, Netty, etc.), these buffers are never collected until the thread
     * dies. Call this method from a servlet filter's {@code destroy()}, a
     * framework shutdown hook, or when retiring threads to prevent memory leaks.
     *
     * <p>Safe to call from any thread, even if the thread never used the cache.
     */
    static void cleanupThreadLocals() {
        OffHeapCacheImpl.removeThreadLocals();
    }

    /**
     * Cache statistics.
     */
    record CacheStats(
            long hits,
            long misses,
            long puts,
            long removes,
            long evictions,
            int size,
            long memoryUsedBytes,
            long memoryTotalBytes,
            long evictionsBySize,
            long evictionsByTtl,
            long evictionsByExplicit) {
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
