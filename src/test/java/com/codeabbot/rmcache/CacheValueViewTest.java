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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

public class CacheValueViewTest {

    private OffHeapCache<String, byte[]> cache;

    @AfterEach
    public void tearDown() {
        if (cache != null) {
            cache.close();
        }
    }

    private OffHeapCache<String, byte[]> createCache(int maxEntries) {
        return new CacheBuilder<String, byte[]>()
                .keySerializer(BuiltInSerializers.STRING_KEY)
                .valueSerializer(BuiltInSerializers.byteArray())
                .offHeapMemory(32 * 1024 * 1024)
                .maxEntries(maxEntries)
                .build();
    }

    private OffHeapCache<String, byte[]> createCache() {
        return createCache(1000);
    }

    @Test
    public void testGetViewReturnsNullForMissingKey() {
        cache = createCache();
        CacheValueView view = cache.getView("missing");
        assertNull(view);
    }

    @Test
    public void testGetViewReturnsValidViewForExistingKey() {
        cache = createCache();
        String key = "testKey";
        byte[] value = "testValue123".getBytes();

        cache.put(key, value);

        try (CacheValueView view = cache.getView(key)) {
            assertNotNull(view);
            assertEquals(value.length, view.size());
            assertTrue(view.isValid());
        }
    }

    @Test
    public void testGetViewToByteArrayReturnsCorrectData() throws IOException {
        cache = createCache();
        String key = "key";
        byte[] value = "hello world from off-heap".getBytes();

        cache.put(key, value);

        try (CacheValueView view = cache.getView(key)) {
            assertNotNull(view);
            byte[] bytes = view.toByteArray();
            assertArrayEquals(value, bytes);
        }
    }

    @Test
    public void testGetViewCopyToStreamsDataCorrectly() throws IOException {
        cache = createCache();
        String key = "streamKey";
        byte[] value = new byte[10_000];
        for (int i = 0; i < value.length; i++) {
            value[i] = (byte) i;
        }

        cache.put(key, value);

        try (CacheValueView view = cache.getView(key)) {
            assertNotNull(view);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            view.copyTo(output); // Buffer size handling is internal or overloaded
            assertArrayEquals(value, output.toByteArray());
        }
    }

    @Test
    public void testGetViewSliceReturnsPartialData() {
        cache = createCache();
        String key = "sliceKey";
        byte[] value = { 10, 20, 30, 40, 50, 60, 70, 80 };

        cache.put(key, value);

        try (CacheValueView view = cache.getView(key)) {
            assertNotNull(view);

            // Slice from offset 2, length 3 -> should return {30, 40, 50}
            byte[] partial = view.slice(2, 3);
            assertArrayEquals(new byte[] { 30, 40, 50 }, partial);

            // Slice from offset 0, length 1 -> should return {10}
            byte[] first = view.slice(0, 1);
            assertArrayEquals(new byte[] { 10 }, first);

            // Slice the entire value
            byte[] full = view.slice(0, value.length);
            assertArrayEquals(value, full);

            // Empty slice
            byte[] empty = view.slice(0, 0);
            assertEquals(0, empty.length);
        }
    }

    @Test
    public void testGetViewGetByteReturnsCorrectByte() {
        cache = createCache();
        String key = "byteKey";
        byte[] value = { 10, 20, 30, 40, 50 };

        cache.put(key, value);

        try (CacheValueView view = cache.getView(key)) {
            assertNotNull(view);
            assertEquals((byte) 10, view.getByte(0));
            assertEquals((byte) 20, view.getByte(1));
            assertEquals((byte) 30, view.getByte(2));
            assertEquals((byte) 40, view.getByte(3));
            assertEquals((byte) 50, view.getByte(4));
        }
    }

    @Test
    public void testGetViewIsValidReturnsFalseAfterClose() throws Exception {
        cache = createCache();
        String key = "validKey";
        byte[] value = "data".getBytes();

        cache.put(key, value);

        CacheValueView view = cache.getView(key);
        assertNotNull(view);
        assertTrue(view.isValid());
        view.close();
        assertFalse(view.isValid());
    }

    @Test
    public void testGetViewIsValidReturnsFalseAfterRemove() {
        cache = createCache();
        String key = "removedKey";
        byte[] value = "data".getBytes();

        cache.put(key, value);

        CacheValueView view = cache.getView(key);
        assertNotNull(view);
        assertTrue(view.isValid());
        assertTrue(cache.remove(key));

        assertFalse(view.isValid());
        assertThrows(IllegalStateException.class, view::toByteArray);
        view.close();
    }

    @Test
    public void testGetViewThrowsAfterClose() throws Exception {
        cache = createCache();
        String key = "throwKey";
        byte[] value = "data".getBytes();

        cache.put(key, value);

        CacheValueView view = cache.getView(key);
        assertNotNull(view);
        view.close();

        assertThrows(IllegalStateException.class, view::toByteArray);
    }
}
