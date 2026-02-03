package com.codeabbot.rmcache.memory;

import java.util.List;

/**
 * Memory allocator interface for off-heap allocation.
 *
 * @author Rabindra Meher
 */
public interface MemoryAllocator extends AutoCloseable {
    /** Total memory under management in bytes */
    long getTotalBytes();

    /** Currently used memory in bytes */
    long getUsedBytes();

    /** Free memory in bytes */
    default long getFreeBytes() {
        return getTotalBytes() - getUsedBytes();
    }

    /**
     * Allocate a block of at least sizeBytes bytes.
     * 
     * @return AllocationHandle for the allocated block
     * @throws IllegalStateException if allocation fails
     */
    AllocationHandle allocate(int sizeBytes);

    /**
     * Free a previously allocated block.
     */
    void free(AllocationHandle handle);

    /**
     * Get allocator statistics.
     */
    AllocatorStats stats();

    @Override
    void close();

    /**
     * Statistics about allocator state.
     */
    record AllocatorStats(
            long totalBytes,
            long usedBytes,
            long freeBytes,
            long allocations,
            long frees,
            List<SlabStats> slabStats) {
    }

    /**
     * Statistics for a single slab size class.
     */
    record SlabStats(
            int sizeClass,
            int blockSize,
            int totalBlocks,
            int usedBlocks,
            int freeBlocks) {
    }
}
