package com.codeabbot.rmcache;

import com.codeabbot.rmcache.serializer.BuiltInSerializers;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class OffHeapGhostCacheModeTest {

    @Test
    public void offHeapGhostCacheReturnsValuesAndInvalidatesOnRemove() {
        OffHeapCache<String, byte[]> cache = new CacheBuilder<String, byte[]>()
                .maxEntries(1000)
                .offHeapMemory(32 * 1024 * 1024)
                .ghostCacheSize(1024)
                .ghostCacheMode(GhostCacheMode.OFF_HEAP)
                .keySerializer(BuiltInSerializers.STRING_KEY)
                .valueSerializer(BuiltInSerializers.byteArray())
                .build();

        try {
            byte[] value = new byte[] { 1, 2, 3, 4 };
            cache.put("k1", value);
            assertArrayEquals(value, cache.get("k1"));

            // Warm the L1 and verify remove clears it
            assertArrayEquals(value, cache.get("k1"));
            assertTrue(cache.remove("k1"));
            assertNull(cache.get("k1"));
        } finally {
            cache.close();
        }
    }
}
