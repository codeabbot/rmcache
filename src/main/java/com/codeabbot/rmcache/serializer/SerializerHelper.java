package com.codeabbot.rmcache.serializer;

import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import java.util.function.ToIntFunction;

/**
 * Helper factory for building optimized serializers with minimal boilerplate.
 */
public final class SerializerHelper {

    @FunctionalInterface
    public interface SegmentWriter<V> {
        int write(V value, MemorySegment dest, long offset, int maxLen);
    }

    @FunctionalInterface
    public interface ByteArrayDeserializer<V> {
        V deserialize(byte[] src, int offset, int length);
    }

    private SerializerHelper() {
    }

    public static <V> SegmentValueSerializer<V> segment(
            ToIntFunction<V> sizeEstimator,
            SegmentWriter<V> writer,
            ByteArrayDeserializer<V> deserializer) {
        return new SegmentValueSerializer<>() {
            @Override
            public int estimateSize(V value) {
                return Math.max(0, sizeEstimator.applyAsInt(value));
            }

            @Override
            public int serializeTo(V value, byte[] dest, int offset) {
                MemorySegment segment = MemorySegment.ofArray(dest);
                return writer.write(value, segment, offset, dest.length - offset);
            }

            @Override
            public int serializeTo(V value, MemorySegment dest, long offset, int maxLen) {
                return writer.write(value, dest, offset, maxLen);
            }

            @Override
            public byte[] serialize(V value) {
                int size = estimateSize(value);
                byte[] buffer = new byte[size];
                int written = serializeTo(value, buffer, 0);
                if (written == size) {
                    return buffer;
                }
                return Arrays.copyOf(buffer, Math.max(0, written));
            }

            @Override
            public V deserialize(byte[] bytes) {
                return deserializer.deserialize(bytes, 0, bytes.length);
            }

            @Override
            public V deserializeFrom(byte[] src, int offset, int length) {
                return deserializer.deserialize(src, offset, length);
            }
        };
    }
}
