package com.codeabbot.rmcache.memory;

import java.lang.foreign.MemorySegment;
import java.util.BitSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Buddy allocator for large allocations (> 64KB).
 *
 * @author Rabindra Meher
 */
class BuddyAllocator {
    private final MemorySegment segment;
    private final long baseOffset;
    private final long capacity;
    private final int minBlockSize;

    private final int levels;
    private final int minOrder;
    private final int maxOrder;

    // Free lists for each order: stored as offsets relative to baseOffset
    private final Set<Long>[] freeLists;

    // Status bits: true = allocated, false = free
    private final BitSet allocatedMap;

    private final ReentrantLock lock = new ReentrantLock();

    @SuppressWarnings("unchecked")
    public BuddyAllocator(MemorySegment segment, long baseOffset, long capacity, int minBlockSize) {
        if (Long.bitCount(capacity) != 1) {
            throw new IllegalArgumentException("Capacity must be power of 2: " + capacity);
        }
        if (Integer.bitCount(minBlockSize) != 1) {
            throw new IllegalArgumentException("Min block size must be power of 2");
        }

        this.segment = segment;
        this.baseOffset = baseOffset;
        this.capacity = capacity;
        this.minBlockSize = minBlockSize;

        this.maxOrder = Long.numberOfTrailingZeros(capacity);
        this.minOrder = Integer.numberOfTrailingZeros(minBlockSize);
        this.levels = maxOrder - minOrder + 1;

        this.freeLists = new Set[levels];
        for (int i = 0; i < levels; i++) {
            this.freeLists[i] = new LinkedHashSet<>();
        }

        this.allocatedMap = new BitSet((int) (capacity / minBlockSize));

        // Initially, one large block of max order is free
        this.freeLists[levels - 1].add(0L);
    }

    public BuddyAllocator(MemorySegment segment, long baseOffset, long capacity) {
        this(segment, baseOffset, capacity, 65536);
    }

    public long allocate(int size) {
        int requiredOrder = calculateOrder(size);
        int listIdx = requiredOrder - minOrder;

        if (listIdx >= levels) {
            return -1; // Too large
        }

        lock.lock();
        try {
            // Find smallest available block >= requested size
            for (int i = listIdx; i < levels; i++) {
                if (!freeLists[i].isEmpty()) {
                    // Remove block
                    long offset = freeLists[i].iterator().next();
                    freeLists[i].remove(offset);

                    // Split until we reach required order
                    int currentIdx = i;
                    long currentOffset = offset;

                    while (currentIdx > listIdx) {
                        currentIdx--;
                        long buddyOffset = currentOffset + (1L << (currentIdx + minOrder));
                        freeLists[currentIdx].add(buddyOffset);
                    }

                    markAllocated(currentOffset, 1L << requiredOrder);
                    return baseOffset + currentOffset;
                }
            }
        } finally {
            lock.unlock();
        }
        return -1; // Out of memory
    }

    public void free(long absoluteOffset, int size) {
        long relativeOffset = absoluteOffset - baseOffset;
        int order = calculateOrder(size);
        int listIdx = order - minOrder;

        lock.lock();
        try {
            markFree(relativeOffset, 1L << order);

            long currentOffset = relativeOffset;
            int currentIdx = listIdx;

            // Try to coalesce with buddies
            while (currentIdx < levels - 1) {
                long blockSize = 1L << (currentIdx + minOrder);
                long buddyOffset = currentOffset ^ blockSize;

                // Check if buddy is free in the current level's free list
                if (freeLists[currentIdx].remove(buddyOffset)) {
                    // Coalesce
                    currentOffset = currentOffset & ~buddyOffset; // Take lower offset
                    currentIdx++;
                } else {
                    break;
                }
            }

            // Add merged block to free list
            freeLists[currentIdx].add(currentOffset);
        } finally {
            lock.unlock();
        }
    }

    private int calculateOrder(long size) {
        long s = size - 1;
        s |= s >>> 1;
        s |= s >>> 2;
        s |= s >>> 4;
        s |= s >>> 8;
        s |= s >>> 16;
        s |= s >>> 32;
        long powerOf2 = s + 1;
        int order = Long.numberOfTrailingZeros(powerOf2);
        return Math.max(order, minOrder);
    }

    private void markAllocated(long offset, long size) {
        int startBit = (int) (offset / minBlockSize);
        int numBits = (int) (size / minBlockSize);
        allocatedMap.set(startBit, startBit + numBits);
    }

    private void markFree(long offset, long size) {
        int startBit = (int) (offset / minBlockSize);
        int numBits = (int) (size / minBlockSize);
        allocatedMap.clear(startBit, startBit + numBits);
    }
}
