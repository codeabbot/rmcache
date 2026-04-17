/*
 * Copyright 2026 Rabindra Meher
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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

    @Override
    public long getDefaultTTLMs() {
        for (int i = 0; i < policyCount; i++) {
            long t = policies[i].getDefaultTTLMs();
            if (t > 0L)
                return t;
        }
        return 0L;
    }

    @Override
    public boolean tracksTTL() {
        for (int i = 0; i < policyCount; i++) {
            if (policies[i].tracksTTL())
                return true;
        }
        return false;
    }

    @Override
    public void onTTLUpdate(int slot) {
        for (int i = 0; i < policyCount; i++) {
            policies[i].onTTLUpdate(slot);
        }
    }
}
