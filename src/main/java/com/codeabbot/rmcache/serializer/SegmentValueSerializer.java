package com.codeabbot.rmcache.serializer;

import java.lang.foreign.MemorySegment;

/**
 * Serializer that can write values directly into off-heap memory.
 */
public interface SegmentValueSerializer<V> extends StreamingSerializer<V> {
    /**
     * Write value into off-heap memory.
     *
     * @param value  value to serialize
     * @param dest   destination segment (typically NativeMemory.UNLIMITED)
     * @param offset destination offset
     * @param maxLen maximum allowed bytes
     * @return actual bytes written (must be {@literal <=} maxLen)
     */
    int serializeTo(V value, MemorySegment dest, long offset, int maxLen);
}
