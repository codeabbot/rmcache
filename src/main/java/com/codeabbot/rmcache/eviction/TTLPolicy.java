package com.codeabbot.rmcache.eviction;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Time-to-Live (TTL) eviction policy.
 */
public class TTLPolicy implements EvictionPolicy {
    private final long defaultTTLMs;
    private final int maxEntries;
    private final TimingWheel timingWheel = new TimingWheel();
    private final Map<Integer, Long> entries = new ConcurrentHashMap<>();
    private final AtomicInteger _size = new AtomicInteger(0);

    public TTLPolicy(long defaultTTLMs, int maxEntries) {
        this.defaultTTLMs = defaultTTLMs;
        this.maxEntries = maxEntries;
    }

    public TTLPolicy(long defaultTTLMs) {
        this(defaultTTLMs, Integer.MAX_VALUE);
    }

    @Override
    public int size() {
        return _size.get();
    }

    @Override
    public int getMaxEntries() {
        return maxEntries;
    }

    @Override
    public void onAccess(int slot, int keyHash) {
    }

    @Override
    public void onAdd(int slot, int keyHash, short priority) {
        long expiresAt = System.currentTimeMillis() + defaultTTLMs;
        entries.put(slot, expiresAt);
        timingWheel.schedule(slot, expiresAt);
        _size.incrementAndGet();
    }

    public void onAddWithTTL(int slot, int keyHash, long ttlMs) {
        long expiresAt = System.currentTimeMillis() + ttlMs;
        entries.put(slot, expiresAt);
        timingWheel.schedule(slot, expiresAt);
        _size.incrementAndGet();
    }

    @Override
    public void onRemove(int slot) {
        if (entries.remove(slot) != null) {
            _size.decrementAndGet();
        }
    }

    @Override
    public int selectVictim() {
        List<Integer> expired = timingWheel.pollExpired(1);
        for (int slot : expired) {
            if (entries.containsKey(slot)) {
                return slot;
            }
        }
        return 0;
    }

    @Override
    public boolean shouldEvict() {
        return timingWheel.hasExpired() || _size.get() >= maxEntries;
    }

    public boolean isExpired(int slot) {
        Long expiresAt = entries.get(slot);
        return expiresAt != null && System.currentTimeMillis() >= expiresAt;
    }
}
