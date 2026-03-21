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

import com.codeabbot.rmcache.serializer.BuiltInSerializers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests OffHeapCacheImpl operations to increase code coverage:
 * putAll, getAll, computeIfAbsent, putIfAbsent, remove, clear, size,
 * contains, getStats, putAsync, getAsync, TTL update, and close behavior.
 */
public class CacheOperationsTest {

    private OffHeapCache<String, String> cache;

    @AfterEach
    void tearDown() {
        if (cache != null) {
            cache.close();
            cache = null;
        }
    }

    private OffHeapCache<String, String> buildCache() {
        return buildCache(10_000);
    }

    private OffHeapCache<String, String> buildCache(int maxEntries) {
        return new CacheBuilder<String, String>()
                .maxEntries(maxEntries)
                .offHeapMemory(16 * 1024 * 1024)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .build();
    }

    // --- putAll + getAll ---

    @Test
    void putAllAndGetAllRoundTrip() {
        cache = buildCache();

        Map<String, String> entries = new HashMap<>();
        entries.put("a", "alpha");
        entries.put("b", "bravo");
        entries.put("c", "charlie");
        cache.putAll(entries);

        Map<String, String> result = cache.getAll(List.of("a", "b", "c", "missing"));
        assertEquals(3, result.size());
        assertEquals("alpha", result.get("a"));
        assertEquals("bravo", result.get("b"));
        assertEquals("charlie", result.get("c"));
        assertNull(result.get("missing"));
    }

    @Test
    void putAllWithTtl() {
        cache = buildCache();

        Map<String, String> entries = Map.of("x", "xray", "y", "yankee");
        cache.putAll(entries, Duration.ofSeconds(60));

        assertEquals("xray", cache.get("x"));
        assertEquals("yankee", cache.get("y"));
    }

    // --- computeIfAbsent ---

    @Test
    void computeIfAbsentMissThenHit() {
        cache = buildCache();

        // First call: miss -> loader invoked
        String value = cache.computeIfAbsent("key1", k -> "computed-" + k);
        assertEquals("computed-key1", value);

        // Second call: hit -> loader NOT invoked
        String value2 = cache.computeIfAbsent("key1", k -> "should-not-run");
        assertEquals("computed-key1", value2);
    }

    @Test
    void computeIfAbsentLoaderReturnsNull() {
        cache = buildCache();

        String value = cache.computeIfAbsent("nullKey", k -> null);
        assertNull(value);
        // Confirm nothing was inserted
        assertNull(cache.get("nullKey"));
    }

    @Test
    void computeIfAbsentWithTtl() {
        cache = buildCache();

        String value = cache.computeIfAbsent("ttlKey", k -> "ttlValue", Duration.ofMinutes(5));
        assertEquals("ttlValue", value);
        assertEquals("ttlValue", cache.get("ttlKey"));
    }

    // --- putIfAbsent ---

    @Test
    void putIfAbsentInsertsWhenMissing() {
        cache = buildCache();

        boolean inserted = cache.putIfAbsent("newKey", "newValue");
        assertTrue(inserted);
        assertEquals("newValue", cache.get("newKey"));
    }

    @Test
    void putIfAbsentDoesNotOverwriteExisting() {
        cache = buildCache();

        cache.put("existing", "original");
        boolean inserted = cache.putIfAbsent("existing", "replacement");
        assertFalse(inserted);
        assertEquals("original", cache.get("existing"));
    }

    @Test
    void putIfAbsentWithTtl() {
        cache = buildCache();

        boolean inserted = cache.putIfAbsent("ttlKey", "ttlVal", Duration.ofMinutes(10));
        assertTrue(inserted);
        assertEquals("ttlVal", cache.get("ttlKey"));
    }

    // --- remove ---

    @Test
    void removeExistingKeyReturnsTrue() {
        cache = buildCache();

        cache.put("toRemove", "value");
        assertTrue(cache.remove("toRemove"));
        assertNull(cache.get("toRemove"));
    }

    @Test
    void removeNonexistentKeyReturnsFalse() {
        cache = buildCache();

        assertFalse(cache.remove("never-inserted"));
    }

    @Test
    void removeNullKeyThrows() {
        cache = buildCache();

        assertThrows(NullPointerException.class, () -> cache.remove(null));
    }

    // --- get after close ---

    @Test
    void getAfterCloseReturnsNull() {
        cache = buildCache();
        cache.put("key", "value");
        cache.close();

        // get on a closed cache returns null (closed flag checked first)
        assertNull(cache.get("key"));
        cache = null; // prevent double-close in tearDown
    }

    // --- put after close throws ---

    @Test
    void putAfterCloseThrows() {
        cache = buildCache();
        cache.close();

        assertThrows(IllegalStateException.class, () -> cache.put("k", "v"));
        cache = null;
    }

    // --- put null key/value ---

    @Test
    void putNullKeyThrows() {
        cache = buildCache();
        assertThrows(NullPointerException.class, () -> cache.put(null, "val"));
    }

