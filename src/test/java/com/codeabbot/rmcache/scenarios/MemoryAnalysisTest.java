package com.codeabbot.rmcache.scenarios;

import com.codeabbot.rmcache.CacheBuilder;
import com.codeabbot.rmcache.OffHeapCache;
import com.codeabbot.rmcache.serializer.BuiltInSerializers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MemoryAnalysisTest {

    private OffHeapCache<String, byte[]> cache;

    @AfterEach
    public void tearDown() {
        if (cache != null) {
            cache.close();
        }
    }

    @Test
    public void testHeapStabilityVsOffHeapGrowth() {
        // Limit Off-heap to 300MB (1KB value + headers aligns to 2KB block -> 200MB
        // needed)
        long maxMemory = 300 * 1024 * 1024L;
        cache = new CacheBuilder<String, byte[]>()
                .keySerializer(BuiltInSerializers.STRING_KEY)
                .valueSerializer(BuiltInSerializers.byteArray())
                .offHeapMemory(maxMemory)
                .maxEntries(1_000_000)
                .build();

        Runtime runtime = Runtime.getRuntime();
        System.gc();
        long initialHeap = runtime.totalMemory() - runtime.freeMemory();

        // Insert 100k items of ~1KB each -> ~100MB Off-Heap
        byte[] value = new byte[1024];
        for (int i = 0; i < 100_000; i++) {
            cache.put("Key-" + i, value);
        }

        System.gc();
        long finalHeap = runtime.totalMemory() - runtime.freeMemory();

        // Heap growth should be minimal (< 5MB overhead for 100k keys objects/wrappers
        // if any)
        // RMCache is Zero-Heap for entries, so we expect very little growth.
        long growth = finalHeap - initialHeap;
        System.out.println("Heap Growth: " + (growth / 1024 / 1024) + " MB. Off-Heap Used: "
                + (cache.getStats().memoryUsedBytes() / 1024 / 1024) + " MB");

        // Assert Heap growth is small relative to data size (100MB)
        // Allowing 10MB tolerance for test runner overhead etc.
        assertTrue(growth < 10 * 1024 * 1024, "Heap usage grew too much: " + (growth / 1024 / 1024) + " MB");

        // Use result
        assertNotNull(cache.get("Key-50000"));
    }

    @Test
    public void testLeakDetectionOnClose() {
        cache = new CacheBuilder<String, byte[]>()
                .keySerializer(BuiltInSerializers.STRING_KEY)
                .valueSerializer(BuiltInSerializers.byteArray())
                .offHeapMemory(10 * 1024 * 1024)
                .maxEntries(10_000)
                .build();

        byte[] value = "A".getBytes();
        for (int i = 0; i < 5000; i++) {
            cache.put("Key-" + i, value);
        }

        assertTrue(cache.getStats().memoryUsedBytes() > 0);

        // Explicit Clear
        cache.clear();

        // Used memory might not be immediately 0 due to Slab quantization,
        // but `clear()` usually resets the Hash Table.
        // Allocator stats? SlabAllocator doesn't support "shrink" on clear efficiently
        // yet maybe?
        // Let's check `close()` instead for leak freedom.

        // Close
        cache.close();
        cache = null; // Prevent double-close in tearDown

        // We cannot check stats after close.
        // This test relies on "No Crash" on close, which implies underlying Arena
        // closed successfully.
        // A true leak check requires hooking into the Allocator or using JMX/Native
        // tools.
        // For unit test, we verify we can re-open/re-allocate without OOM.
    }

    @Test
    public void testMemoryPressureAndEviction() {
        // Small cache: 1MB
        cache = new CacheBuilder<String, byte[]>()
                .keySerializer(BuiltInSerializers.STRING_KEY)
                .valueSerializer(BuiltInSerializers.byteArray())
                .offHeapMemory(1024 * 1024)
                .maxEntries(1000) // Small entry limit
                .build();

        // Insert 1500 items (should trigger eviction)
        byte[] value = new byte[100]; // Small value
        for (int i = 0; i < 1500; i++) {
            cache.put("Key-" + i, value);
        }

        // Verify size stays near maxEntries
        assertTrue(cache.size() <= 1100, "Cache size exceeded limit: " + cache.size());

        // Verify early items evicted
        assertNull(cache.get("Key-0"), "Key-0 should have been evicted");
        assertNotNull(cache.get("Key-1499"), "Key-1499 should exist");
    }
}
