package com.codeabbot.rmcache.eviction;

import java.util.function.Supplier;

/**
 * Eviction listener - Called before eviction.
 */
@FunctionalInterface
public interface EvictionListener<K, V> {
    /**
     * Called before eviction.
     * 
     * @param key   The key being evicted
     * @param value Lazy value (loaded from off-heap only if accessed)
     * @param cause Reason for eviction
     */
    void onEviction(K key, Supplier<V> value, EvictionCause cause);
}
