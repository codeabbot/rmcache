package com.codeabbot.rmcache.serializer;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Interface for key serialization and matching.
 */
public interface KeySerializer<K> {
    byte[] serialize(K key);

    K deserialize(byte[] bytes);

    int hashCode(K key);

    default boolean matches(K key, MemorySegment segment, long offset, int length) {
        byte[] bytes = serialize(key);
        if (bytes.length != length)
            return false;
        return segment.asSlice(offset, (long) length).mismatch(MemorySegment.ofArray(bytes)) == -1L;
    }

    default int hashCode(MemorySegment segment, long offset, int length) {
        int h = 0;
        for (int i = 0; i < length; i++) {
            h = 31 * h + segment.get(ValueLayout.JAVA_BYTE, offset + i);
        }
        return h;
    }
}