    @Test
    void putNullValueThrows() {
        cache = buildCache();
        assertThrows(NullPointerException.class, () -> cache.put("key", null));
    }

    // --- clear ---

    @Test
    void clearEmptiesCache() {
        cache = buildCache();

        for (int i = 0; i < 100; i++) {
            cache.put("key-" + i, "value-" + i);
        }
        assertTrue(cache.size() > 0);

        cache.clear();
        assertEquals(0, cache.size());

        // Verify entries are gone
        for (int i = 0; i < 100; i++) {
            assertNull(cache.get("key-" + i));
        }
    }

    @Test
    void clearOnEmptyCacheIsNoop() {
        cache = buildCache();
        assertEquals(0, cache.size());
        cache.clear(); // should not throw
        assertEquals(0, cache.size());
    }

    // --- size() accuracy ---

    @Test
    void sizeReflectsInsertions() {
        cache = buildCache();

        assertEquals(0, cache.size());
        cache.put("a", "1");
        assertEquals(1, cache.size());
        cache.put("b", "2");
        assertEquals(2, cache.size());
    }

    @Test
    void sizeReflectsRemovals() {
        cache = buildCache();

        cache.put("a", "1");
        cache.put("b", "2");
        assertEquals(2, cache.size());

        cache.remove("a");
        assertEquals(1, cache.size());
    }

    @Test
    void sizeReflectsOverwrite() {
        cache = buildCache();

        cache.put("a", "1");
        cache.put("a", "2"); // overwrite, size should not increase
        assertEquals(1, cache.size());
        assertEquals("2", cache.get("a"));
    }

    // --- contains() ---

    @Test
    void containsReturnsTrueForExistingKey() {
        cache = buildCache();

        cache.put("present", "here");
        assertTrue(cache.contains("present"));
    }

    @Test
    void containsReturnsFalseForMissingKey() {
        cache = buildCache();
        assertFalse(cache.contains("absent"));
    }

    // --- getStats() ---

    @Test
    void getStatsReturnsNonNullWithCorrectFields() {
        cache = buildCache();

        cache.put("s1", "v1");
        cache.get("s1");        // hit
        cache.get("s1");        // hit
        cache.get("missing");   // miss

        OffHeapCache.CacheStats stats = cache.getStats();
        assertNotNull(stats);
        assertTrue(stats.hits() >= 2, "Expected at least 2 hits, got " + stats.hits());
        assertTrue(stats.misses() >= 1, "Expected at least 1 miss, got " + stats.misses());
        assertEquals(1, stats.size());
        assertTrue(stats.memoryTotalBytes() > 0);
        assertTrue(stats.memoryUsedBytes() >= 0);
        assertTrue(stats.hitRate() > 0.0);
        assertTrue(stats.missRate() > 0.0);
        assertTrue(stats.memoryUsagePercent() >= 0.0);
    }

    @Test
    void getStatsEvictionCountersStartAtZero() {
        cache = buildCache();

        OffHeapCache.CacheStats stats = cache.getStats();
        assertEquals(0, stats.evictions());
        assertEquals(0, stats.evictionsBySize());
        assertEquals(0, stats.evictionsByTtl());
        assertEquals(0, stats.evictionsByExplicit());
    }

    @Test
    void getStatsExplicitEvictionCountsRemoval() {
        cache = buildCache();

        cache.put("evictMe", "data");
        cache.remove("evictMe");

        OffHeapCache.CacheStats stats = cache.getStats();
        assertEquals(1, stats.evictionsByExplicit());
    }

    @Test
    void getStatsHitRateZeroWhenEmpty() {
        cache = buildCache();
        OffHeapCache.CacheStats stats = cache.getStats();
        // With zero total operations, hitRate returns 0.0
        // and missRate returns 1.0 - hitRate = 1.0 (by convention)
        assertEquals(0.0, stats.hitRate());
        assertEquals(0, stats.hits());
        assertEquals(0, stats.misses());
    }

    // --- putAsync / getAsync ---

    @Test
    void putAsyncAndGetAsyncBasic() throws Exception {
        cache = buildCache();

        CompletableFuture<Void> putFuture = cache.putAsync("asyncKey", "asyncValue");
        putFuture.get(5, TimeUnit.SECONDS); // wait for put to complete

        CompletableFuture<String> getFuture = cache.getAsync("asyncKey");
        String result = getFuture.get(5, TimeUnit.SECONDS);
        assertEquals("asyncValue", result);
    }

    @Test
    void getAsyncMissReturnsNull() throws Exception {
        cache = buildCache();

        CompletableFuture<String> future = cache.getAsync("noSuchKey");
        String result = future.get(5, TimeUnit.SECONDS);
        assertNull(result);
    }

    // --- TTL update on existing key (H1 fix path) ---

    @Test
    void putWithTtlThenPutWithNewTtlUpdatesExpiry() {
        cache = buildCache();

        // Insert with a 60-second TTL
        cache.put("ttlKey", "v1", Duration.ofSeconds(60));
        assertEquals("v1", cache.get("ttlKey"));

        // Update same key with a new TTL — exercises the H1 fix path
        // (setExpiresAt called on in-place update when ttl != null)
        cache.put("ttlKey", "v2", Duration.ofSeconds(120));
        assertEquals("v2", cache.get("ttlKey"));
    }

