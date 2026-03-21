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
