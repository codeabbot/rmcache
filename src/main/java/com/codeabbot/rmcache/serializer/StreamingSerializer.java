package com.codeabbot.rmcache.serializer;

/**
 * Specialized serializer for zero-copy streaming.
 */
public interface StreamingSerializer<V> extends ValueSerializer<V> {
    int estimateSize(V value);

    int serializeTo(V value, byte[] dest, int offset);

    V deserializeFrom(byte[] src, int offset, int length);
}
