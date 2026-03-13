/**
 * RMCache — High-Performance, Billion-Scale Off-Heap Cache for Java 25+ (LTS).
 *
 * <p>
 * This is the primary public API package. Key entry points:
 * <ul>
 * <li>{@link com.codeabbot.rmcache.CacheBuilder} — fluent builder for
 * configuring and creating cache instances</li>
 * <li>{@link com.codeabbot.rmcache.OffHeapCache} — the main cache interface
 * (get, put, remove, getView)</li>
 * <li>{@link com.codeabbot.rmcache.CacheValueView} — zero-copy view into native
 * memory for a cached value</li>
 * </ul>
 *
 * <p>
 * All data (keys, values, index structures) is stored off-heap using the
 * Java Foreign Function &amp; Memory (FFM) API. The Java heap is used only for
 * control structures (locks, counters, thread-local buffers).
 *
 * @author Rabindra Meher
 */
package com.codeabbot.rmcache;
