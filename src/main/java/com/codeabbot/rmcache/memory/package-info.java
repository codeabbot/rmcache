/**
 * Native memory management for RMCache.
 *
 * <p>
 * Manages off-heap memory allocation via:
 * <ul>
 * <li>{@link com.codeabbot.rmcache.memory.SlabAllocator} — slab-based allocator
 * with lock-free bitmap allocation</li>
 * <li>{@link com.codeabbot.rmcache.memory.BuddyAllocator} — buddy allocator for
 * values exceeding slab size</li>
 * <li>{@link com.codeabbot.rmcache.memory.AllocationHandle} — packed 64-bit
 * handle (offset + capacity + sizeClass)</li>
 * <li>{@link com.codeabbot.rmcache.memory.NativeMemory} — thin wrapper over C
 * malloc/calloc/free via Panama FFM</li>
 * </ul>
 *
 * @author Rabindra Meher
 */
package com.codeabbot.rmcache.memory;
