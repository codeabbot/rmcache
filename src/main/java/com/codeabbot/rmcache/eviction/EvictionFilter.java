package com.codeabbot.rmcache.eviction;

/**
 * Eviction filter - can veto eviction of specific entries.
 */
@FunctionalInterface
public interface EvictionFilter<K> {
    /**
     * Check if entry can be evicted.
     */
    boolean canEvict(K key, EntryMetadata metadata);
}
