package com.codeabbot.rmcache;

import com.codeabbot.rmcache.serializer.BuiltInSerializers;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class BackgroundEvictionTest {

    @Test
    public void backgroundEvictionReducesSizeToMaxEntries() throws Exception {
        int maxEntries = 100;
        OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(maxEntries)
                .offHeapMemory(32 * 1024 * 1024)
                .ghostCacheSize(0)
                .backgroundEviction(true)
                .backgroundEvictionInterval(Duration.ofMillis(10))
                .keySerializer(BuiltInSerializers.STRING_KEY)
                .valueSerializer(BuiltInSerializers.string())
                .build();

        try {
            for (int i = 0; i < maxEntries * 2; i++) {
                cache.put("k" + i, "v" + i);
            }

            // Wait up to 2 seconds for background eviction to converge
            long deadline = System.nanoTime() + 2_000_000_000L;
            while (cache.size() > maxEntries && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }

            assertTrue(cache.size() <= maxEntries);
        } finally {
            cache.close();
        }
    }
}
