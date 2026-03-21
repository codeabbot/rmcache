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
}
