/**
 * Index structures for RMCache.
 *
 * <p>
 * Contains the hash table, entry pool, and ghost cache:
 * <ul>
 * <li>{@link com.codeabbot.rmcache.index.OffHeapHashTable} — Robin Hood hash
 * table with StampedLock optimistic reads</li>
 * <li>{@link com.codeabbot.rmcache.index.EntryPool} — partitioned slot
 * allocator storing key/value metadata off-heap</li>
 * <li>{@link com.codeabbot.rmcache.index.OffHeapGhostCache} — direct-mapped L1
 * shortcut for hot keys</li>
 * </ul>
 *
 * @author Rabindra Meher
 */
package com.codeabbot.rmcache.index;
