package com.codeabbot.rmcache.memory;

import com.codeabbot.rmcache.Units;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class BuddyAllocatorTest {

    private SlabAllocator allocator;

    @AfterEach
    public void tearDown() {
        if (allocator != null) {
            allocator.close();
        }
    }

    @Test
    public void largeAllocationUsesBuddyAllocator() {
        allocator = new SlabAllocator(Units.megabytes(128));

        // Allocate larger than 64KB threshold
        AllocationHandle handle = allocator.allocate(100_000); // ~100KB

        assertNotNull(handle);
        assertTrue(handle.isLarge());
        assertEquals(-1, handle.getSizeClass());
        assertTrue(handle.getCapacity() >= 100_000);

        allocator.free(handle);
    }

    @Test
    public void buddyAllocatorHandlesMultipleLargeAllocations() {
        allocator = new SlabAllocator(Units.megabytes(128));

        List<AllocationHandle> handles = new ArrayList<>();

        // Allocate several large blocks
        for (int i = 0; i < 5; i++) {
            AllocationHandle handle = allocator.allocate(200_000); // 200KB each
            assertNotNull(handle);
            assertTrue(handle.isLarge());
            handles.add(handle);
        }

        // Free all
        for (AllocationHandle h : handles) {
            allocator.free(h);
        }
    }

    @Test
    public void buddyAllocatorCoalescesFreedBlocks() {
        allocator = new SlabAllocator(Units.megabytes(128));

        // Allocate two adjacent blocks
        AllocationHandle handle1 = allocator.allocate(128_000);
        AllocationHandle handle2 = allocator.allocate(128_000);

        // Free them
        allocator.free(handle1);
        allocator.free(handle2);

        // Should be able to allocate a larger block due to coalescing
        AllocationHandle largeHandle = allocator.allocate(256_000);
        assertNotNull(largeHandle);

        allocator.free(largeHandle);
    }

    @Test
    public void mixedSlabAndBuddyAllocations() {
        allocator = new SlabAllocator(Units.megabytes(256));

        List<AllocationHandle> handles = new ArrayList<>();

        // Small allocations (slab)
        for (int i = 0; i < 100; i++) {
            handles.add(allocator.allocate(1024)); // 1KB - slab
        }

        // Large allocations (buddy)
        for (int i = 0; i < 10; i++) {
            handles.add(allocator.allocate(100_000)); // 100KB - buddy
        }

        // Verify mix
        long slabCount = handles.stream().filter(h -> !h.isLarge()).count();
        long buddyCount = handles.stream().filter(h -> h.isLarge()).count();

        assertEquals(100, slabCount);
        assertEquals(10, buddyCount);

        // Free all
        for (AllocationHandle h : handles) {
            allocator.free(h);
        }
    }
}