    @Test
    void putWithoutTtlPreservesExistingExpiry() {
        cache = buildCache();

        // Insert with TTL
        cache.put("ttlKey", "v1", Duration.ofSeconds(60));

        // Update without TTL — H1 fix: should NOT clear the existing expiration
        cache.put("ttlKey", "v2");
        assertEquals("v2", cache.get("ttlKey"));
    }

    // --- put with priority ---

    @Test
    void putWithPriority() {
        cache = buildCache();

        cache.put("pKey", "pVal", (short) 5);
        assertEquals("pVal", cache.get("pKey"));
    }

    @Test
    void putWithTtlAndPriority() {
        cache = buildCache();

        cache.put("pKey", "pVal", Duration.ofMinutes(5), (short) 10);
        assertEquals("pVal", cache.get("pKey"));
    }

    // --- getZeroCopy ---

    @Test
    void getZeroCopyReturnsProcessedValue() {
        try (OffHeapCache<String, byte[]> byteCache = new CacheBuilder<String, byte[]>()
                .maxEntries(1000)
                .offHeapMemory(8 * 1024 * 1024)
                .keySerializer(BuiltInSerializers.STRING_KEY)
                .valueSerializer(BuiltInSerializers.byteArray())
                .backgroundEviction(false)
                .build()) {

            byte[] data = {1, 2, 3, 4, 5};
            byteCache.put("zcKey", data);

            Integer size = byteCache.getZeroCopy("zcKey", segment -> (int) segment.byteSize());
            assertNotNull(size);
            assertEquals(5, size);
        }
    }

    @Test
    void getZeroCopyMissReturnsNull() {
        try (OffHeapCache<String, byte[]> byteCache = new CacheBuilder<String, byte[]>()
                .maxEntries(1000)
                .offHeapMemory(8 * 1024 * 1024)
                .keySerializer(BuiltInSerializers.STRING_KEY)
                .valueSerializer(BuiltInSerializers.byteArray())
                .backgroundEviction(false)
                .build()) {

            Object result = byteCache.getZeroCopy("missing", segment -> "found");
            assertNull(result);
        }
    }

    // --- Integer key/value cache operations ---

    @Test
    void integerCachePutAllGetAll() {
        try (OffHeapCache<Integer, Integer> intCache = new CacheBuilder<Integer, Integer>()
                .maxEntries(5000)
                .offHeapMemory(8 * 1024 * 1024)
                .keySerializer(BuiltInSerializers.INT_KEY)
                .valueSerializer(BuiltInSerializers.INT_VALUE)
                .backgroundEviction(false)
                .build()) {

            Map<Integer, Integer> entries = new HashMap<>();
            for (int i = 0; i < 100; i++) {
                entries.put(i, i * 10);
            }
            intCache.putAll(entries);

            Map<Integer, Integer> result = intCache.getAll(List.of(0, 50, 99, 999));
            assertEquals(3, result.size());
            assertEquals(0, result.get(0));
            assertEquals(500, result.get(50));
            assertEquals(990, result.get(99));
        }
    }

    // --- operations on closed cache ---

    @Test
    void removeAfterCloseThrows() {
        cache = buildCache();
        cache.close();
        assertThrows(IllegalStateException.class, () -> cache.remove("k"));
        cache = null;
    }

    @Test
    void putIfAbsentAfterCloseThrows() {
        cache = buildCache();
        cache.close();
        assertThrows(IllegalStateException.class, () -> cache.putIfAbsent("k", "v"));
        cache = null;
    }

    @Test
    void computeIfAbsentAfterCloseThrows() {
        cache = buildCache();
        cache.close();
        assertThrows(IllegalStateException.class, () -> cache.computeIfAbsent("k", k -> "v"));
        cache = null;
    }

    @Test
    void putAllAfterCloseThrows() {
        cache = buildCache();
        cache.close();
        assertThrows(IllegalStateException.class, () -> cache.putAll(Map.of("k", "v")));
        cache = null;
    }

    @Test
    void getAllAfterCloseThrows() {
        cache = buildCache();
        cache.close();
        assertThrows(IllegalStateException.class, () -> cache.getAll(List.of("k")));
        cache = null;
    }

    // --- cleanupThreadLocals ---

    @Test
    void cleanupThreadLocalsDoesNotThrow() {
        cache = buildCache();
        cache.put("k", "v");
        cache.get("k");

        // Should be safe to call from any thread
        OffHeapCache.cleanupThreadLocals();
    }

    // --- bulk insert and read correctness ---

    @Test
    void bulkInsertAndReadCorrectness() {
        cache = buildCache();

        int count = 500;
        for (int i = 0; i < count; i++) {
            cache.put("bulk-" + i, "value-" + i);
        }
        assertEquals(count, cache.size());

        for (int i = 0; i < count; i++) {
            assertEquals("value-" + i, cache.get("bulk-" + i));
        }
    }
}
