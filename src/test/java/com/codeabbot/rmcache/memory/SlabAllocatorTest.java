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
package com.codeabbot.rmcache.memory;

import com.codeabbot.rmcache.Units;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class SlabAllocatorTest {

    @Test
    public void testBasicAllocationAndFree() {
        SlabAllocator allocator = new SlabAllocator(Units.megabytes(64));

        // Allocate small block
        AllocationHandle handle1 = allocator.allocate(100);
        assertEquals(128, handle1.getCapacity()); // Next size class

        // Allocate large block
        AllocationHandle handle2 = allocator.allocate(100_000);
        assertEquals(100_000, handle2.getCapacity());
        assertTrue(handle2.isLarge());

        // Free and verify stats
        long initialUsed = allocator.getUsedBytes();
        allocator.free(handle1);
        allocator.free(handle2);

        assertTrue(allocator.getUsedBytes() < initialUsed);

        allocator.close();
    }

    @Test
    public void testThreadLocalCache() {
        SlabAllocator allocator = new SlabAllocator(Units.megabytes(64));

        // Allocate and free in same thread
        AllocationHandle handle1 = allocator.allocate(64);
        long offset1 = handle1.getOffset();
        allocator.free(handle1);

        // Next allocation should come from thread-local cache
        AllocationHandle handle2 = allocator.allocate(64);
        assertEquals(offset1, handle2.getOffset());

        allocator.close();
    }
}
