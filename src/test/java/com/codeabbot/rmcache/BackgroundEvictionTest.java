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

            // Allow background eviction to run
            for (int i = 0; i < 20; i++) {
                if (cache.size() <= maxEntries) {
                    break;
                }
                Thread.sleep(10);
            }

            assertTrue(cache.size() <= maxEntries);
        } finally {
            cache.close();
        }
    }
}
