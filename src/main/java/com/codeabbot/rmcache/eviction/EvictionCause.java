package com.codeabbot.rmcache.eviction;

/**
 * Cause of eviction.
 */
public enum EvictionCause {
    /** Evicted due to max entries reached */
    SIZE,
    /** Evicted due to TTL expiration */
    EXPIRED,
    /** Explicitly removed via remove() or clear() */
    EXPLICIT,
    /** Replaced by a new value via put() */
    REPLACED,
}
