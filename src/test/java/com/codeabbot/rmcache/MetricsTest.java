package com.codeabbot.rmcache;

import com.codeabbot.rmcache.serializer.BuiltInSerializers;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

public class MetricsTest {

    @Test
    public void testMetricsRegistrationAndFlushing() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .averageValueSize(64)
                .offHeapMemory(1024 * 1024)
                .withMeterRegistry(registry)
                .withCacheName("test-cache")
                .keySerializer(BuiltInSerializers.STRING_KEY)
                .valueSerializer(BuiltInSerializers.string())
                .build();

        try {
            // Check init
            assertEquals(0.0, registry.get("rmcache.hits").functionCounter().count());

            // Phase 1: Hits
            for (int i = 0; i < 150; i++) {
                cache.put("key" + i, "value" + i);
            }

            // Verify size
            double sizeInit = registry.get("rmcache.size").gauge().value();
            assertEquals(150.0, sizeInit, 1.0);

            for (int i = 0; i < 150; i++) {
                String res = cache.get("key" + i);
                assertNotNull(res, "Get failed for key" + i);
            }
            // 150 hits. Should flush (threshold 128).

            double hits = registry.get("rmcache.hits").functionCounter().count();
            assertTrue(hits > 0, "Hits should be flushed");
            // Batched flush means we might trail by up to 127 ops.
            // 150 ops total. 128 flushed. 22 pending.
            assertEquals(150.0, hits, 128.0);

            // Phase 2: Misses
            for (int i = 0; i < 200; i++) {
                cache.get("absent" + i);
            }
            double misses = registry.get("rmcache.misses").functionCounter().count();
            assertTrue(misses > 0, "Misses should be flushed");

        } finally {
            cache.close();
            registry.close();
        }
    }

    @Test
    public void testMultiThreadedFlushing() throws InterruptedException {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(10000)
                .offHeapMemory(8 * 1024 * 1024)
                .withMeterRegistry(registry)
                .keySerializer(BuiltInSerializers.STRING_KEY)
                .valueSerializer(BuiltInSerializers.string())
                .build();

        int numThreads = 8;
        int opsPerThread = 10_000;
        CountDownLatch latch = new CountDownLatch(numThreads);
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);

        try {
            // Pre-populate
            for (int i = 0; i < 1000; i++) {
                cache.put("key" + i, "val" + i);
            }

            for (int t = 0; t < numThreads; t++) {
                executor.submit(() -> {
                    try {
                        for (int i = 0; i < opsPerThread; i++) {
                            // Mix of hits and misses
                            if (i % 2 == 0) {
                                cache.get("key" + (i % 1000)); // Hit
                            } else {
                                cache.get("absent" + i); // Miss
                            }
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertTrue(latch.await(10, TimeUnit.SECONDS));

            // Total ops = 8 * 10,000 = 80,000
            // Hits = 40,000. Misses = 40,000.

            double totalHits = registry.get("rmcache.hits").functionCounter().count();
            double totalMisses = registry.get("rmcache.misses").functionCounter().count();

            // Allow small delta for unflushed local stats (max 127 per thread)
            // 8 threads * 127 = ~1016 max error.
            assertEquals(40_000.0, totalHits, 1024.0);
            assertEquals(40_000.0, totalMisses, 1024.0);

        } finally {
            executor.shutdown();
            cache.close();
            registry.close();
        }
    }

    @Test
    public void testEvictionMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(100)
                .offHeapMemory(1024 * 1024)
                .withMeterRegistry(registry)
                .keySerializer(BuiltInSerializers.STRING_KEY)
                .valueSerializer(BuiltInSerializers.string())
                .backgroundEviction(false) // Synchronous eviction for test
                .build();

        try {
            // Fill 100
            for (int i = 0; i < 100; i++) {
                cache.put("key" + i, "val" + i);
            }

            // Overfill by 200
            for (int i = 100; i < 300; i++) {
                cache.put("key" + i, "val" + i);
            }

            // Check size
            double size = registry.get("rmcache.size").gauge().value();
            assertTrue(size <= 110, "Size should be near maxEntries (100) + wiggle room");

            // Check evictions
            double evictions = registry.get("rmcache.evictions").functionCounter().count();
            assertTrue(evictions >= 190, "Should have evicted ~200 items");

        } finally {
            cache.close();
            registry.close();
        }
    }

    @Test
    public void testGhostCacheHitsAreCounted() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .ghostCacheSize(100)
                .offHeapMemory(1024 * 1024)
                .withMeterRegistry(registry)
                .keySerializer(BuiltInSerializers.STRING_KEY)
                .valueSerializer(BuiltInSerializers.string())
                .build();

        try {
            cache.put("A", "1");

            // 1. Cold Get (L2 Hit)
            cache.get("A");

            // 2. Hot Get (should be Ghost L1 Hit)
            // Do enough to trigger flush
            for (int i = 0; i < 200; i++) {
                cache.get("A");
            }

            double hits = registry.get("rmcache.hits").functionCounter().count();
            // 200 ops -> 128 flushed. 72 pending.
            assertTrue(hits >= 128, "Ghost hits should count towards global metrics (min 128 flushed)");

        } finally {
            cache.close();
            registry.close();
        }
    }

    @Test
    public void testExpirationCountsAsMiss() throws InterruptedException {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(1024 * 1024)
                .withMeterRegistry(registry)
                .keySerializer(BuiltInSerializers.STRING_KEY)
                .valueSerializer(BuiltInSerializers.string())
                .build();

        try {
            cache.put("expired", "val", Duration.ofMillis(10));
            Thread.sleep(200);

            // Should be miss
            String res = cache.get("expired");
            assertNull(res);

            // Force flush by doing enough ops (threshold 128)
            int loopCount = 300;
            for (int i = 0; i < loopCount; i++)
                cache.get("absent");

            double misses = registry.get("rmcache.misses").functionCounter().count();
            // 1 expired miss + 300 absent misses = 301
            // Tolerance due to batching (up to 127 pending)
            assertTrue(misses >= 256.0, "Should have flushed at least 256 misses");
            assertEquals(301.0, misses, 128.0);

        } finally {
            cache.close();
            registry.close();
        }
    }
}
