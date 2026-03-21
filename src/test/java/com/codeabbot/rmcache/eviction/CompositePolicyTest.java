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

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class CompositePolicyTest {

    @Test
    public void testWithNoEvictionPolicies() {
        CompositePolicy composite = new CompositePolicy(
                List.of(new NoEvictionPolicy(100), new NoEvictionPolicy(200)));

        // maxEntries is the min of sub-policies
        assertEquals(100, composite.getMaxEntries());

        // NoEvictionPolicy always returns false
        assertFalse(composite.shouldEvict());
        assertEquals(0, composite.selectVictim());
        assertEquals(0, composite.size());
    }

    @Test
    public void testEmptyPoliciesList() {
        CompositePolicy composite = new CompositePolicy(List.of());
        assertEquals(Integer.MAX_VALUE, composite.getMaxEntries());
        assertEquals(0, composite.size());
        assertFalse(composite.shouldEvict());
        assertEquals(0, composite.selectVictim());
    }

    @Test
    public void testShouldEvictDelegatesToSubPolicies() {
        // First policy says no, second says yes
        EvictionPolicy alwaysEvict = new StubEvictionPolicy(true, 42, 10, 5);
        EvictionPolicy neverEvict = new NoEvictionPolicy(100);

        CompositePolicy composite = new CompositePolicy(List.of(neverEvict, alwaysEvict));
        assertTrue(composite.shouldEvict());
    }

    @Test
    public void testShouldEvictAllFalse() {
        CompositePolicy composite = new CompositePolicy(
                List.of(new NoEvictionPolicy(), new NoEvictionPolicy()));
        assertFalse(composite.shouldEvict());
    }

    @Test
    public void testSelectVictimReturnsFirstNonZero() {
        EvictionPolicy returnsZero = new StubEvictionPolicy(false, 0, 10, 50);
        EvictionPolicy returns42 = new StubEvictionPolicy(false, 42, 10, 50);
        EvictionPolicy returns99 = new StubEvictionPolicy(false, 99, 10, 50);

        CompositePolicy composite = new CompositePolicy(
                List.of(returnsZero, returns42, returns99));
        assertEquals(42, composite.selectVictim());
    }

    @Test
    public void testSelectVictimAllZero() {
        CompositePolicy composite = new CompositePolicy(
                List.of(new NoEvictionPolicy(), new NoEvictionPolicy()));
        assertEquals(0, composite.selectVictim());
    }

    @Test
    public void testOnAddPropagates() {
        AtomicInteger addCount1 = new AtomicInteger();
        AtomicInteger addCount2 = new AtomicInteger();
        EvictionPolicy p1 = new CountingPolicy(addCount1);
        EvictionPolicy p2 = new CountingPolicy(addCount2);

        CompositePolicy composite = new CompositePolicy(List.of(p1, p2));
        composite.onAdd(1, 100, (short) 0);

        assertEquals(1, addCount1.get());
        assertEquals(1, addCount2.get());
    }

    @Test
    public void testOnAccessPropagates() {
        AtomicInteger accessCount1 = new AtomicInteger();
        AtomicInteger accessCount2 = new AtomicInteger();
        EvictionPolicy p1 = new CountingPolicy(accessCount1);
        EvictionPolicy p2 = new CountingPolicy(accessCount2);

        CompositePolicy composite = new CompositePolicy(List.of(p1, p2));
        composite.onAccess(1, 100);
        composite.onAccess(2, 200);

        assertEquals(2, accessCount1.get());
        assertEquals(2, accessCount2.get());
    }

    @Test
    public void testOnRemovePropagates() {
        AtomicInteger removeCount1 = new AtomicInteger();
        AtomicInteger removeCount2 = new AtomicInteger();
        EvictionPolicy p1 = new CountingPolicy(removeCount1);
        EvictionPolicy p2 = new CountingPolicy(removeCount2);

        CompositePolicy composite = new CompositePolicy(List.of(p1, p2));
        composite.onRemove(1);

        assertEquals(1, removeCount1.get());
        assertEquals(1, removeCount2.get());
    }

    @Test
    public void testSizeReturnsFirstPolicySize() {
        EvictionPolicy size5 = new StubEvictionPolicy(false, 0, 5, 100);
        EvictionPolicy size10 = new StubEvictionPolicy(false, 0, 10, 200);

        CompositePolicy composite = new CompositePolicy(List.of(size5, size10));
        assertEquals(5, composite.size());
    }

    @Test
    public void testMaxEntriesIsMinOfSubPolicies() {
        EvictionPolicy max50 = new StubEvictionPolicy(false, 0, 0, 50);
        EvictionPolicy max30 = new StubEvictionPolicy(false, 0, 0, 30);
        EvictionPolicy max80 = new StubEvictionPolicy(false, 0, 0, 80);

        CompositePolicy composite = new CompositePolicy(List.of(max50, max30, max80));
        assertEquals(30, composite.getMaxEntries());
    }

    @Test
    public void testCloseDoesNotThrow() {
        CompositePolicy composite = new CompositePolicy(
                List.of(new NoEvictionPolicy(), new NoEvictionPolicy()));
        assertDoesNotThrow(composite::close);
    }

    // ---- Stub and counting helpers (package-private) ----

    /**
     * Minimal stub that returns fixed values for shouldEvict, selectVictim, size,
     * and maxEntries.
     */
    private static class StubEvictionPolicy implements EvictionPolicy {
        private final boolean evict;
        private final int victim;
        private final int size;
        private final int maxEntries;

        StubEvictionPolicy(boolean evict, int victim, int size, int maxEntries) {
            this.evict = evict;
            this.victim = victim;
            this.size = size;
            this.maxEntries = maxEntries;
        }

        @Override public void onAccess(int slot, int keyHash) {}
        @Override public void onAdd(int slot, int keyHash, short priority) {}
        @Override public void onRemove(int slot) {}
        @Override public int selectVictim() { return victim; }
        @Override public boolean shouldEvict() { return evict; }
        @Override public int size() { return size; }
        @Override public int getMaxEntries() { return maxEntries; }
    }

    /**
     * Policy that counts onAdd, onAccess, and onRemove calls via a shared
     * AtomicInteger.
     * The counter is incremented on every callback.
     */
    private static class CountingPolicy implements EvictionPolicy {
        private final AtomicInteger counter;

        CountingPolicy(AtomicInteger counter) {
            this.counter = counter;
        }

        @Override public void onAccess(int slot, int keyHash) { counter.incrementAndGet(); }
        @Override public void onAdd(int slot, int keyHash, short priority) { counter.incrementAndGet(); }
        @Override public void onRemove(int slot) { counter.incrementAndGet(); }
        @Override public int selectVictim() { return 0; }
        @Override public boolean shouldEvict() { return false; }
        @Override public int size() { return 0; }
        @Override public int getMaxEntries() { return Integer.MAX_VALUE; }
    }
}
