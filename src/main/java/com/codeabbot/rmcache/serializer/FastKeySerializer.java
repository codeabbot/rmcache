package com.codeabbot.rmcache.serializer;

import java.lang.foreign.MemorySegment;

/**
 * Optional key serializer that can match keys without allocating temporary byte arrays.
 */
public interface FastKeySerializer<K> extends KeySerializer<K> {

    /**
     * Match a key directly against off-heap bytes without allocations.
     */
    boolean matchesFast(K key, MemorySegment segment, long offset, int length);

    @Override
    default boolean matches(K key, MemorySegment segment, long offset, int length) {
        return matchesFast(key, segment, offset, length);
    }
}
