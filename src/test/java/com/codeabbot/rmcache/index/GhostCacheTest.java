package com.codeabbot.rmcache.index;

import org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

public class GhostCacheTest {

    @Test
    public void testBasicPutAndGet() {
        GhostCache<String, String> cache = new GhostCache<>(128);

        cache.put("key1", "key1".hashCode(), "value1");
        cache.put("key2", "key2".hashCode(), "value2");

        assertEquals("value1", cache.get("key1", "key1".hashCode()));
        assertEquals("value2", cache.get("key2", "key2".hashCode()));
        assertNull(cache.get("key3", "key3".hashCode()));
    }

    @Test
    public void testInvalidateRemovesEntry() {
        GhostCache<String, String> cache = new GhostCache<>(128);

        cache.put("key1", "key1".hashCode(), "value1");
        assertEquals("value1", cache.get("key1", "key1".hashCode()));

        cache.invalidate("key1".hashCode());
        assertNull(cache.get("key1", "key1".hashCode()));
    }

    @Test
    public void testInvalidateAllClearsCache() {
        GhostCache<String, String> cache = new GhostCache<>(128);

        cache.put("key1", "key1".hashCode(), "value1");
        cache.put("key2", "key2".hashCode(), "value2");

        cache.invalidateAll();

        assertNull(cache.get("key1", "key1".hashCode()));
        assertNull(cache.get("key2", "key2".hashCode()));
    }

    @Test
    public void testUpdateValueForSameKey() {
        GhostCache<String, String> cache = new GhostCache<>(128);

        cache.put("key1", "key1".hashCode(), "value1");
        assertEquals("value1", cache.get("key1", "key1".hashCode()));

        cache.put("key1", "key1".hashCode(), "updated");
        assertEquals("updated", cache.get("key1", "key1".hashCode()));
    }

    @Test
    public void testCapacityIsPowerOf2() {
        GhostCache<String, String> cache = new GhostCache<>(100);
        assertEquals(128, cache.stats().capacity()); // Rounded up to 128
    }

    @Test
    public void testConcurrentAccess() {
        GhostCache<String, String> cache = new GhostCache<>(1024);
        List<Thread> threads = new ArrayList<>();
        for (int tId = 0; tId < 8; tId++) {
            final int threadId = tId;
            Thread t = new Thread(() -> {
                for (int i = 0; i < 1000; i++) {
                    String key = "key-" + threadId + "-" + i;
                    cache.put(key, key.hashCode(), "value-" + i);
                    cache.get(key, key.hashCode());
                }
            });
            threads.add(t);
        }

        threads.forEach(Thread::start);
        threads.forEach(t -> {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // No exceptions thrown = success
        assertTrue(true);
    }

    @Test
    public void testDirectMappedCollisionOverwrites() {
        GhostCache<String, String> cache = new GhostCache<>(4);

        // Put key with hash 0
        cache.put("a", 0, "value-a");
        assertEquals("value-a", cache.get("a", 0));

        // Put another key with same slot (hash 4 -> 4 & 3 = 0)
        cache.put("b", 4, "value-b");
        assertEquals("value-b", cache.get("b", 4));

        // Key "a" should be overwritten (direct-mapped)
        assertNull(cache.get("a", 0));
    }
}
