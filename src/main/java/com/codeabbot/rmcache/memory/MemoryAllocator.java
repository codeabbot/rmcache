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
