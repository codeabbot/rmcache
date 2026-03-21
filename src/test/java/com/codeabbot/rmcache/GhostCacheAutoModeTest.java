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
