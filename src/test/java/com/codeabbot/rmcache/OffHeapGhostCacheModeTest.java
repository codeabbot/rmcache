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
