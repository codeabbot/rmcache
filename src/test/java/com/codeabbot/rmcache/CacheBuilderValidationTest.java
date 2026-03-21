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

import com.codeabbot.rmcache.eviction.NoEvictionPolicy;
import com.codeabbot.rmcache.serializer.BuiltInSerializers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates CacheBuilder parameter validation, presets, and custom configuration.
 */
public class CacheBuilderValidationTest {

    private OffHeapCache<?, ?> cache;

    @AfterEach
    void tearDown() {
        if (cache != null) {
            cache.close();
            cache = null;
        }
    }

    // --- maxEntries validation ---

    @Test
    void maxEntriesZeroThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new CacheBuilder<String, String>().maxEntries(0));
    }

    @Test
    void maxEntriesNegativeThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new CacheBuilder<String, String>().maxEntries(-1));
    }

    // --- offHeapMemory validation ---

    @Test
    void offHeapMemoryZeroThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new CacheBuilder<String, String>().offHeapMemory(0));
    }

    @Test
    void offHeapMemoryNegativeThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new CacheBuilder<String, String>().offHeapMemory(-100));
    }

    // --- averageValueSize / averageKeySize validation ---

    @Test
    void averageValueSizeZeroThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new CacheBuilder<String, String>().averageValueSize(0));
    }

    @Test
    void averageKeySizeNegativeThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new CacheBuilder<String, String>().averageKeySize(-5));
    }

    // --- valid build with defaults (String key/value) ---

    @Test
    void buildWithDefaultsCreatesUsableCache() {
        cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(8 * 1024 * 1024)
                .build();
        assertNotNull(cache);
        assertEquals(0, cache.size());
    }

    // --- zeroHeapProfile preset ---

    @Test
    void zeroHeapProfileBuildSucceeds() {
        cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(8 * 1024 * 1024)
                .zeroHeapProfile()
                .build();
        assertNotNull(cache);
        // Verify it is functional
        ((OffHeapCache<String, String>) cache).put("k", "v");
        assertEquals("v", ((OffHeapCache<String, String>) cache).get("k"));
    }

    // --- custom evictionPolicy ---

    @Test
    void customEvictionPolicyIsAccepted() {
        cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(8 * 1024 * 1024)
                .eviction(new NoEvictionPolicy())
                .build();
        assertNotNull(cache);
    }

    // --- custom serializers (INT_KEY, INT_VALUE) ---

    @Test
    void intKeyIntValueSerializersBuild() {
        OffHeapCache<Integer, Integer> intCache = new CacheBuilder<Integer, Integer>()
                .maxEntries(1000)
                .offHeapMemory(8 * 1024 * 1024)
                .keySerializer(BuiltInSerializers.INT_KEY)
                .valueSerializer(BuiltInSerializers.INT_VALUE)
                .build();
        cache = intCache;

        intCache.put(42, 100);
        assertEquals(100, intCache.get(42));
        assertNull(intCache.get(99));
    }

    // --- evictionMemoryWatermarks validation ---

    @Test
    void watermarkHighAboveOneThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new CacheBuilder<String, String>().evictionMemoryWatermarks(1.5, 0.8));
    }

    @Test
    void watermarkHighZeroThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new CacheBuilder<String, String>().evictionMemoryWatermarks(0.0, 0.0));
    }

    @Test
    void watermarkLowNegativeThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new CacheBuilder<String, String>().evictionMemoryWatermarks(0.9, -0.1));
    }

    @Test
    void watermarkLowEqualsHighThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new CacheBuilder<String, String>().evictionMemoryWatermarks(0.9, 0.9));
    }

    @Test
    void watermarkLowGreaterThanHighThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new CacheBuilder<String, String>().evictionMemoryWatermarks(0.8, 0.9));
    }

    @Test
    void watermarkLowAtOneThrows() {
        // low >= 1.0 is invalid per the guard: low < 0 || low >= 1.0
        assertThrows(IllegalArgumentException.class, () ->
                new CacheBuilder<String, String>().evictionMemoryWatermarks(1.0, 1.0));
    }

    @Test
    void validWatermarksAccepted() {
        cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(8 * 1024 * 1024)
                .evictionMemoryWatermarks(0.95, 0.85)
                .build();
        assertNotNull(cache);
    }

    // --- ghostCacheMode settings ---

    @Test
    void ghostCacheModeHeapBuildSucceeds() {
        cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(8 * 1024 * 1024)
                .ghostCacheMode(GhostCacheMode.HEAP)
                .build();
        assertNotNull(cache);
    }

    @Test
    void ghostCacheModeOffHeapBuildSucceeds() {
        cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(8 * 1024 * 1024)
                .ghostCacheMode(GhostCacheMode.OFF_HEAP)
                .build();
        assertNotNull(cache);
    }

    @Test
    void ghostCacheModeDisabledBuildSucceeds() {
        cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(8 * 1024 * 1024)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .build();
        assertNotNull(cache);
    }

    @Test
    void ghostCacheModeNullThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new CacheBuilder<String, String>().ghostCacheMode(null));
    }

    @Test
    void ghostCacheSizeZeroDisablesGhostCache() {
        cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(8 * 1024 * 1024)
                .ghostCacheSize(0)
                .build();
        assertNotNull(cache);
    }

    @Test
    void ghostCacheSizeNegativeThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new CacheBuilder<String, String>().ghostCacheSize(-1));
    }

    // --- backgroundEviction configuration ---

    @Test
    void backgroundEvictionDisabledBuildSucceeds() {
        cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(8 * 1024 * 1024)
                .backgroundEviction(false)
                .build();
        assertNotNull(cache);
    }

    @Test
    void backgroundEvictionIntervalAccepted() {
        cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(8 * 1024 * 1024)
                .backgroundEviction(true)
                .backgroundEvictionInterval(Duration.ofMillis(50))
                .build();
        assertNotNull(cache);
    }

    @Test
    void backgroundEvictionIntervalNullThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new CacheBuilder<String, String>().backgroundEvictionInterval(null));
    }

    @Test
    void backgroundEvictionIntervalZeroThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new CacheBuilder<String, String>().backgroundEvictionInterval(Duration.ZERO));
    }

    // --- hashTableStripes validation ---

    @Test
    void hashTableStripesNonPowerOfTwoThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new CacheBuilder<String, String>().hashTableStripes(3));
    }

    @Test
    void hashTableStripesZeroThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new CacheBuilder<String, String>().hashTableStripes(0));
    }

    @Test
    void hashTableStripesPowerOfTwoAccepted() {
        cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(8 * 1024 * 1024)
                .hashTableStripes(16)
                .build();
        assertNotNull(cache);
    }

    // --- entryPoolPartitions validation ---

    @Test
    void entryPoolPartitionsNonPowerOfTwoThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new CacheBuilder<String, String>().entryPoolPartitions(5));
    }

    @Test
    void entryPoolPartitionsZeroThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new CacheBuilder<String, String>().entryPoolPartitions(0));
    }

    // --- hashTableLoadFactor validation ---

    @Test
    void hashTableLoadFactorZeroThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new CacheBuilder<String, String>().hashTableLoadFactor(0.0));
    }

    @Test
    void hashTableLoadFactorOneThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new CacheBuilder<String, String>().hashTableLoadFactor(1.0));
    }

    @Test
    void hashTableLoadFactorNegativeThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new CacheBuilder<String, String>().hashTableLoadFactor(-0.5));
    }

    // --- hashTableInitialCapacity validation ---

    @Test
    void hashTableInitialCapacityZeroThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new CacheBuilder<String, String>().hashTableInitialCapacity(0));
    }

    @Test
    void hashTableInitialCapacityNegativeThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new CacheBuilder<String, String>().hashTableInitialCapacity(-10));
    }

    // --- slabSize validation ---

    @Test
    void slabSizeBelowMinimumThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new CacheBuilder<String, String>().slabSize(2048));
    }

    @Test
    void slabSizeNonPowerOfTwoThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new CacheBuilder<String, String>().slabSize(5000));
    }

    @Test
    void slabSizeValidAccepted() {
        cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(8 * 1024 * 1024)
                .slabSize(4096)
                .build();
        assertNotNull(cache);
    }

    // --- indexMemoryBudgetPercent validation ---

    @Test
    void indexMemoryBudgetPercentZeroThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new CacheBuilder<String, String>().indexMemoryBudgetPercent(0.0));
    }

    @Test
    void indexMemoryBudgetPercentAboveOneThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new CacheBuilder<String, String>().indexMemoryBudgetPercent(1.5));
    }

    @Test
    void indexMemoryBudgetPercentNegativeThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new CacheBuilder<String, String>().indexMemoryBudgetPercent(-0.1));
    }

    // --- indexMemoryBudgetBytes validation ---

    @Test
    void indexMemoryBudgetBytesZeroThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new CacheBuilder<String, String>().indexMemoryBudgetBytes(0));
    }

    @Test
    void indexMemoryBudgetBytesNegativeThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new CacheBuilder<String, String>().indexMemoryBudgetBytes(-100));
    }

    // --- stringKeyEncoding validation ---

    @Test
    void stringKeyEncodingNullThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new CacheBuilder<String, String>().stringKeyEncoding(null));
    }

    // --- memory too small for maxEntries ---

    @Test
    void memoryTooSmallForEntriesThrows() {
        // 1000 entries with default key/value sizes need much more than 1KB
        assertThrows(IllegalArgumentException.class, () ->
                new CacheBuilder<String, String>()
                        .maxEntries(1000)
                        .offHeapMemory(1024)
                        .build());
    }

    // --- estimateMemory does not throw ---

    @Test
    void estimateMemoryReturnsNonNull() {
        var estimate = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .estimateMemory();
        assertNotNull(estimate);
    }

    // --- cacheName and other fluent setters ---

    @Test
    void cacheNameIsAccepted() {
        cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(8 * 1024 * 1024)
                .withCacheName("my-cache")
                .build();
        assertNotNull(cache);
    }

    @Test
    void zeroMemoryOnStartupAccepted() {
        cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(8 * 1024 * 1024)
                .zeroMemoryOnStartup()
                .build();
        assertNotNull(cache);
    }

    @Test
    void prefetchFlagAccepted() {
        cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(8 * 1024 * 1024)
                .prefetch(true)
                .build();
        assertNotNull(cache);
    }
}
