package com.codeabbot.rmcache;

import com.codeabbot.rmcache.serializer.BuiltInSerializers;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

public class GhostCacheAutoModeTest {

    @Test
    public void autoModeUsesOffHeapWhenZeroHeapProfileEnabled() throws Exception {
        OffHeapCache<String, byte[]> cache = new CacheBuilder<String, byte[]>()
                .maxEntries(1_000)
                .offHeapMemory(32 * 1024 * 1024)
                .ghostCacheSize(256)
                .ghostCacheMode(GhostCacheMode.AUTO)
                .zeroHeapProfile()
                .keySerializer(BuiltInSerializers.STRING_KEY)
                .valueSerializer(BuiltInSerializers.byteArray())
                .build();

        try {
            OffHeapCacheImpl<?, ?> impl = (OffHeapCacheImpl<?, ?>) cache;
            Field offHeapField = OffHeapCacheImpl.class.getDeclaredField("offHeapGhostCache");
            Field heapField = OffHeapCacheImpl.class.getDeclaredField("ghostCache");
            offHeapField.setAccessible(true);
            heapField.setAccessible(true);
            assertNotNull(offHeapField.get(impl));
            assertNull(heapField.get(impl));
        } finally {
            cache.close();
        }
    }

    @Test
    public void autoModeUsesHeapWhenZeroHeapProfileDisabled() throws Exception {
        OffHeapCache<String, byte[]> cache = new CacheBuilder<String, byte[]>()
                .maxEntries(1_000)
                .offHeapMemory(32 * 1024 * 1024)
                .ghostCacheSize(256)
                .ghostCacheMode(GhostCacheMode.AUTO)
                .keySerializer(BuiltInSerializers.STRING_KEY)
                .valueSerializer(BuiltInSerializers.byteArray())
                .build();

        try {
            OffHeapCacheImpl<?, ?> impl = (OffHeapCacheImpl<?, ?>) cache;
            Field offHeapField = OffHeapCacheImpl.class.getDeclaredField("offHeapGhostCache");
            Field heapField = OffHeapCacheImpl.class.getDeclaredField("ghostCache");
            offHeapField.setAccessible(true);
            heapField.setAccessible(true);
            assertNull(offHeapField.get(impl));
            assertNotNull(heapField.get(impl));
        } finally {
            cache.close();
        }
    }
}
