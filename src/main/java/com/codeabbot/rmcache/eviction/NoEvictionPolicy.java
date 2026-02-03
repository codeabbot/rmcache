package com.codeabbot.rmcache.eviction;

/**
 * Policy that performs no eviction.
 */
public class NoEvictionPolicy implements EvictionPolicy {
    private final int maxEntries;

    public NoEvictionPolicy(int maxEntries) {
        this.maxEntries = maxEntries;
    }

    public NoEvictionPolicy() {
        this(Integer.MAX_VALUE);
    }

    @Override
    public void onAccess(int slot, int keyHash) {
    }

    @Override
    public void onAdd(int slot, int keyHash, short priority) {
    }

    @Override
    public void onRemove(int slot) {
    }

    @Override
    public int selectVictim() {
        return 0;
    }

    @Override
    public boolean shouldEvict() {
        return false;
    }

    @Override
    public int size() {
        return 0;
    }

    @Override
    public int getMaxEntries() {
        return maxEntries;
    }
}
