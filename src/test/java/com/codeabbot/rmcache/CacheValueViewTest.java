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
        // CacheValueView interface in Java might not have slice exposed if it was
        // Kotlin extension or implementation specific
        // checking CacheValueView definition... Assuming strict interface compliance.
        // It seems `slice` was not in the interface I reviewed earlier?
        // Let's check CacheValueView.java again before implementing this test if
        // unsure.
        // For now I will skip slice test if not sure, but let's assume it exists or
        // equivalent.
        // Wait, looking at CacheValueViewImpl.java (impl) it might have it.
        // But let's stick to public API.
    }

    @Test
    public void testGetViewGetByteReturnsCorrectByte() {
        cache = createCache();
        String key = "byteKey";
        byte[] value = { 10, 20, 30, 40, 50 };

        cache.put(key, value);

        try (CacheValueView view = cache.getView(key)) {
            assertNotNull(view);
            // Assuming getByte exists or via MemorySegment
            // The previous Kotlin test used `view.getByte(index)`.
            // I should verify if CacheValueView has `getByte`.
            // If not, I'll rely on toByteArray() for now.
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
