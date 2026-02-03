package com.codeabbot.rmcache.index;

import com.codeabbot.rmcache.util.CoarseClock;

/**
 * Ultra-fast L1 cache using direct-mapped design with volatile slot array.
 *
 * @author Rabindra Meher
 */
public class GhostCache<K, V> {
    private final int actualCapacity;
    private final int mask;

    private volatile Entry<K, V>[] slots;

    @SuppressWarnings("unchecked")
    public GhostCache(int capacity) {
        int cap = 1;
        while (cap < capacity)
            cap <<= 1;
        this.actualCapacity = cap;
        this.mask = cap - 1;
        this.slots = new Entry[cap];
    }

    public GhostCache() {
        this(4096);
    }

    public V get(K key, int keyHash) {
        int idx = keyHash & mask;
        Entry<K, V>[] currentSlots = slots;
        Entry<K, V> entry = currentSlots[idx];

        if (entry == null)
            return null;

        if (entry.hash == keyHash && entry.key.equals(key)) {
            if (entry.expiresAt > 0 && entry.expiresAt < CoarseClock.getNow()) {
                currentSlots[idx] = null;
                return null;
            }
            return entry.value;
        }
        return null;
    }

    public void put(K key, int keyHash, V value, long ttlMs) {
        int idx = keyHash & mask;
        long expiresAt = (ttlMs > 0) ? CoarseClock.getNow() + ttlMs : 0L;
        slots[idx] = new Entry<>(key, keyHash, value, expiresAt);
    }

    public void put(K key, int keyHash, V value) {
        put(key, keyHash, value, 0);
    }

    public void invalidate(int keyHash) {
        int idx = keyHash & mask;
        Entry<K, V> entry = slots[idx];
        if (entry != null && entry.hash == keyHash) {
            slots[idx] = null;
        }
    }

    @SuppressWarnings("unchecked")
    public void invalidateAll() {
        this.slots = new Entry[actualCapacity];
    }

    public GhostCacheStats stats() {
        int count = 0;
        Entry<K, V>[] currentSlots = slots;
        for (int i = 0; i < actualCapacity; i++) {
            if (currentSlots[i] != null)
                count++;
        }
        return new GhostCacheStats(actualCapacity, 0L, 0L, 0.0);
    }

    private static class Entry<K, V> {
        final K key;
        final int hash;
        final V value;
        final long expiresAt;

        Entry(K key, int hash, V value, long expiresAt) {
            this.key = key;
            this.hash = hash;
            this.value = value;
            this.expiresAt = expiresAt;
        }
    }

    public record GhostCacheStats(
            int capacity,
            long hits,
            long misses,
            double hitRate) {
    }
}
