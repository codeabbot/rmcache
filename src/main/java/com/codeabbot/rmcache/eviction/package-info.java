/**
 * Eviction policies for RMCache.
 *
 * <p>
 * Provides pluggable eviction strategies:
 * <ul>
 * <li>{@link com.codeabbot.rmcache.eviction.LRUPolicy} — W-TinyLFU (3-segment
 * SLRU + frequency sketch)</li>
 * <li>{@link com.codeabbot.rmcache.eviction.TTLPolicy} — time-to-live
 * expiration via off-heap timing wheel</li>
 * <li>{@link com.codeabbot.rmcache.eviction.CompositePolicy} — combines
 * multiple policies (e.g., LRU + TTL)</li>
 * <li>{@link com.codeabbot.rmcache.eviction.NoEvictionPolicy} — disables
 * eviction entirely</li>
 * </ul>
 *
 * <p>
 * All eviction metadata (linked-list pointers, frequency counters, expiration
 * timestamps) is stored off-heap.
 *
 * @author Rabindra Meher
 */
package com.codeabbot.rmcache.eviction;
