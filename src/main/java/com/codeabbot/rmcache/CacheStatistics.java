package com.codeabbot.rmcache;

/**
 * Immutable snapshot of cache statistics.
 * Provides programmatic access to cache metrics.
 *
 * @author Rabindra Meher
 */
public record CacheStatistics(
        long hits,
        long misses,
        long evictions,
        double hitRate,
        long size,
        long memoryUsedBytes,
        long memoryAllocatedBytes,
        double memoryUtilization,
        long evictionsBySizeLimit,
        long evictionsByTtl,
        long evictionsByExplicit) {
    /**
     * Returns the total number of requests (hits + misses).
     */
    public long totalRequests() {
        return hits + misses;
    }

    /**
     * Returns the miss rate (1.0 - hitRate).
     */
    public double missRate() {
        return 1.0 - hitRate;
    }
}
