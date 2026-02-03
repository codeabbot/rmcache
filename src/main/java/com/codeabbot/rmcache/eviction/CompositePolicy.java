package com.codeabbot.rmcache.eviction;

import com.codeabbot.rmcache.index.EntryPool;
import java.util.List;

/**
 * Composite policy that combines multiple policies.
 */
public class CompositePolicy implements EvictionPolicy {
    private final List<EvictionPolicy> policies;
    private final int maxEntries;

    public CompositePolicy(List<EvictionPolicy> policies) {
        this.policies = policies;
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
        return policies.isEmpty() ? 0 : policies.get(0).size();
    }

    @Override
    public void onAccess(int slot, int keyHash) {
        policies.forEach(p -> p.onAccess(slot, keyHash));
    }

    @Override
    public void onAdd(int slot, int keyHash, short priority) {
        policies.forEach(p -> p.onAdd(slot, keyHash, priority));
    }

    @Override
    public void onRemove(int slot) {
        policies.forEach(p -> p.onRemove(slot));
    }

    @Override
    public int selectVictim() {
        for (EvictionPolicy policy : policies) {
            int victim = policy.selectVictim();
            if (victim != 0)
                return victim;
        }
        return 0;
    }

    @Override
    public boolean shouldEvict() {
        return policies.stream().anyMatch(EvictionPolicy::shouldEvict);
    }

    @Override
    public void close() {
        for (EvictionPolicy policy : policies) {
            try {
                policy.close();
            } catch (Exception e) {
                // Ignore close errors
            }
        }
    }

    @Override
    public void drainBuffers() {
        policies.forEach(EvictionPolicy::drainBuffers);
    }

    @Override
    public void setEntryPool(EntryPool pool) {
        policies.forEach(p -> p.setEntryPool(pool));
    }
}
