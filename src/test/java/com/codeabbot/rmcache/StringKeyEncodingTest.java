package com.codeabbot.rmcache;

import com.codeabbot.rmcache.serializer.BuiltInSerializers;
import com.codeabbot.rmcache.serializer.StringEncoding;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class StringKeyEncodingTest {

    @Test
    public void latin1EncodingSupportsLatin1Keys() {
        OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(100)
                .offHeapMemory(8 * 1024 * 1024)
                .ghostCacheSize(0)
                .stringKeyEncoding(StringEncoding.LATIN1)
                .keySerializer(BuiltInSerializers.STRING_KEY_LATIN1)
                .valueSerializer(BuiltInSerializers.string())
                .build();

        try {
            String key = "cafe\u00E9"; // 'é' is Latin1
            cache.put(key, "v1");
            assertEquals("v1", cache.get(key));
        } finally {
            cache.close();
        }
    }

    @Test
    public void utf8EncodingSupportsUnicodeKeys() {
        OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(100)
                .offHeapMemory(8 * 1024 * 1024)
                .ghostCacheSize(0)
                .stringKeyEncoding(StringEncoding.UTF8)
                .keySerializer(BuiltInSerializers.STRING_KEY_UTF8)
                .valueSerializer(BuiltInSerializers.string())
                .build();

        try {
            String key = "snowman-\u2603"; // unicode snowman
            cache.put(key, "v2");
            assertEquals("v2", cache.get(key));
        } finally {
            cache.close();
        }
    }
}
