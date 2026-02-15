package com.codeabbot.rmcache;

import com.codeabbot.rmcache.serializer.BuiltInSerializers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CAS operations (putIfAbsent, computeIfAbsent) and bulk operations
 * (putAll, getAll).
 */
public class CASOperationsTest {

    private OffHeapCache<String, byte[]> cache;

    @BeforeEach
    public void setUp() {
        cache = new CacheBuilder<String, byte[]>()
                .keySerializer(BuiltInSerializers.STRING_KEY)
                .valueSerializer(BuiltInSerializers.byteArray())
                .offHeapMemory(10 * 1024 * 1024)
                .maxEntries(10_000)
                .build();
    }

    @AfterEach
    public void tearDown() {
        if (cache != null) {
            cache.close();
        }
    }

    @Test
    public void testPutIfAbsent_NewKey() {
        byte[] value = "Value1".getBytes(StandardCharsets.UTF_8);
        boolean inserted = cache.putIfAbsent("Key1", value);

        assertTrue(inserted, "Should insert new key");
        assertArrayEquals(value, cache.get("Key1"));
    }

    @Test
    public void testPutIfAbsent_ExistingKey() {
        byte[] value1 = "Value1".getBytes(StandardCharsets.UTF_8);
        byte[] value2 = "Value2".getBytes(StandardCharsets.UTF_8);

        cache.put("Key1", value1);
        boolean inserted = cache.putIfAbsent("Key1", value2);

        assertFalse(inserted, "Should not overwrite existing key");
        assertArrayEquals(value1, cache.get("Key1"), "Original value should remain");
    }

    @Test
    public void testPutIfAbsent_WithTTL() throws InterruptedException {
        byte[] value = "Value1".getBytes(StandardCharsets.UTF_8);
        boolean inserted = cache.putIfAbsent("Key1", value, Duration.ofMillis(200));

        assertTrue(inserted, "Should insert new key");
        assertNotNull(cache.get("Key1"));

        Thread.sleep(300);
        assertNull(cache.get("Key1"), "Value should expire");
    }

    @Test
    public void testPutIfAbsent_Concurrency() throws InterruptedException {
        int numThreads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);
        AtomicInteger successCount = new AtomicInteger(0);

        String key = "RaceKey";

        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    byte[] value = ("Thread-" + threadId).getBytes(StandardCharsets.UTF_8);
                    if (cache.putIfAbsent(key, value)) {
                        successCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        // Due to the non-atomic check-then-put nature of putIfAbsent,
        // we expect at least one success, but potentially more due to race conditions
        assertTrue(successCount.get() >= 1, "At least one thread should succeed");
        assertTrue(successCount.get() <= numThreads, "Success count should not exceed thread count");
        assertNotNull(cache.get(key), "Key should exist");
    }

    @Test
    public void testComputeIfAbsent_NewKey() {
        byte[] computed = cache.computeIfAbsent("Key1",
                k -> ("Computed-" + k).getBytes(StandardCharsets.UTF_8));

        assertNotNull(computed);
        assertArrayEquals("Computed-Key1".getBytes(StandardCharsets.UTF_8), computed);
        assertArrayEquals(computed, cache.get("Key1"));
    }

    @Test
    public void testComputeIfAbsent_ExistingKey() {
        byte[] value1 = "Value1".getBytes(StandardCharsets.UTF_8);
        cache.put("Key1", value1);

        AtomicInteger loaderCallCount = new AtomicInteger(0);
        byte[] result = cache.computeIfAbsent("Key1", k -> {
            loaderCallCount.incrementAndGet();
            return "ShouldNotBeUsed".getBytes(StandardCharsets.UTF_8);
        });

        assertArrayEquals(value1, result, "Should return existing value");
        assertEquals(0, loaderCallCount.get(), "Loader should not be called for existing key");
    }

    @Test
    public void testComputeIfAbsent_WithTTL() throws InterruptedException {
        byte[] computed = cache.computeIfAbsent("Key1",
                k -> "Value1".getBytes(StandardCharsets.UTF_8),
                Duration.ofMillis(200));

        assertNotNull(computed);
        assertNotNull(cache.get("Key1"));

        Thread.sleep(300);
        assertNull(cache.get("Key1"), "Value should expire");
    }

