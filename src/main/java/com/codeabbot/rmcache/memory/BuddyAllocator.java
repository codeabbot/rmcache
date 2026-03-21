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

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Buddy allocator for large allocations (&gt; 64KB).
 * Uses off-heap free lists and bitmap to avoid GC pressure at scale.
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

    /**
     * Off-heap free list heads. Each entry is the relative offset of the first free
     * block at that order, or -1L if empty. Free blocks store a 'next' pointer
     * (relative offset or -1L) in their first 8 bytes within the data segment.
     */
    private final long[] freeListHeads;

    /**
     * Off-heap bitmap for allocated status. Each bit represents one minBlockSize
     * chunk. Stored in a dedicated off-heap segment to avoid GC pressure.
     */
    private final MemorySegment bitmapSegment;
    private final int bitmapLongs; // number of longs in bitmap

    private final ReentrantLock lock = new ReentrantLock();

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

        // Off-heap free list heads (kept on heap — tiny array, levels is ~10-20)
        this.freeListHeads = new long[levels];
        for (int i = 0; i < levels; i++) {
            this.freeListHeads[i] = -1L;
        }

        // Off-heap bitmap: one bit per minBlockSize chunk
        int totalChunks = (int) (capacity / minBlockSize);
        this.bitmapLongs = (totalChunks + 63) >>> 6; // ceil(totalChunks / 64)
        this.bitmapSegment = NativeMemory.calloc(bitmapLongs, 8); // zeroed = all free

        // Initially, one large block of max order is free
        this.freeListHeads[levels - 1] = 0L; // offset 0 relative to baseOffset
        // Write -1L as the 'next' pointer for this single free block
        segment.set(ValueLayout.JAVA_LONG, baseOffset, -1L);
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
                if (freeListHeads[i] != -1L) {
                    // Pop first block from free list
                    long offset = freeListHeads[i];
                    long nextFree = segment.get(ValueLayout.JAVA_LONG, baseOffset + offset);
                    freeListHeads[i] = nextFree;

                    // Split until we reach required order
                    int currentIdx = i;
                    long currentOffset = offset;

                    while (currentIdx > listIdx) {
                        currentIdx--;
                        long buddyOffset = currentOffset + (1L << (currentIdx + minOrder));
                        // Push buddy onto free list at currentIdx
                        long oldHead = freeListHeads[currentIdx];
                        segment.set(ValueLayout.JAVA_LONG, baseOffset + buddyOffset, oldHead);
                        freeListHeads[currentIdx] = buddyOffset;
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

                // Check if buddy is free (not allocated)
                if (!isBuddyAllocated(buddyOffset, blockSize)) {
                    // Remove buddy from its free list
                    removeFreeBlock(currentIdx, buddyOffset);
                    // Coalesce — take the lower offset
                    currentOffset = Math.min(currentOffset, buddyOffset);
                    currentIdx++;
                } else {
                    break;
                }
            }

            // Push merged block onto free list
            long oldHead = freeListHeads[currentIdx];
            segment.set(ValueLayout.JAVA_LONG, baseOffset + currentOffset, oldHead);
            freeListHeads[currentIdx] = currentOffset;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Remove a specific block from the free list at the given level.
     * This is an O(n) scan but buddy coalescing is rare relative to alloc/free.
     */
    private void removeFreeBlock(int level, long targetOffset) {
        long prev = -1L;
        long current = freeListHeads[level];

        while (current != -1L) {
            long next = segment.get(ValueLayout.JAVA_LONG, baseOffset + current);
            if (current == targetOffset) {
                if (prev == -1L) {
                    freeListHeads[level] = next;
                } else {
                    segment.set(ValueLayout.JAVA_LONG, baseOffset + prev, next);
                }
                return;
            }
            prev = current;
            current = next;
        }
    }

    /**
     * Check if all chunks in the buddy block are allocated.
     */
    private boolean isBuddyAllocated(long offset, long blockSize) {
        int startBit = (int) (offset / minBlockSize);
        int numBits = (int) (blockSize / minBlockSize);
        for (int i = startBit; i < startBit + numBits; i++) {
            if (isBitSet(i)) {
                return true;
            }
        }
        return false;
    }

    // P4-O1 fix: Parameter changed from int to long for consistency
    // with internal long arithmetic and future-proofing.
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
        for (int i = startBit; i < startBit + numBits; i++) {
            setBit(i);
        }
    }

    private void markFree(long offset, long size) {
        int startBit = (int) (offset / minBlockSize);
        int numBits = (int) (size / minBlockSize);
        for (int i = startBit; i < startBit + numBits; i++) {
            clearBit(i);
        }
    }

    // --- Off-heap bitmap operations ---

    private void setBit(int bitIndex) {
        int longIdx = bitIndex >>> 6;
        long mask = 1L << (bitIndex & 63);
        long addr = (long) longIdx * 8L;
        long val = bitmapSegment.get(ValueLayout.JAVA_LONG, addr);
        bitmapSegment.set(ValueLayout.JAVA_LONG, addr, val | mask);
    }

    private void clearBit(int bitIndex) {
        int longIdx = bitIndex >>> 6;
        long mask = 1L << (bitIndex & 63);
        long addr = (long) longIdx * 8L;
        long val = bitmapSegment.get(ValueLayout.JAVA_LONG, addr);
        bitmapSegment.set(ValueLayout.JAVA_LONG, addr, val & ~mask);
    }

    private boolean isBitSet(int bitIndex) {
        int longIdx = bitIndex >>> 6;
        long mask = 1L << (bitIndex & 63);
        long addr = (long) longIdx * 8L;
        long val = bitmapSegment.get(ValueLayout.JAVA_LONG, addr);
        return (val & mask) != 0;
    }

    // L3 fix: Free the off-heap bitmap segment to prevent memory leak.
    public void close() {
        if (bitmapSegment != null) {
            NativeMemory.free(bitmapSegment);
        }
    }
}
