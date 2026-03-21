/*
 * Copyright 2026 Rabindra Meher
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.codeabbot.rmcache;

import com.codeabbot.rmcache.serializer.BuiltInSerializers;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class LifecycleTest {

    @Test
    public void testHashTableResizing() {
        int maxEntries = 100_000;
        OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(maxEntries)
                .offHeapMemory(64 * 1024 * 1024)
                .keySerializer(BuiltInSerializers.STRING_KEY)
                .valueSerializer(BuiltInSerializers.string())
                .build();

        try {
            int count = 50_000;
            for (int i = 0; i < count; i++) {
                cache.put("key" + i, "value" + i);
            }

            assertEquals(count, cache.size(), "Cache size should match inserted count");

            // Verify all items are still accessible after potentially multiple resizes
            for (int i = 0; i < count; i++) {
                assertEquals("value" + i, cache.get("key" + i), "Data lost after resize for key" + i);
            }

        } finally {
            cache.close();
        }
    }

    @Test
    public void testClearOperation() {
        OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(1024 * 1024)
                .keySerializer(BuiltInSerializers.STRING_KEY)
                .valueSerializer(BuiltInSerializers.string())
                .build();

        try {
            for (int i = 0; i < 100; i++) {
                cache.put("key" + i, "val" + i);
            }
            assertEquals(100, cache.size());

            cache.clear();

            assertEquals(0, cache.size(), "Size should be 0 after clear");
            assertNull(cache.get("key0"), "Items should be gone");

            // Verify we can use it after clear
            cache.put("newKey", "newValue");
            assertEquals("newValue", cache.get("newKey"));
            assertEquals(1, cache.size());

        } finally {
            cache.close();
        }
    }

    @Test
    public void testCloseBehavior() {
        OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(1024 * 1024)
                .keySerializer(BuiltInSerializers.STRING_KEY)
                .valueSerializer(BuiltInSerializers.string())
                .build();

        cache.put("A", "B");
        cache.close();

        // Assuming put throws on closed
        assertThrows(IllegalStateException.class, () -> cache.put("C", "D"));

        // Get usually returns null on closed, or throws depending on impl.
        // OffHeapCacheImpl.get checks closed and returns null.
        assertNull(cache.get("A"), "Get should return null or fail after close");
    }
}
