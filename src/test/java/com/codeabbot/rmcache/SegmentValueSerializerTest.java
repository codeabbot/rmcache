package com.codeabbot.rmcache;

import com.codeabbot.rmcache.serializer.SegmentValueSerializer;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

public class SegmentValueSerializerTest {

    private static final class DirectByteArraySerializer implements SegmentValueSerializer<byte[]> {
        private final AtomicBoolean usedSegment = new AtomicBoolean(false);

        @Override
        public int estimateSize(byte[] value) {
            return value.length;
        }

        @Override
        public int serializeTo(byte[] value, byte[] dest, int offset) {
            System.arraycopy(value, 0, dest, offset, value.length);
            return value.length;
        }

        @Override
        public byte[] deserializeFrom(byte[] src, int offset, int length) {
            return Arrays.copyOfRange(src, offset, offset + length);
        }

        @Override
        public byte[] serialize(byte[] value) {
            return value;
        }

        @Override
        public byte[] deserialize(byte[] bytes) {
            return bytes;
        }

        @Override
        public int serializeTo(byte[] value, MemorySegment dest, long offset, int maxLen) {
            usedSegment.set(true);
            int len = Math.min(value.length, maxLen);
            MemorySegment.copy(value, 0, dest, ValueLayout.JAVA_BYTE, offset, len);
            return len;
        }

        public boolean usedSegment() {
            return usedSegment.get();
        }
    }

    @Test
    public void largeValuesUseDirectSegmentWrite() {
        DirectByteArraySerializer serializer = new DirectByteArraySerializer();
        OffHeapCache<String, byte[]> cache = new CacheBuilder<String, byte[]>()
                .maxEntries(1000)
                .offHeapMemory(64 * 1024 * 1024)
                .ghostCacheSize(0)
                .valueSerializer(serializer)
                .build();

        try {
            byte[] large = new byte[512 * 1024];
            for (int i = 0; i < large.length; i += 4096) {
                large[i] = (byte) (i % 255);
            }

            cache.put("big", large);
            byte[] res = cache.get("big");
            assertNotNull(res);
            assertArrayEquals(large, res);
            assertTrue(serializer.usedSegment(), "Expected direct segment write path");
        } finally {
            cache.close();
        }
    }
}
