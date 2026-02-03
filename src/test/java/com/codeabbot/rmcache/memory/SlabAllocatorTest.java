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
