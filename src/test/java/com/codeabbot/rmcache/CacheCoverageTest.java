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

import com.codeabbot.rmcache.eviction.EvictionCause;
import com.codeabbot.rmcache.eviction.EvictionListener;
import com.codeabbot.rmcache.eviction.Priority;
import com.codeabbot.rmcache.serializer.BuiltInSerializers;
import com.codeabbot.rmcache.serializer.StreamingSerializer;
import com.codeabbot.rmcache.serializer.StringEncoding;
import com.codeabbot.rmcache.serializer.ValueSerializer;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Targeted coverage tests for OffHeapCacheImpl — covers getZeroCopy, getView,
 * async methods, eviction listener paths, remove, close, putIfAbsent,
 * computeIfAbsent, putAll/getAll, clear, and eviction filter paths.
 */
public class CacheCoverageTest {

    private static final long MB8 = 8L * 1024 * 1024;
    private static final long MB16 = 16L * 1024 * 1024;

    // ── getZeroCopy ──────────────────────────────────────────────────────────

    @Test
    void getZeroCopy_returnsProcessedValue() {
        try (OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .build()) {
            cache.put("key1", "hello");

            Integer len = cache.getZeroCopy("key1", seg -> (int) seg.byteSize());
            assertNotNull(len);
            assertTrue(len > 0);
        }
    }

    @Test
    void getZeroCopy_returnsNullForMissingKey() {
        try (OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .build()) {

            Object result = cache.getZeroCopy("nonexistent", seg -> "found");
            assertNull(result);
        }
    }

