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

import com.codeabbot.rmcache.eviction.LRUPolicy;
import com.codeabbot.rmcache.eviction.Priority;
import com.codeabbot.rmcache.serializer.BuiltInSerializers;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

public class ComprehensiveOffHeapCacheTest {

    private OffHeapCache<String, byte[]> cache;

    @AfterEach
    public void tearDown() {
        if (cache != null) {
            cache.close();
        }
    }

    private OffHeapCache<String, byte[]> createCache(int maxEntries, long memorySize) {
        return new CacheBuilder<String, byte[]>()
                .maxEntries(maxEntries)
                .offHeapMemory(memorySize)
                .ghostCacheSize(0) // Disable GhostCache for deterministic LRU testing
                .keySerializer(BuiltInSerializers.STRING_KEY)
                .valueSerializer(BuiltInSerializers.byteArray())
                .eviction(new LRUPolicy(maxEntries))
                .backgroundEviction(false) // Synchronous eviction for test
                .build();
    }

    private OffHeapCache<String, byte[]> createCache() {
        return createCache(10_000, 64 * 1024 * 1024);
    }

    private OffHeapCache<String, byte[]> createCache(int maxEntries) {
        return createCache(maxEntries, 64 * 1024 * 1024);
    }

    // --- TTL Tests ---

    @Test
    public void testTtlExpiration() throws InterruptedException {
        cache = createCache();
        String key = "ttl-key";
        byte[] value = "value".getBytes();

        // Put with 200ms TTL
        cache.put(key, value, Duration.ofMillis(200));

        // Should exist immediately
        assertNotNull(cache.get(key));

        // Wait 300ms
        Thread.sleep(300);

        // Should be expired (return null)
        assertNull(cache.get(key));
    }

    @Test
    public void testTtlExpirationWithMixedEntries() throws InterruptedException {
        cache = createCache();

        // Permanent entry
        cache.put("perm", "perm".getBytes());

        // Expiring entry
        cache.put("temp", "temp".getBytes(), Duration.ofMillis(100));

        Thread.sleep(300);

        assertNotNull(cache.get("perm"));
        assertNull(cache.get("temp"));
    }

    // --- LRU Eviction Tests ---

    @Test
    public void testLruEvictionOrder() throws InterruptedException {
        // Cache size 3
        cache = createCache(3);

        cache.put("A", "valA".getBytes());
        cache.put("B", "valB".getBytes());
        cache.put("C", "valC".getBytes());

        // Access A to make it most recently used. Order: B, C, A
        cache.get("A");
        Thread.sleep(50); // Allow async maintenance to promote A

        // Add D, should evict LRU -> B
        cache.put("D", "valD".getBytes());

        assertNull(cache.get("B"), "B should have been evicted");
        assertNotNull(cache.get("A"), "A should exist");
        assertNotNull(cache.get("C"), "C should exist");
        assertNotNull(cache.get("D"), "D should exist");

        // Current Order: C, A, D. Add E -> Evict C
        cache.put("E", "valE".getBytes());
        assertNull(cache.get("C"), "C should have been evicted");
        assertNotNull(cache.get("A"));
        assertNotNull(cache.get("D"));
        assertNotNull(cache.get("E"));
    }

    // --- Priority Tests ---

    @Test
    public void testPriorityProtection() {
        // Cache size 3
        cache = createCache(3);

        // A is LOW, B is NORMAL, C is CRITICAL
        cache.put("A", "valA".getBytes(), Priority.LOW);
        cache.put("B", "valB".getBytes(), Priority.NORMAL);
        cache.put("C", "valC".getBytes(), Priority.CRITICAL);

        // Add D (NORMAL). Should prefer evicting LOW (A)

        cache.put("D", "valD".getBytes());

        // If policy respects priority, A (LOW) should be evicted first.
        assertNull(cache.get("A"), "Lowest priority A should be evicted");
        assertNotNull(cache.get("B"));
        assertNotNull(cache.get("C"));
        assertNotNull(cache.get("D"));
    }

    // --- Edge Case Tests ---

    @Test
    public void testZeroLengthValue() {
        cache = createCache();
        cache.put("empty", new byte[0]);
        byte[] res = cache.get("empty");
        assertNotNull(res);
        assertEquals(0, res.length);
    }

    @Test
    public void testLargeValueAllocation() {
        // Larger heap for large alloc (256 MB)
        long memorySize = 256L * 1024 * 1024;
        cache = createCache(10000, memorySize);
        int size = 64 * 1024 - 100; // Close to slab size limit if 64KB slabs
        byte[] largeVal = new byte[size];
        new Random().nextBytes(largeVal);

        cache.put("large", largeVal);
        byte[] res = cache.get("large");

        assertNotNull(res);
        assertArrayEquals(largeVal, res);
    }

    @Test
    public void testValueResizing() {
        cache = createCache();
        String key = "resize";

        // Small initial
        byte[] small = new byte[10];
        for (int i = 0; i < 10; i++)
            small[i] = 1;
        cache.put(key, small);

        // Update larger
        byte[] larger = new byte[1000];
        for (int i = 0; i < 1000; i++)
            larger[i] = 2;
        cache.put(key, larger);

        byte[] res = cache.get(key);
        assertNotNull(res);
        assertArrayEquals(larger, res);
    }

    @Test
    public void testCollisionHandling() {
        // Hard to force collision with String, but we can put many keys and verify all
        // exist
        // to ensure hash table linear probing works.
        cache = createCache(1000, 10 * 1024 * 1024);
        int count = 500;
        for (int i = 0; i < count; i++) {
            cache.put("col-key-" + i, ("val-" + i).getBytes());
        }

        for (int i = 0; i < count; i++) {
            byte[] res = cache.get("col-key-" + i);
            assertNotNull(res, "Key col-key-" + i + " missing");
            assertArrayEquals(("val-" + i).getBytes(), res);
        }
        assertEquals(count, cache.size());
    }

    // --- Concurrency Tests ---

    @Test
    public void testConcurrentPutsAndGets() throws InterruptedException {
        long memorySize = 256L * 1024 * 1024;
        cache = createCache(100_000, memorySize);
        int threads = 8;
        int opsPerThread = 5000;
        ExecutorService service = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            final int tId = t;
            service.submit(() -> {
                try {
                    for (int i = 0; i < opsPerThread; i++) {
                        String k = "key-" + tId + "-" + i;
                        byte[] v = ("val-" + tId + "-" + i).getBytes();
                        cache.put(k, v);

                        // Immediate verify sometimes
                        if (i % 100 == 0) {
                            assertArrayEquals(v, cache.get(k));
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS));
        service.shutdown();

        // Verify random keys
        for (int t = 0; t < threads; t++) {
            String k = "key-" + t + "-4999";
            assertNotNull(cache.get(k));
        }

        assertEquals(threads * opsPerThread, cache.size());
    }
}
