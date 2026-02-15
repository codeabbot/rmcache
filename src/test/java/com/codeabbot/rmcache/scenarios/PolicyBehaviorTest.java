package com.codeabbot.rmcache.scenarios;

import com.codeabbot.rmcache.CacheBuilder;
import com.codeabbot.rmcache.OffHeapCache;
import com.codeabbot.rmcache.serializer.BuiltInSerializers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PolicyBehaviorTest {

    private OffHeapCache<String, byte[]> cache;

    @AfterEach
    public void tearDown() {
        if (cache != null) {
            cache.close();
        }
    }

    @Test
    public void testDefaultNoPolicy() {
        // Default behavior (LRU by default in CacheBuilder)
        cache = new CacheBuilder<String, byte[]>()
                .keySerializer(BuiltInSerializers.STRING_KEY)
                .valueSerializer(BuiltInSerializers.byteArray())
                .offHeapMemory(1024 * 1024)
                .maxEntries(10) // Small limit
                .ghostCacheSize(0)
                .backgroundEviction(false) // Synchronous eviction for test
                .build();

        for (int i = 0; i < 15; i++) {
            cache.put("K-" + i, "V".getBytes(StandardCharsets.UTF_8));
        }

        // Should contain 10
        assertEquals(10, cache.size());
        // Check eviction (LRU default)
        assertNull(cache.get("K-0"));
        assertNotNull(cache.get("K-14"));
    }

    @Test
    public void testTtlEdgeCases() throws InterruptedException {
        cache = new CacheBuilder<String, byte[]>()
                .keySerializer(BuiltInSerializers.STRING_KEY)
                .valueSerializer(BuiltInSerializers.byteArray())
                .offHeapMemory(1024 * 1024)
                .maxEntries(100)
                .ghostCacheSize(0)
                .build();

        cache.put("Short", "V".getBytes(StandardCharsets.UTF_8), Duration.ofMillis(100));
        assertNotNull(cache.get("Short"));

        Thread.sleep(300);
        assertNull(cache.get("Short"));

        // Zero TTL -> Handled as normal expiry (now + 0)
        // Effectively immediate expire
        cache.put("Zero", "V".getBytes(StandardCharsets.UTF_8), Duration.ZERO);

        // Wait for CoarseClock to update (100ms resolution) + buffer
        Thread.sleep(500);
        assertNull(cache.get("Zero"));
    }

    @Test
    public void testLruScanResistance() throws InterruptedException {
        // Window-Tiny-LFU like behavior
        cache = new CacheBuilder<String, byte[]>()
                .keySerializer(BuiltInSerializers.STRING_KEY)
                .valueSerializer(BuiltInSerializers.byteArray())
                .offHeapMemory(10 * 1024 * 1024)
                .maxEntries(100)
                .ghostCacheSize(0)
                .build();

        // Populate with "Hot" items
        for (int i = 0; i < 10; i++) {
            cache.put("Hot-" + i, "V".getBytes(StandardCharsets.UTF_8));
            // Access them repeatedly
            cache.get("Hot-" + i);
            cache.get("Hot-" + i);
        }

        // Allow async LRU maintenance to process promotions to Protected
        Thread.sleep(200);

        // Scan: Insert 200 "One-Hit Wonders"
        for (int j = 0; j < 200; j++) {
            cache.put("Scan-" + j, "V".getBytes(StandardCharsets.UTF_8));
        }

        // Verify Hot items retained vs Scan items
        // RMCache uses CompactLRU (Window/Probation/Protected), it offers SOME scan
        // resistance.
        int hotRetained = 0;
        for (int i = 0; i < 10; i++) {
            if (cache.get("Hot-" + i) != null)
                hotRetained++;
        }

        // Ideally > 0. CompactLRU splits space.
        assertTrue(hotRetained > 0, "Scan should not flush all hot items");
    }
}