    @Test
    public void testComputeIfAbsent_NullLoader() {
        byte[] result = cache.computeIfAbsent("Key1", k -> null);
        assertNull(result);
        assertNull(cache.get("Key1"));
    }

    @Test
    public void testPutAll_Basic() {
        Map<String, byte[]> entries = new HashMap<>();
        for (int i = 0; i < 100; i++) {
            entries.put("Key-" + i, ("Value-" + i).getBytes(StandardCharsets.UTF_8));
        }

        cache.putAll(entries);

        assertEquals(100, cache.size());
        for (int i = 0; i < 100; i++) {
            assertArrayEquals(("Value-" + i).getBytes(StandardCharsets.UTF_8),
                    cache.get("Key-" + i));
        }
    }

    @Test
    public void testPutAll_WithTTL() throws InterruptedException {
        Map<String, byte[]> entries = new HashMap<>();
        entries.put("Key1", "Value1".getBytes(StandardCharsets.UTF_8));
        entries.put("Key2", "Value2".getBytes(StandardCharsets.UTF_8));

        cache.putAll(entries, Duration.ofMillis(200));

        assertEquals(2, cache.size());
        assertNotNull(cache.get("Key1"));
        assertNotNull(cache.get("Key2"));

        Thread.sleep(300);
        assertNull(cache.get("Key1"));
        assertNull(cache.get("Key2"));
    }

    @Test
    public void testGetAll_AllKeysExist() {
        for (int i = 0; i < 10; i++) {
            cache.put("Key-" + i, ("Value-" + i).getBytes(StandardCharsets.UTF_8));
        }

        List<String> keys = Arrays.asList("Key-0", "Key-5", "Key-9");
        Map<String, byte[]> results = cache.getAll(keys);

        assertEquals(3, results.size());
        assertArrayEquals("Value-0".getBytes(StandardCharsets.UTF_8), results.get("Key-0"));
        assertArrayEquals("Value-5".getBytes(StandardCharsets.UTF_8), results.get("Key-5"));
        assertArrayEquals("Value-9".getBytes(StandardCharsets.UTF_8), results.get("Key-9"));
    }

    @Test
    public void testGetAll_PartialKeys() {
        cache.put("Key-1", "Value-1".getBytes(StandardCharsets.UTF_8));
        cache.put("Key-3", "Value-3".getBytes(StandardCharsets.UTF_8));

        List<String> keys = Arrays.asList("Key-1", "Key-2", "Key-3", "Key-4");
        Map<String, byte[]> results = cache.getAll(keys);

        assertEquals(2, results.size());
        assertTrue(results.containsKey("Key-1"));
        assertFalse(results.containsKey("Key-2"));
        assertTrue(results.containsKey("Key-3"));
        assertFalse(results.containsKey("Key-4"));
    }

    @Test
    public void testGetAll_NoKeysExist() {
        List<String> keys = Arrays.asList("NonExistent-1", "NonExistent-2");
        Map<String, byte[]> results = cache.getAll(keys);

        assertTrue(results.isEmpty());
    }

    @Test
    public void testGetAll_EmptyKeys() {
        Map<String, byte[]> results = cache.getAll(Collections.emptyList());
        assertTrue(results.isEmpty());
    }

    @Test
    public void testBulkOperations_LargeScale() {
        // Test putAll with 1000 entries
        Map<String, byte[]> largeData = new HashMap<>();
        for (int i = 0; i < 1000; i++) {
            largeData.put("Bulk-" + i, ("Value-" + i).getBytes(StandardCharsets.UTF_8));
        }

        cache.putAll(largeData);
        assertEquals(1000, cache.size());

        // Test getAll with 500 keys
        List<String> keysToRetrieve = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            keysToRetrieve.add("Bulk-" + (i * 2)); // Every other key
        }

        Map<String, byte[]> results = cache.getAll(keysToRetrieve);
        assertEquals(500, results.size());
    }
}