    @Test
    void getZeroCopy_withOffHeapGhostCache() {
        try (OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.OFF_HEAP)
                .ghostCacheSize(128)
                .build()) {
            cache.put("key1", "value1");
            // First get populates ghost cache
            assertEquals("value1", cache.get("key1"));
            // getZeroCopy should work with ghost cache hint
            Integer len = cache.getZeroCopy("key1", seg -> (int) seg.byteSize());
            assertNotNull(len);
            assertTrue(len > 0);
        }
    }

    @Test
    void getZeroCopy_expiredEntry_returnsNull() throws Exception {
        try (OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .build()) {
            cache.put("ttl-key", "ttl-value", Duration.ofMillis(50));
            Thread.sleep(100);

            Object result = cache.getZeroCopy("ttl-key", seg -> "found");
            assertNull(result);
        }
    }

    // ── getView ──────────────────────────────────────────────────────────────

    @Test
    void getView_returnsValidView() {
        try (OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .build()) {
            cache.put("view-key", "view-value");

            try (CacheValueView view = cache.getView("view-key")) {
                assertNotNull(view);
                assertTrue(view.isValid());
                assertTrue(view.size() > 0);
                byte[] bytes = view.toByteArray();
                assertNotNull(bytes);
                assertEquals(view.size(), bytes.length);
            }
        }
    }

    @Test
    void getView_returnsNullForMissingKey() {
        try (OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .build()) {

            CacheValueView view = cache.getView("no-such-key");
            assertNull(view);
        }
    }

    @Test
    void getView_expiredEntry_returnsNull() throws Exception {
        try (OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .build()) {
            cache.put("exp-key", "exp-value", Duration.ofMillis(50));
            Thread.sleep(100);

            CacheValueView view = cache.getView("exp-key");
            assertNull(view);
        }
    }

    @Test
    void getView_withOffHeapGhostCache() {
        try (OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.OFF_HEAP)
                .ghostCacheSize(128)
                .build()) {
            cache.put("gk", "gv");
            cache.get("gk"); // populate ghost cache
            try (CacheValueView view = cache.getView("gk")) {
                assertNotNull(view);
                assertTrue(view.size() > 0);
            }
        }
    }

    @Test
    void getView_segment_and_structured_reads() {
        try (OffHeapCache<String, byte[]> cache = new CacheBuilder<String, byte[]>()
                .maxEntries(1000)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .keySerializer(BuiltInSerializers.STRING_KEY)
                .valueSerializer(BuiltInSerializers.byteArray())
                .build()) {
            // Write 16 bytes: an int, a long, and 4 extra bytes
            byte[] data = new byte[16];
            MemorySegment.ofArray(data).set(ValueLayout.JAVA_INT_UNALIGNED, 0, 42);
            MemorySegment.ofArray(data).set(ValueLayout.JAVA_LONG_UNALIGNED, 4, 123456789L);
            data[12] = (byte) 0xAB;

            cache.put("struct-key", data);
            try (CacheValueView view = cache.getView("struct-key")) {
                assertNotNull(view);
                assertEquals(16, view.size());

                // segment() access
                MemorySegment seg = view.segment();
                assertNotNull(seg);

                // getInt and getLong
                assertEquals(42, view.getInt(0));
                assertEquals(123456789L, view.getLong(4));
                assertEquals((byte) 0xAB, view.getByte(12));

                // slice
                byte[] sliced = view.slice(0, 4);
                assertEquals(4, sliced.length);

                // copyTo
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                try {
                    view.copyTo(bos, 8);
                } catch (IOException e) {
                    fail("copyTo should not throw: " + e.getMessage());
                }
                assertEquals(16, bos.size());
            }
        }
    }

    @Test
    void getView_boundsChecks() {
        try (OffHeapCache<String, byte[]> cache = new CacheBuilder<String, byte[]>()
                .maxEntries(1000)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .keySerializer(BuiltInSerializers.STRING_KEY)
                .valueSerializer(BuiltInSerializers.byteArray())
                .build()) {
            cache.put("bk", new byte[8]);
            try (CacheValueView view = cache.getView("bk")) {
                assertNotNull(view);
                // Out of bounds getInt
                assertThrows(IndexOutOfBoundsException.class, () -> view.getInt(7));
                // Out of bounds getLong
                assertThrows(IndexOutOfBoundsException.class, () -> view.getLong(5));
                // Negative offset
                assertThrows(IndexOutOfBoundsException.class, () -> view.getByte(-1));
                // Slice past end
                assertThrows(IllegalArgumentException.class, () -> view.slice(4, 8));
            }
        }
    }

    @Test
    void getView_closedView_throwsIllegalState() {
        try (OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .build()) {
            cache.put("ck", "cv");
            CacheValueView view = cache.getView("ck");
            assertNotNull(view);
            view.close();
            assertFalse(view.isValid());
            assertThrows(IllegalStateException.class, view::toByteArray);
            // Double close is safe
            view.close();
        }
    }

    // ── Async methods ────────────────────────────────────────────────────────

    @Test
    void putAsync_and_getAsync() throws Exception {
        try (OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .build()) {
            cache.putAsync("akey", "avalue").get(5, TimeUnit.SECONDS);
            String result = cache.getAsync("akey").get(5, TimeUnit.SECONDS);
            assertEquals("avalue", result);
        }
    }

    @Test
    void putAsync_and_getAsync_withCustomExecutor() throws Exception {
        ExecutorService exec = Executors.newFixedThreadPool(2);
        try (OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .asyncExecutor(exec)
                .build()) {
            cache.putAsync("akey2", "avalue2").get(5, TimeUnit.SECONDS);
            String result = cache.getAsync("akey2").get(5, TimeUnit.SECONDS);
            assertEquals("avalue2", result);
        } finally {
            exec.shutdown();
        }
    }

    @Test
    void getAsync_missingKey_returnsNull() throws Exception {
        try (OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .build()) {
            String result = cache.getAsync("missing").get(5, TimeUnit.SECONDS);
            assertNull(result);
        }
    }

    // ── Eviction listener ────────────────────────────────────────────────────

    @Test
    void evictionListener_firesOnRemove() {
        AtomicReference<String> evictedKey = new AtomicReference<>();
        AtomicReference<EvictionCause> evictedCause = new AtomicReference<>();
        AtomicReference<String> evictedValue = new AtomicReference<>();

        try (OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .evictionListener((key, valueSup, cause) -> {
                    evictedKey.set(key);
                    evictedValue.set(valueSup.get());
                    evictedCause.set(cause);
                })
                .build()) {
            cache.put("ekey", "evalue");
            assertTrue(cache.remove("ekey"));

            assertEquals("ekey", evictedKey.get());
            assertEquals("evalue", evictedValue.get());
            assertEquals(EvictionCause.EXPLICIT, evictedCause.get());
        }
    }

    @Test
    void evictionListener_firesOnSizeEviction() {
        AtomicInteger evictionCount = new AtomicInteger(0);

        try (OffHeapCache<String, byte[]> cache = new CacheBuilder<String, byte[]>()
                .maxEntries(100)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .keySerializer(BuiltInSerializers.STRING_KEY)
                .valueSerializer(BuiltInSerializers.byteArray())
                .evictionListener((key, valueSup, cause) -> evictionCount.incrementAndGet())
                .build()) {
            byte[] value = new byte[1024];
            for (int i = 0; i < 200; i++) {
                cache.put("k" + i, value);
            }
            // Some entries must have been evicted
            assertTrue(evictionCount.get() > 0);
        }
    }

    // ── Eviction filter ──────────────────────────────────────────────────────

    @Test
    void evictionFilter_protectsEntries() {
        try (OffHeapCache<String, byte[]> cache = new CacheBuilder<String, byte[]>()
                .maxEntries(50)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .keySerializer(BuiltInSerializers.STRING_KEY)
                .valueSerializer(BuiltInSerializers.byteArray())
                .evictionFilter((key, meta) -> !key.startsWith("protected"))
                .build()) {
            // Put protected entries first
            byte[] value = new byte[512];
            for (int i = 0; i < 10; i++) {
                cache.put("protected-" + i, value);
            }
            // Fill cache to trigger eviction
            for (int i = 0; i < 100; i++) {
                cache.put("normal-" + i, value);
            }
            // At least some protected entries should survive
            int protectedSurvivors = 0;
            for (int i = 0; i < 10; i++) {
                if (cache.get("protected-" + i) != null) {
                    protectedSurvivors++;
                }
            }
            assertTrue(protectedSurvivors > 0, "Some protected entries should survive eviction");
        }
    }

    // ── remove ───────────────────────────────────────────────────────────────

    @Test
    void remove_existingKey_returnsTrue() {
        try (OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .build()) {
            cache.put("rk", "rv");
            assertTrue(cache.remove("rk"));
            assertNull(cache.get("rk"));
        }
    }

    @Test
    void remove_missingKey_returnsFalse() {
        try (OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .build()) {
            assertFalse(cache.remove("nonexistent"));
        }
    }

    // ── putIfAbsent ──────────────────────────────────────────────────────────

    @Test
    void putIfAbsent_insertsOnlyOnce() {
        try (OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .build()) {
            assertTrue(cache.putIfAbsent("pk", "v1"));
            assertFalse(cache.putIfAbsent("pk", "v2"));
            assertEquals("v1", cache.get("pk"));
        }
    }

    @Test
    void putIfAbsent_withTTL() {
        try (OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .build()) {
            assertTrue(cache.putIfAbsent("pk2", "v1", Duration.ofSeconds(30)));
            assertFalse(cache.putIfAbsent("pk2", "v2", Duration.ofSeconds(30)));
        }
    }

    // ── computeIfAbsent ──────────────────────────────────────────────────────

    @Test
    void computeIfAbsent_computesOnMiss() {
        try (OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .build()) {
            String result = cache.computeIfAbsent("ck", k -> "computed-" + k);
            assertEquals("computed-ck", result);
            // Second call returns existing
            String result2 = cache.computeIfAbsent("ck", k -> "should-not-compute");
            assertEquals("computed-ck", result2);
        }
    }

    @Test
    void computeIfAbsent_withTTL() {
        try (OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .build()) {
            String result = cache.computeIfAbsent("ck2", k -> "val", Duration.ofSeconds(30));
            assertEquals("val", result);
        }
    }

    @Test
    void computeIfAbsent_nullLoader_returnsNull() {
        try (OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .build()) {
            String result = cache.computeIfAbsent("ck3", k -> null);
            assertNull(result);
        }
    }

    // ── putAll / getAll ──────────────────────────────────────────────────────

    @Test
    void putAll_and_getAll() {
        try (OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .build()) {
            Map<String, String> entries = Map.of("a", "1", "b", "2", "c", "3");
            cache.putAll(entries);

            Map<String, String> result = cache.getAll(List.of("a", "b", "c", "d"));
            assertEquals(3, result.size());
            assertEquals("1", result.get("a"));
            assertNull(result.get("d"));
        }
    }

    @Test
    void putAll_withTTL() throws Exception {
        try (OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .build()) {
            Map<String, String> entries = Map.of("a", "1", "b", "2");
            cache.putAll(entries, Duration.ofMillis(50));
            assertNotNull(cache.get("a"));
            Thread.sleep(100);
            assertNull(cache.get("a"));
        }
    }

    // ── put with priority ────────────────────────────────────────────────────

    @Test
    void put_withPriority() {
        try (OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .build()) {
            cache.put("pk", "pv", Priority.HIGH);
            assertEquals("pv", cache.get("pk"));
        }
    }

    @Test
    void put_withTTLAndPriority() {
        try (OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .build()) {
            cache.put("pk2", "pv2", Duration.ofSeconds(60), Priority.CRITICAL);
            assertEquals("pv2", cache.get("pk2"));
        }
    }

    // ── clear ────────────────────────────────────────────────────────────────

    @Test
    void clear_removesAllEntries() {
        try (OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .build()) {
            for (int i = 0; i < 50; i++) {
                cache.put("ck" + i, "cv" + i);
            }
            assertEquals(50, cache.size());
            cache.clear();
            assertEquals(0, cache.size());
            assertNull(cache.get("ck0"));
        }
    }

    // ── contains ─────────────────────────────────────────────────────────────

    @Test
    void contains_basic() {
        try (OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .build()) {
            cache.put("ck", "cv");
            assertTrue(cache.contains("ck"));
            assertFalse(cache.contains("nope"));
        }
    }

    // ── Stats ────────────────────────────────────────────────────────────────

    @Test
    void getStats_allFields() {
        try (OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .build()) {
            cache.put("sk", "sv");
            cache.get("sk");
            cache.get("miss");
            cache.remove("sk");

            OffHeapCache.CacheStats stats = cache.getStats();
            assertTrue(stats.hits() >= 1);
            assertTrue(stats.misses() >= 1);
            assertTrue(stats.memoryTotalBytes() > 0);
            assertTrue(stats.hitRate() > 0);
            assertTrue(stats.missRate() > 0);
            assertTrue(stats.memoryUsagePercent() >= 0);
            assertTrue(stats.evictionsByExplicit() >= 1);
        }
    }

    // ── Close behavior ───────────────────────────────────────────────────────

    @Test
    void closedCache_putThrows() {
        OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .build();
        cache.close();
        assertThrows(IllegalStateException.class, () -> cache.put("k", "v"));
    }

    @Test
    void closedCache_getReturnsNull() {
        OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .build();
        cache.put("k", "v");
        cache.close();
        // get on closed cache returns null (does not throw)
        assertNull(cache.get("k"));
    }

    // ── cleanupThreadLocals ──────────────────────────────────────────────────

    @Test
    void cleanupThreadLocals_doesNotThrow() {
        OffHeapCache.cleanupThreadLocals();
    }

    // ── Null key/value ───────────────────────────────────────────────────────

    @Test
    void put_nullKey_throws() {
        try (OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .build()) {
            assertThrows(NullPointerException.class, () -> cache.put(null, "v"));
        }
    }

    @Test
    void put_nullValue_throws() {
        try (OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .build()) {
            assertThrows(NullPointerException.class, () -> cache.put("k", null));
        }
    }

    @Test
    void get_nullKey_throws() {
        try (OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .build()) {
            assertThrows(NullPointerException.class, () -> cache.get(null));
        }
    }

    @Test
    void remove_nullKey_throws() {
        try (OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .build()) {
            assertThrows(NullPointerException.class, () -> cache.remove(null));
        }
    }

    // ── put update (existing key) ────────────────────────────────────────────

    @Test
    void put_updateExistingKey() {
        try (OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .build()) {
            cache.put("uk", "v1");
            assertEquals("v1", cache.get("uk"));
            cache.put("uk", "v2");
            assertEquals("v2", cache.get("uk"));
        }
    }

    @Test
    void put_updateExistingKey_withNewTTL() {
        try (OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .build()) {
            cache.put("uk2", "v1");
            cache.put("uk2", "v2", Duration.ofSeconds(60));
            assertEquals("v2", cache.get("uk2"));
        }
    }

    // ── byte[] value serializer (SegmentValueSerializer path) ────────────────

    @Test
    void byteArraySerializer_putGetRemove() {
        try (OffHeapCache<String, byte[]> cache = new CacheBuilder<String, byte[]>()
                .maxEntries(1000)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .keySerializer(BuiltInSerializers.STRING_KEY)
                .valueSerializer(BuiltInSerializers.byteArray())
                .build()) {
            byte[] data = new byte[]{1, 2, 3, 4, 5};
            cache.put("bk", data);
            byte[] result = cache.get("bk");
            assertArrayEquals(data, result);
            assertTrue(cache.remove("bk"));
            assertNull(cache.get("bk"));
        }
    }

    @Test
    void byteArraySerializer_zeroCopy() {
        try (OffHeapCache<String, byte[]> cache = new CacheBuilder<String, byte[]>()
                .maxEntries(1000)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .keySerializer(BuiltInSerializers.STRING_KEY)
                .valueSerializer(BuiltInSerializers.byteArray())
                .build()) {
            byte[] data = new byte[]{10, 20, 30};
            cache.put("bk", data);
            Integer size = cache.getZeroCopy("bk", seg -> (int) seg.byteSize());
            assertNotNull(size);
            assertEquals(data.length, size);
        }
    }

    // ── Background eviction ──────────────────────────────────────────────────

    @Test
    void backgroundEviction_triggersWhenMemoryHigh() throws Exception {
        try (OffHeapCache<String, byte[]> cache = new CacheBuilder<String, byte[]>()
                .maxEntries(10000)
                .offHeapMemory(MB8)
                .backgroundEviction(true)
                .backgroundEvictionInterval(Duration.ofMillis(5))
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .keySerializer(BuiltInSerializers.STRING_KEY)
                .valueSerializer(BuiltInSerializers.byteArray())
                .build()) {
            byte[] value = new byte[4096];
            for (int i = 0; i < 5000; i++) {
                cache.put("bgk-" + i, value);
            }
            // Wait for background eviction to kick in
            Thread.sleep(100);
            // Cache should still be functional
            cache.put("after", value);
            assertNotNull(cache.get("after"));
        }
    }

    // ── Cache with offHeapGhostCache ─────────────────────────────────────────

    @Test
    void offHeapGhostCache_putAndGet() {
        try (OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.OFF_HEAP)
                .ghostCacheSize(128)
                .build()) {
            cache.put("ogk", "ogv");
            assertEquals("ogv", cache.get("ogk"));
            // Second get should come from ghost cache path
            assertEquals("ogv", cache.get("ogk"));
        }
    }

    // ── Cache with heap ghost cache ──────────────────────────────────────────

    @Test
    void heapGhostCache_putAndGet() {
        try (OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.HEAP)
                .ghostCacheSize(128)
                .build()) {
            cache.put("hgk", "hgv");
            assertEquals("hgv", cache.get("hgk"));
            // Second get should use heap ghost cache
            assertEquals("hgv", cache.get("hgk"));
            // Remove should invalidate ghost
            assertTrue(cache.remove("hgk"));
            assertNull(cache.get("hgk"));
        }
    }

    // ── Eviction watermarks ──────────────────────────────────────────────────

    @Test
    void evictionMemoryWatermarks_applied() {
        try (OffHeapCache<String, byte[]> cache = new CacheBuilder<String, byte[]>()
                .maxEntries(5000)
                .offHeapMemory(MB8)
                .backgroundEviction(true)
                .backgroundEvictionInterval(Duration.ofMillis(5))
                .evictionMemoryWatermarks(0.90, 0.80)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .keySerializer(BuiltInSerializers.STRING_KEY)
                .valueSerializer(BuiltInSerializers.byteArray())
                .build()) {
            byte[] value = new byte[2048];
            for (int i = 0; i < 3000; i++) {
                cache.put("wk-" + i, value);
            }
            assertTrue(cache.size() > 0);
        }
    }

    // ── zeroHeapProfile builder ──────────────────────────────────────────────

    @Test
    void zeroHeapProfile_buildsSuccessfully() {
        try (OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(MB8)
                .zeroHeapProfile()
                .build()) {
            cache.put("zhk", "zhv");
            assertEquals("zhv", cache.get("zhk"));
        }
    }

    // ── estimateMemory ───────────────────────────────────────────────────────

    @Test
    void estimateMemory_basic() {
        CacheBuilder<String, String> builder = new CacheBuilder<String, String>()
                .maxEntries(10000)
                .averageKeySize(16)
                .averageValueSize(128);

        var estimate = builder.estimateMemory();
        assertTrue(estimate.totalBytes() > 0);
        assertTrue(estimate.dataBytes() > 0);
        assertTrue(estimate.hashTableBytes() > 0);
        assertTrue(estimate.bytesPerEntry() > 0);
    }

    @Test
    void estimateMemory_withIndexBudget() {
        CacheBuilder<String, String> builder = new CacheBuilder<String, String>()
                .maxEntries(10000)
                .averageKeySize(16)
                .averageValueSize(128)
                .offHeapMemory(MB16)
                .indexMemoryBudgetPercent(0.3);

        var estimate = builder.estimateMemory();
        assertTrue(estimate.totalBytes() > 0);
    }

    @Test
    void estimateMemory_withIndexBudgetBytes() {
        CacheBuilder<String, String> builder = new CacheBuilder<String, String>()
                .maxEntries(10000)
                .averageKeySize(16)
                .averageValueSize(128)
                .indexMemoryBudgetBytes(4 * 1024 * 1024);

        var estimate = builder.estimateMemory();
        assertTrue(estimate.totalBytes() > 0);
    }

    // ── StreamingSerializer path (putInternal + readValueFromSlot) ───────────

    /**
     * Custom StreamingSerializer that exercises the streaming serializer code path
     * in putInternal (lines 373-381) and readValueFromSlot (lines 654-662).
     * This is the single biggest uncovered code path (~150+ instructions).
     */
    static final StreamingSerializer<String> STREAMING_STRING = new StreamingSerializer<>() {
        @Override
        public int estimateSize(String value) {
            return value.length() * 3; // worst-case UTF-8
        }

        @Override
        public int serializeTo(String value, byte[] dest, int offset) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            System.arraycopy(bytes, 0, dest, offset, bytes.length);
            return bytes.length;
        }

        @Override
        public String deserializeFrom(byte[] src, int offset, int length) {
            return new String(src, offset, length, StandardCharsets.UTF_8);
        }

        @Override
        public byte[] serialize(String value) {
            return value.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public String deserialize(byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    };

    @Test
    void streamingSerializer_putAndGet() {
        try (OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .valueSerializer(STREAMING_STRING)
                .build()) {
            cache.put("ssk1", "streaming-value-1");
            assertEquals("streaming-value-1", cache.get("ssk1"));

            // Update existing key (exercises streaming update path)
            cache.put("ssk1", "streaming-updated");
            assertEquals("streaming-updated", cache.get("ssk1"));
        }
    }

    @Test
    void streamingSerializer_putWithTTL() {
        try (OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .valueSerializer(STREAMING_STRING)
                .build()) {
            cache.put("ssk2", "streaming-ttl", Duration.ofSeconds(60));
            assertEquals("streaming-ttl", cache.get("ssk2"));
        }
    }

    @Test
    void streamingSerializer_withOffHeapGhost() {
        try (OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.OFF_HEAP)
                .ghostCacheSize(128)
                .valueSerializer(STREAMING_STRING)
                .build()) {
            cache.put("ssg1", "ghost-streaming");
            assertEquals("ghost-streaming", cache.get("ssg1"));
            // Second get exercises ghost cache hit + streaming deserialization
            assertEquals("ghost-streaming", cache.get("ssg1"));
        }
    }

    @Test
    void streamingSerializer_remove() {
        try (OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .valueSerializer(STREAMING_STRING)
                .build()) {
            cache.put("ssr1", "to-remove");
            assertTrue(cache.remove("ssr1"));
            assertNull(cache.get("ssr1"));
        }
    }

    @Test
    void streamingSerializer_evictionUnderPressure() {
        try (OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(100)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .valueSerializer(STREAMING_STRING)
                .build()) {
            // Fill beyond capacity to trigger eviction path with streaming serializer
            for (int i = 0; i < 200; i++) {
                cache.put("sse-" + i, "eviction-value-" + i);
            }
            assertTrue(cache.size() > 0);
            assertTrue(cache.size() <= 100);
        }
    }

    @Test
    void streamingSerializer_getZeroCopy() {
        try (OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .valueSerializer(STREAMING_STRING)
                .build()) {
            cache.put("szc1", "zero-copy-streaming");
            Integer size = cache.getZeroCopy("szc1", seg -> (int) seg.byteSize());
            assertNotNull(size);
            assertTrue(size > 0);
        }
    }

    @Test
    void streamingSerializer_getView() {
        try (OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .valueSerializer(STREAMING_STRING)
                .build()) {
            cache.put("svk1", "view-streaming");
            try (CacheValueView view = cache.getView("svk1")) {
                assertNotNull(view);
                assertTrue(view.size() > 0);
            }
        }
    }

    @Test
    void streamingSerializer_putIfAbsent() {
        try (OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .valueSerializer(STREAMING_STRING)
                .build()) {
            assertTrue(cache.putIfAbsent("spia1", "first"));
            assertFalse(cache.putIfAbsent("spia1", "second"));
            assertEquals("first", cache.get("spia1"));
        }
    }

    @Test
    void streamingSerializer_computeIfAbsent() {
        try (OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .valueSerializer(STREAMING_STRING)
                .build()) {
            String result = cache.computeIfAbsent("scia1", k -> "computed-" + k);
            assertEquals("computed-scia1", result);
        }
    }

    @Test
    void streamingSerializer_asyncOps() throws Exception {
        try (OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .valueSerializer(STREAMING_STRING)
                .build()) {
            cache.putAsync("sak1", "async-stream").get(5, TimeUnit.SECONDS);
            String result = cache.getAsync("sak1").get(5, TimeUnit.SECONDS);
            assertEquals("async-stream", result);
        }
    }

    // ── CacheBuilder validation paths ───────────────────────────────────────

    @Test
    void cacheBuilder_withCacheName() {
        try (OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .withCacheName("test-cache")
                .build()) {
            cache.put("nk", "nv");
            assertEquals("nv", cache.get("nk"));
        }
    }

    @Test
    void cacheBuilder_slabSize() {
        try (OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .slabSize(8192)
                .build()) {
            cache.put("slk", "slv");
            assertEquals("slv", cache.get("slk"));
        }
    }

    @Test
    void cacheBuilder_slabSize_tooSmall_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new CacheBuilder<String, String>().slabSize(1024));
    }

    @Test
    void cacheBuilder_slabSize_notPowerOf2_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new CacheBuilder<String, String>().slabSize(5000));
    }

    @Test
    void cacheBuilder_zeroMemoryOnStartup() {
        try (OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .zeroMemoryOnStartup()
                .build()) {
            cache.put("zmk", "zmv");
            assertEquals("zmv", cache.get("zmk"));
        }
    }

    @Test
    void cacheBuilder_hashTableInitialCapacity() {
        try (OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .hashTableInitialCapacity(512)
                .build()) {
            cache.put("htk", "htv");
            assertEquals("htv", cache.get("htk"));
        }
    }

    @Test
    void cacheBuilder_hashTableInitialCapacity_invalid_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new CacheBuilder<String, String>().hashTableInitialCapacity(0));
    }

    @Test
    void cacheBuilder_hashTableLoadFactor_invalid_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new CacheBuilder<String, String>().hashTableLoadFactor(0.0));
        assertThrows(IllegalArgumentException.class,
                () -> new CacheBuilder<String, String>().hashTableLoadFactor(1.0));
    }

    @Test
    void cacheBuilder_maxEntries_invalid_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new CacheBuilder<String, String>().maxEntries(0));
    }

    @Test
    void cacheBuilder_averageValueSize_invalid_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new CacheBuilder<String, String>().averageValueSize(0));
    }

    @Test
    void cacheBuilder_averageKeySize_invalid_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new CacheBuilder<String, String>().averageKeySize(0));
    }

    @Test
    void cacheBuilder_offHeapMemory_invalid_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new CacheBuilder<String, String>().offHeapMemory(0));
    }

    @Test
    void cacheBuilder_hashTableStripes_invalid_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new CacheBuilder<String, String>().hashTableStripes(3));
    }

    @Test
    void cacheBuilder_entryPoolPartitions_invalid_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new CacheBuilder<String, String>().entryPoolPartitions(3));
    }

    @Test
    void cacheBuilder_ghostCacheSize_negative_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new CacheBuilder<String, String>().ghostCacheSize(-1));
    }

    @Test
    void cacheBuilder_ghostCacheMode_null_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new CacheBuilder<String, String>().ghostCacheMode(null));
    }

    @Test
    void cacheBuilder_stringKeyEncoding_null_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new CacheBuilder<String, String>().stringKeyEncoding(null));
    }

    @Test
    void cacheBuilder_indexMemoryBudgetBytes_invalid_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new CacheBuilder<String, String>().indexMemoryBudgetBytes(0));
    }

    @Test
    void cacheBuilder_indexMemoryBudgetPercent_invalid_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new CacheBuilder<String, String>().indexMemoryBudgetPercent(0.0));
        assertThrows(IllegalArgumentException.class,
                () -> new CacheBuilder<String, String>().indexMemoryBudgetPercent(1.1));
    }

    @Test
    void cacheBuilder_backgroundEvictionInterval_null_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new CacheBuilder<String, String>().backgroundEvictionInterval(null));
    }

    @Test
    void cacheBuilder_backgroundEvictionInterval_zero_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new CacheBuilder<String, String>().backgroundEvictionInterval(Duration.ofMillis(0)));
    }

    @Test
    void cacheBuilder_evictionWatermarks_invalid_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new CacheBuilder<String, String>().evictionMemoryWatermarks(0.0, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> new CacheBuilder<String, String>().evictionMemoryWatermarks(0.9, 0.9));
    }

    @Test
    void cacheBuilder_offHeapMemoryTooSmall_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new CacheBuilder<String, String>()
                        .maxEntries(100000)
                        .offHeapMemory(1024)
                        .build());
    }

    @Test
    void cacheBuilder_prefetchEnabled() {
        try (OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .prefetch(true)
                .build()) {
            cache.put("pfk", "pfv");
            assertEquals("pfv", cache.get("pfk"));
        }
    }

    @Test
    void cacheBuilder_customHashTableStripes() {
        try (OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .hashTableStripes(16)
                .build()) {
            cache.put("htsk", "htsv");
            assertEquals("htsv", cache.get("htsk"));
        }
    }

    @Test
    void cacheBuilder_customEntryPoolPartitions() {
        try (OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .entryPoolPartitions(16)
                .build()) {
            cache.put("epk", "epv");
            assertEquals("epv", cache.get("epk"));
        }
    }

    @Test
    void cacheBuilder_indexMemoryBudgetPercent_build() {
        try (OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(MB16)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .indexMemoryBudgetPercent(0.3)
                .build()) {
            cache.put("ibk", "ibv");
            assertEquals("ibv", cache.get("ibk"));
        }
    }

    @Test
    void cacheBuilder_latin1KeyEncoding() {
        try (OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .stringKeyEncoding(StringEncoding.LATIN1)
                .build()) {
            cache.put("lk1", "lv1");
            assertEquals("lv1", cache.get("lk1"));
            // Update existing with Latin-1
            cache.put("lk1", "lv2");
            assertEquals("lv2", cache.get("lk1"));
            // Remove with Latin-1
            assertTrue(cache.remove("lk1"));
            assertNull(cache.get("lk1"));
        }
    }

    // ── close with background eviction executor ─────────────────────────────

    @Test
    void close_withBackgroundEviction_shutsDownCleanly() {
        OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(MB8)
                .backgroundEviction(true)
                .backgroundEvictionInterval(Duration.ofMillis(5))
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .build();
        cache.put("bgck", "bgcv");
        // close should shut down both eviction and maintenance executors
        cache.close();
        // After close, get returns null
        assertNull(cache.get("bgck"));
    }

    // ── Eviction listener fires on TTL expiration ───────────────────────────

    @Test
    void evictionListener_firesOnTTLExpiration() throws Exception {
        AtomicReference<EvictionCause> causeRef = new AtomicReference<>();

        try (OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .evictionListener((key, valueSup, cause) -> causeRef.set(cause))
                .build()) {
            cache.put("ttl-ek", "ttl-ev", Duration.ofMillis(50));
            Thread.sleep(100);
            // Get triggers lazy removal on expired entry
            assertNull(cache.get("ttl-ek"));
            assertEquals(EvictionCause.EXPIRED, causeRef.get());
        }
    }

    // ── clear with ghost caches ─────────────────────────────────────────────

    @Test
    void clear_withOffHeapGhost() {
        try (OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.OFF_HEAP)
                .ghostCacheSize(128)
                .build()) {
            for (int i = 0; i < 20; i++) {
                cache.put("clk" + i, "clv" + i);
            }
            cache.clear();
            assertEquals(0, cache.size());
        }
    }

    @Test
    void clear_withHeapGhost() {
        try (OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.HEAP)
                .ghostCacheSize(128)
                .build()) {
            for (int i = 0; i < 20; i++) {
                cache.put("clk" + i, "clv" + i);
            }
            cache.clear();
            assertEquals(0, cache.size());
        }
    }

    // ── getZeroCopy / getView closed cache returns null ─────────────────────

    @Test
    void getZeroCopy_closedCache_returnsNull() {
        OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .build();
        cache.put("czk", "czv");
        cache.close();
        assertNull(cache.getZeroCopy("czk", seg -> "found"));
    }

    @Test
    void getView_closedCache_returnsNull() {
        OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .build();
        cache.put("cvk", "cvv");
        cache.close();
        assertNull(cache.getView("cvk"));
    }

    @Test
    void getZeroCopy_nullKey_throws() {
        try (OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .build()) {
            assertThrows(NullPointerException.class, () -> cache.getZeroCopy(null, seg -> "x"));
        }
    }

    @Test
    void getView_nullKey_throws() {
        try (OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .build()) {
            assertThrows(NullPointerException.class, () -> cache.getView(null));
        }
    }

    @Test
    void getZeroCopy_nullProcessor_throws() {
        try (OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .build()) {
            cache.put("npk", "npv");
            assertThrows(NullPointerException.class, () -> cache.getZeroCopy("npk", null));
        }
    }
}
