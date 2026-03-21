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
package com.codeabbot.rmcache.scenarios;

import com.codeabbot.rmcache.CacheBuilder;
import com.codeabbot.rmcache.OffHeapCache;
import com.codeabbot.rmcache.eviction.EntryMetadata;
import com.codeabbot.rmcache.eviction.EvictionCause;
import com.codeabbot.rmcache.eviction.EvictionFilter;
import com.codeabbot.rmcache.eviction.EvictionListener;
import com.codeabbot.rmcache.serializer.BuiltInSerializers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

public class AdvancedFeaturesTest {

    private OffHeapCache<String, byte[]> cache;

    @AfterEach
    public void tearDown() {
        if (cache != null) {
            cache.close();
        }
    }

    @Test
    public void testEvictionListener() {
        AtomicReference<String> evictedKey = new AtomicReference<>();
        AtomicReference<EvictionCause> evictedCause = new AtomicReference<>();

        EvictionListener<String, byte[]> listener = (key, valueLazy, cause) -> {
            evictedKey.set(key);
            // Access value to trigger lazy loading
            valueLazy.get();
            evictedCause.set(cause);
        };

        cache = new CacheBuilder<String, byte[]>()
                .keySerializer(BuiltInSerializers.STRING_KEY)
                .valueSerializer(BuiltInSerializers.byteArray())
                .offHeapMemory(1024 * 1024)
                .maxEntries(1) // Force eviction immediately
                .evictionListener(listener)
                .backgroundEviction(false) // Synchronous eviction for test
                .build();

        cache.put("Keep", "V".getBytes(StandardCharsets.UTF_8));

        // This should evict "Keep"
        cache.put("New", "V".getBytes(StandardCharsets.UTF_8));

        // Check existence
        assertNotNull(cache.get("New"));
        assertNull(cache.get("Keep"));

        assertEquals("Keep", evictedKey.get());
        assertNotNull(evictedCause.get());
        System.out.println("Eviction Cause: " + evictedCause.get());
    }

    @Test
    public void testEvictionFilter() {
        // Filter that prevents "Pinned" key from being evicted
        EvictionFilter<String> filter = (key, metadata) -> !key.startsWith("Pinned");

        cache = new CacheBuilder<String, byte[]>()
                .keySerializer(BuiltInSerializers.STRING_KEY)
                .valueSerializer(BuiltInSerializers.byteArray())
                .offHeapMemory(1024 * 1024)
                .maxEntries(2)
                .evictionFilter(filter)
                .backgroundEviction(false) // Synchronous eviction for test
                .build();

        cache.put("Pinned-1", "V".getBytes(StandardCharsets.UTF_8));
        cache.put("A", "V".getBytes(StandardCharsets.UTF_8));

        // Cache full (2 items). "Pinned-1" is LRU.
        // Insert "B". Should trigger eviction.
        // Candidate: "Pinned-1" (LRU). Filter says NO.
        // Candidate: "A". (Evicted).
        cache.put("B", "V".getBytes(StandardCharsets.UTF_8));

        // "Pinned-1" should still exist
        assertNotNull(cache.get("Pinned-1"), "Pinned item should be retained");

        // "A" should be evicted?
        assertNull(cache.get("A"));

        // "B" should exist
        assertNotNull(cache.get("B"));

        // Ensure size constraint obeyed
        assertEquals(2, cache.size());
    }

    @Test
    public void testZeroCopyAccess() {
        cache = new CacheBuilder<String, byte[]>()
                .keySerializer(BuiltInSerializers.STRING_KEY)
                .valueSerializer(BuiltInSerializers.byteArray())
                .offHeapMemory(1024 * 1024)
                .maxEntries(10)
                .build();

        String text = "Hello Zero Copy World";
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        cache.put("ZC", bytes);

        String result = cache.getZeroCopy("ZC", segment -> {
            // Segment contains the value bytes directly
            long len = segment.byteSize();
            byte[] buffer = new byte[(int) len];
            // Manual copy to verify
            MemorySegment.copy(segment, ValueLayout.JAVA_BYTE, 0, buffer, 0, (int) len);
            return new String(buffer, StandardCharsets.UTF_8);
        });

        assertEquals(text, result);
    }
}
