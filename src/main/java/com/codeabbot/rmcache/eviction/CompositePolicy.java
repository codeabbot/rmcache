package com.codeabbot.rmcache.eviction;

import com.codeabbot.rmcache.index.EntryPool;

/**
 * Composite policy that combines multiple policies.
 * P4-C1 fix: All hot-path methods use indexed for-loops instead of
 * Stream/forEach to avoid 3-4 allocations per cache operation.
 */
public class CompositePolicy implements EvictionPolicy {
    private final EvictionPolicy[] policies; // array instead of List for bounds-check elision
    private final int policyCount;
    private final int maxEntries;

    public CompositePolicy(java.util.List<EvictionPolicy> policies) {
        this.policies = policies.toArray(new EvictionPolicy[0]);
        this.policyCount = this.policies.length;
        this.maxEntries = policies.stream()
                .mapToInt(EvictionPolicy::getMaxEntries)
                .min()
                .orElse(Integer.MAX_VALUE);
    }

    @Override
    public int getMaxEntries() {
        return maxEntries;
    }

    @Override
    public int size() {
        return policyCount == 0 ? 0 : policies[0].size();
    }

    @Override
    public void onAccess(int slot, int keyHash) {
        for (int i = 0; i < policyCount; i++) {
            policies[i].onAccess(slot, keyHash);
        }
    }

    @Override
    public void onAdd(int slot, int keyHash, short priority) {
        for (int i = 0; i < policyCount; i++) {
            policies[i].onAdd(slot, keyHash, priority);
        }
    }

    @Override
    public void onRemove(int slot) {
        for (int i = 0; i < policyCount; i++) {
            policies[i].onRemove(slot);
        }
    }

    @Override
    public int selectVictim() {
        for (int i = 0; i < policyCount; i++) {
            int victim = policies[i].selectVictim();
            if (victim != 0)
                return victim;
        }
        return 0;
    }

    @Override
    public boolean shouldEvict() {
        for (int i = 0; i < policyCount; i++) {
            if (policies[i].shouldEvict())
                return true;
        }
        return false;
    }

    @Override
    public void close() {
        for (int i = 0; i < policyCount; i++) {
            try {
                policies[i].close();
            } catch (Exception e) {
                // Ignore close errors
            }
        }
    }

    @Override
    public void drainBuffers() {
        for (int i = 0; i < policyCount; i++) {
            policies[i].drainBuffers();
        }
    }

    @Override
    public void compact() {
        for (int i = 0; i < policyCount; i++) {
            policies[i].compact();
        }
    }

    @Override
    public void setEntryPool(EntryPool pool) {
        for (int i = 0; i < policyCount; i++) {
            policies[i].setEntryPool(pool);
        }
    }
}
