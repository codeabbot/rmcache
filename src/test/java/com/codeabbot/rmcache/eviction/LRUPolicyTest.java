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
import com.codeabbot.rmcache.memory.SlabAllocator;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class LRUPolicyTest {

    @Test
    public void testLRUOverflow() {
        SlabAllocator allocator = new SlabAllocator(10 * 1024 * 1024);
        EntryPool pool = new EntryPool(allocator, 200); // Pool larger than policy
        LRUPolicy policy = new LRUPolicy(200); // Policy tracking all pool slots
        policy.setEntryPool(pool);

        // Add 110 items
        for (int i = 1; i <= 110; i++) {
            policy.onAdd(i, i, (short) 0);
        }

        // Size should be tracked
        assertEquals(110, policy.size());

        // Select victim
        int victim = policy.selectVictim();
        assertTrue(victim > 0);

        allocator.close();
        policy.close();
    }

    @Test
    public void testPromotions() {
        SlabAllocator allocator = new SlabAllocator(10 * 1024 * 1024);
        EntryPool pool = new EntryPool(allocator, 200);
        LRUPolicy policy = new LRUPolicy(200);
        policy.setEntryPool(pool);

        // Add 10 items
        for (int i = 1; i <= 10; i++) {
            policy.onAdd(i, i, (short) 0);
        }

        // Access item 1 many times to promote
        for (int i = 0; i < 100; i++) {
            policy.onAccess(1, 1);
        }

        policy.drainBuffers();

        // Size should still be 10
        assertEquals(10, policy.size());

        allocator.close();
        policy.close();
    }

    @Test
    public void closeIsIdempotent() {
        LRUPolicy policy = new LRUPolicy(200);

        assertDoesNotThrow(policy::close);
        assertDoesNotThrow(policy::close);
    }

    /**
     * W-TinyLFU admission must consult the frequency sketch at eviction time:
     * when a high-frequency entry sits at the probation LRU tail and a
     * low-frequency one-hit candidate is the MRU head, the candidate is the one
     * evicted and the hot tail survives. Plain FIFO tail eviction (the pre-fix
     * "dead sketch" behavior) would instead have evicted the tail — the hot
     * entry. This test therefore fails if the admission path ever regresses back
     * to ignoring the sketch.
     */
    @Test
    public void selectVictim_admissionProtectsHotTail_overColdCandidate() throws Exception {
        SlabAllocator allocator = new SlabAllocator(16 * 1024 * 1024);
        EntryPool pool = new EntryPool(allocator, 256);
        LRUPolicy policy = new LRUPolicy(256);
        try {
            final int HOT_HASH = 0x1111_1111;
            final int COLD_HASH = 0x2222_2222;
            byte[] kh = "hot".getBytes();
            byte[] kc = "cold".getBytes();
            byte[] v = "v".getBytes();
            int slotHot = pool.allocateWithLen(HOT_HASH, kh, kh.length, v, v.length, (short) 0, 0L);
            int slotCold = pool.allocateWithLen(COLD_HASH, kc, kc.length, v, v.length, (short) 0, 0L);
            assertTrue(slotHot > 0 && slotCold > 0 && slotHot != slotCold);

            policy.setEntryPool(pool);

            // Raise HOT_HASH's estimated frequency in the (post-setEntryPool) sketch.
            Field fSketch = LRUPolicy.class.getDeclaredField("frequencySketch");
            fSketch.setAccessible(true);
            OffHeapFrequencySketch sketch = (OffHeapFrequencySketch) fSketch.get(policy);
            for (int i = 0; i < 30; i++) {
                sketch.increment(HOT_HASH);
            }
            assertTrue(sketch.frequency(HOT_HASH) > sketch.frequency(COLD_HASH),
                    "hot key must have the higher estimated frequency");

            // Force both into one shard's probation: HOT as LRU tail (oldest),
            // COLD as MRU head (newest one-hit candidate).
            Field fShards = LRUPolicy.class.getDeclaredField("shards");
            fShards.setAccessible(true);
            OffHeapCompactLRU[] shards = (OffHeapCompactLRU[]) fShards.get(policy);
            OffHeapCompactLRU shard0 = shards[0];
            shard0.addToProbation(slotHot);  // oldest -> tail
            shard0.addToProbation(slotCold); // newest -> head

            int victim = policy.selectVictim();

            assertEquals(slotCold, victim,
                    "admission must evict the low-frequency candidate, not the hot tail");
            assertEquals(OffHeapCompactLRU.PROBATION, shard0.getSegment(slotHot),
                    "high-frequency entry must remain resident after admission");
        } finally {
            policy.close();
            allocator.close();
        }
    }
}
