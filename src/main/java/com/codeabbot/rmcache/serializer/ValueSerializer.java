package com.codeabbot.rmcache.serializer;

/**
 * Interface for value serialization.
 */
public interface ValueSerializer<V> {
    byte[] serialize(V value);

    V deserialize(byte[] bytes);
}
