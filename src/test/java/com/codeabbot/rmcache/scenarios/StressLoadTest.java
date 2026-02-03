package com.codeabbot.rmcache.scenarios;

import com.codeabbot.rmcache.CacheBuilder;
import com.codeabbot.rmcache.OffHeapCache;
import com.codeabbot.rmcache.serializer.BuiltInSerializers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class StressLoadTest {

    private OffHeapCache<String, byte[]> cache;

    @AfterEach
    public void tearDown() {
        if (cache != null) {
            cache.close();
        }
    }

    // @Test
    public void testStress10k() {
        cache = new CacheBuilder<String, byte[]>()
                .keySerializer(BuiltInSerializers.STRING_KEY)
                .valueSerializer(BuiltInSerializers.byteArray())
                .offHeapMemory(32 * 1024 * 1024)
                .maxEntries(20_000)
                .build();

        int count = 10_000;
        byte[] value = "A small value".getBytes(StandardCharsets.UTF_8);

        long start = System.currentTimeMillis();
        for (int i = 0; i < count; i++) {
            cache.put("Key-" + i, value);
        }
        long duration = System.currentTimeMillis() - start;
        System.out.println("Stress 10k Write: " + duration + " ms");
        assertEquals(count, cache.size());

        long startRead = System.currentTimeMillis();
        for (int i = 0; i < count; i++) {
            assertNotNull(cache.get("Key-" + i));
        }
        long durationRead = System.currentTimeMillis() - startRead;
        System.out.println("Stress 10k Read: " + durationRead + " ms");
    }

    // @Test
    public void testStress100k() {
        cache = new CacheBuilder<String, byte[]>()
                .keySerializer(BuiltInSerializers.STRING_KEY)
                .valueSerializer(BuiltInSerializers.byteArray())
                .offHeapMemory(128 * 1024 * 1024)
                .maxEntries(200_000)
                .build();

        int count = 100_000;
        byte[] value = "Fixed Value".getBytes(StandardCharsets.UTF_8);

        long start = System.currentTimeMillis();
        for (int i = 0; i < count; i++) {
            cache.put("K-" + i, value);
        }
        long duration = System.currentTimeMillis() - start;
        System.out.println("Stress 100k Write: " + duration + " ms");
        assertEquals(count, cache.size());

        // Random Access check
        assertNotNull(cache.get("K-50000"));
    }

    // @Test
    public void testStress1Million() {
        // User Requirement: Use default config (no manual adjustments).
        // Memory Formula: maxEntries * (avgKey + avgVal + overhead) * 1.3

        int targetLoad = 1_000_000;
        // Set capacity to 1.3M to hold 1M items without eviction (default load factor
        // 0.8)

        int avgKey = 32; // Default
        int avgVal = 256; // Default
        int overhead = 40; // Internal per-entry overhead

        // Memory calculated for the TARGET load (1M) with the 1.3 safety buffer
        // requested
        long calculatedMemory = (long) (targetLoad * (avgKey + avgVal + overhead) * 1.3);
        // Result: 1M * 328 * 1.3 = ~426 MB.

        cache = new CacheBuilder<String, byte[]>()
                .keySerializer(BuiltInSerializers.STRING_KEY)
                .valueSerializer(BuiltInSerializers.byteArray())
                .offHeapMemory(calculatedMemory)
                .maxEntries(targetLoad)
                .build();

        int count = 1_000_000;

        System.out.println("Starting 1M put...");
        long start = System.currentTimeMillis();
        for (int i = 0; i < count; i++) {
            cache.put("K-" + i, ("Small Val-" + i).getBytes(StandardCharsets.UTF_8));
        }
        long duration = System.currentTimeMillis() - start;
        System.out.println("Stress 1M Write: " + duration + " ms");
        assertEquals(count, cache.size());

        System.out.println("Starting 1M verification...");
        long startRead = System.currentTimeMillis();
        for (int i = 0; i < count; i++) {
            byte[] val = cache.get("K-" + i);
            assertNotNull(val, "Failed to find key: K-" + i);
            assertEquals("Small Val-" + i, new String(val, StandardCharsets.UTF_8));
        }
        long durationRead = System.currentTimeMillis() - startRead;
        System.out.println("Stress 1M Read: " + durationRead + " ms");
    }

    // @Test
    public void testStressScalingDurability() {
        // Phase 1: 10M Entries, 1GB Off-Heap
        int count = 10_000_000;
        long memory = 1L * 1024 * 1024 * 1024; // 1 GB

        System.out.println("--- Starting Scaling Stress Test (Phase 1: 10M) ---");
        System.out.println("Target Entries: " + count);
        System.out.println("Off-Heap Memory: " + (memory / (1024 * 1024)) + " MB");

        cache = new CacheBuilder<String, byte[]>()
                .keySerializer(BuiltInSerializers.STRING_KEY)
                .valueSerializer(BuiltInSerializers.byteArray())
                .offHeapMemory(memory)
                .maxEntries(count)
                .averageKeySize(16)
                .averageValueSize(16)
                .build();

        System.out.println("Cache initialized successfully.");
        System.out.println("Starting initial load for " + count + " entries...");
        byte[] value = "Fixed Value 10M".getBytes(StandardCharsets.UTF_8);
        long start = System.currentTimeMillis();
        for (int i = 0; i < count; i++) {
            cache.put("K-" + i, value);
            if (i > 0 && i % 2_000_000 == 0) {
                System.out.println("Loaded " + i + " items (" + (i * 100L / count) + "%)...");
            }
        }
        long duration = System.currentTimeMillis() - start;
        System.out.println("Initial Load: " + duration + " ms (" + (duration / 1000.0) + " s)");
        assertEquals(count, cache.size());

        System.out.println("Starting 5-minute stability stress phase (Get/Put churn)...");
        long stressStartTime = System.currentTimeMillis();
        long stressDurationMs = TimeUnit.MINUTES.toMillis(5);
        long ops = 0;
        ThreadLocalRandom random = ThreadLocalRandom.current();

        while (System.currentTimeMillis() - stressStartTime < stressDurationMs) {
            int idx = random.nextInt(count);
            String key = "K-" + idx;

            // 50% Read, 50% Update
            if (random.nextBoolean()) {
                byte[] val = cache.get(key);
                if (val == null) {
                    throw new IllegalStateException("Key lost during stress: " + key);
                }
            } else {
                cache.put(key, value); // Update existing entry
            }
            ops++;

            if (ops > 0 && ops % 2_000_000 == 0) {
                long now = System.currentTimeMillis();
                double t = (ops * 1000.0) / (now - stressStartTime);
                System.out.println("Performed " + ops + " operations... Current Size: " + cache.size() + " Throughput: "
                        + String.format("%.2f", t) + " ops/s");
            }
        }

        long totalTimeMs = System.currentTimeMillis() - stressStartTime;
        double throughput = (ops * 1000.0) / totalTimeMs;
        System.out.println("--- 10M Stability Test Results ---");
        System.out.println("Stress Phase Finished after " + (totalTimeMs / 1000.0) + " s");
        System.out.println("Total Operations during stress: " + ops);
        System.out.println("Average Throughput: " + String.format("%.2f", throughput) + " ops/s");

        // Final sanity check
        assertEquals(count, cache.size());
        System.out.println("Final size verification passed.");
    }
}
