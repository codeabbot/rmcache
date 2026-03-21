package com.codeabbot.rmcache.memory;

import java.lang.foreign.MemorySegment;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Optimized SlabAllocator for 1 Billion Entries.
 * - Uses Bitmaps for slab management (low heap).
 * - Uses Active Slab + Partial Queue (O(1) allocation).
 *
 * @author Rabindra Meher
 */
public class SlabAllocator implements MemoryAllocator, AutoCloseable {

    public static final int[] SIZE_CLASSES = {
            64, 128, 256, 512, 1024, 2048, 4096, 8192, 16384, 32768, 65536
    };
    private static final int LARGE_THRESHOLD = 65536;

    // private final Arena arena = Arena.ofShared(); // Removed Arena
    private final MemorySegment segment;
    private final long totalBytes;

    private final SizeClassState[] classStates;
    private final LockFreeSlabManager[] slabDirectory;
    private final int slabSize;
    private final long slabRegionSize;
    private final long buddySize;
    private final BuddyAllocator buddyAllocator;

    private final AtomicLong nextSlabOffset = new AtomicLong(0);
    private final Map<Long, Integer> largeAllocations = new ConcurrentHashMap<>();
    private final ReentrantLock largeLock = new ReentrantLock();

    private final LongAdder allocationCount = new LongAdder();
    private final LongAdder freeCount = new LongAdder();
    private final LongAdder usedBytesCounter = new LongAdder();

    public MemorySegment getSegment() {
        return segment;
    }

    public SlabAllocator(long memorySize, int slabSize) {
        if (memorySize <= 0) {
            throw new IllegalArgumentException("Memory size must be > 0");
        }
        this.totalBytes = memorySize;
        this.slabSize = slabSize;
        // Use NativeMemory.malloc for raw, uninitialized memory (Lazy commit by OS)
        this.segment = NativeMemory.malloc(memorySize);

        // Reserved Buddy space at end (25% or fixed?)
        long bSizePref = memorySize / 4;
        long p2 = 1L;
        while (p2 * 2 <= bSizePref) {
            p2 *= 2;
        }
        this.buddySize = (memorySize < 1024 * 1024) ? 0 : p2;
        this.slabRegionSize = memorySize - buddySize;

        // AUDIT-H7: Skip BuddyAllocator when buddySize is 0 (memorySize < 1MB).
        // BuddyAllocator requires capacity to be a power-of-2 > 0.
        this.buddyAllocator = (buddySize > 0) ? new BuddyAllocator(segment, slabRegionSize, buddySize) : null;

        int maxSlabs = (int) (slabRegionSize / slabSize) + 1;
        this.slabDirectory = new LockFreeSlabManager[maxSlabs];

        this.classStates = new SizeClassState[SIZE_CLASSES.length];
        for (int i = 0; i < SIZE_CLASSES.length; i++) {
            this.classStates[i] = new SizeClassState();
        }

    }

    public SlabAllocator(long memorySize) {
        this(memorySize, 64 * 1024);
    }

    @Override
    public long getTotalBytes() {
        return totalBytes;
    }

    @Override
    public long getUsedBytes() {
        return usedBytesCounter.sum();
    }

    @Override
    public AllocationHandle allocate(int sizeBytes) {
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("Size must be positive");
        }
        int classIndex = findSizeClass(sizeBytes);

        if (classIndex < 0 || sizeBytes > LARGE_THRESHOLD) {
            return allocateLarge(sizeBytes);
        }

        int blockSize = SIZE_CLASSES[classIndex];

        SizeClassState state = classStates[classIndex];

        // 1. Try active slab
        LockFreeSlabManager active = state.activeSlab.get();
        if (active != null) {
            long offset = active.allocate();
            if (offset >= 0) {
                allocationCount.add(1);
                usedBytesCounter.add(blockSize);
                return new AllocationHandle(segment, offset, blockSize, classIndex);
            }
        }

        // 2. Slow path: Active full or null. CAS loop to promote new active.
        while (true) {
            LockFreeSlabManager currentActive = state.activeSlab.get();

            if (currentActive != null) {
                long offset = currentActive.allocate();
                if (offset >= 0) {
                    allocationCount.add(1);
                    usedBytesCounter.add(blockSize);
                    return new AllocationHandle(segment, offset, blockSize, classIndex);
                }
                state.activeSlab.compareAndSet(currentActive, null);
            }

            LockFreeSlabManager replacement = state.partialSlabs.poll();

            if (replacement == null) {
                // K5: CAS loop — only advance offset on success
                long newOffset;
                while (true) {
                    newOffset = nextSlabOffset.get();
                    if (newOffset + slabSize > slabRegionSize) {
                        // H2 fix: Return null instead of throwing, consistent
                        // with allocatePacked() which returns -1L.
                        return null;
                    }
                    if (nextSlabOffset.compareAndSet(newOffset, newOffset + slabSize))
                        break;
                }

                replacement = new LockFreeSlabManager(segment, newOffset, blockSize, slabSize);
                int slabIdx = (int) (newOffset / slabSize);
                if (slabIdx < slabDirectory.length) {
                    slabDirectory[slabIdx] = replacement;
                } else {
                    throw new IllegalStateException("Slab directory overflow");
                }
            }

            if (state.activeSlab.compareAndSet(null, replacement)) {
                long offset = replacement.allocate();
                if (offset >= 0) {
                    allocationCount.add(1);
                    usedBytesCounter.add(blockSize);
                    return new AllocationHandle(segment, offset, blockSize, classIndex);
                }
            } else {
                state.partialSlabs.offer(replacement);
            }
        }
    }

    /**
     * ISSUE-005 fix: Return null on OOM instead of throwing, consistent with
     * slab path behavior. Callers (allocate, allocatePacked) already handle null/-1L.
     */
    private AllocationHandle allocateLarge(int sizeBytes) {
        if (buddySize == 0) {
            return null;
        }
        largeLock.lock();
        try {
            long offset = buddyAllocator.allocate(sizeBytes);
            if (offset < 0) {
                return null;
            }
            largeAllocations.put(offset, sizeBytes);
            allocationCount.add(1);
            usedBytesCounter.add(sizeBytes);
            return new AllocationHandle(segment, offset, sizeBytes, -1);
        } finally {
            largeLock.unlock();
        }
    }

    @Override
    public void free(AllocationHandle handle) {
        freeCount.add(1);
        if (handle.isLarge()) {
            freeLarge(handle);
            return;
        }
        int classIndex = handle.getSizeClass();
        int blockSize = SIZE_CLASSES[classIndex];

        int slabIdx = (int) (handle.getOffset() / slabSize);
        LockFreeSlabManager slab = slabDirectory[slabIdx];
        if (slab == null) {
            throw new IllegalStateException("Freeing pointer to unknown slab");
        }

        boolean wasFull = slab.isFull();
        if (slab.free(handle.getOffset())) {
            usedBytesCounter.add(-blockSize);
            if (wasFull) {
                classStates[classIndex].partialSlabs.offer(slab);
            }
        }
    }

    private void freeLarge(AllocationHandle handle) {
        largeLock.lock();
        try {
            Integer size = largeAllocations.remove(handle.getOffset());
            if (size != null) {
                usedBytesCounter.add(-size);
                buddyAllocator.free(handle.getOffset(), size);
            }
        } finally {
            largeLock.unlock();
        }
    }

    /**
     * Allocate and return a packed long handle (no object allocation).
     * Returns -1L if allocation fails.
     */
    public long allocatePacked(int sizeBytes) {
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("Size must be positive");
        }
        int classIndex = findSizeClass(sizeBytes);

        if (classIndex < 0 || sizeBytes > LARGE_THRESHOLD) {
            // Fall back to object-based path for large allocations (rare)
            AllocationHandle h = allocateLarge(sizeBytes);
            if (h == null) return -1L;
            return AllocationHandle.pack(h.getOffset(), h.getCapacity(), h.getSizeClass());
        }

        int blockSize = SIZE_CLASSES[classIndex];
        SizeClassState state = classStates[classIndex];

        // 1. Try active slab
        LockFreeSlabManager active = state.activeSlab.get();
        if (active != null) {
            long offset = active.allocate();
            if (offset >= 0) {
                allocationCount.add(1);
                usedBytesCounter.add(blockSize);
                return AllocationHandle.pack(offset, blockSize, classIndex);
            }
        }

        // 2. Slow path
        while (true) {
            LockFreeSlabManager currentActive = state.activeSlab.get();
            if (currentActive != null) {
                long offset = currentActive.allocate();
                if (offset >= 0) {
                    allocationCount.add(1);
                    usedBytesCounter.add(blockSize);
                    return AllocationHandle.pack(offset, blockSize, classIndex);
                }
                state.activeSlab.compareAndSet(currentActive, null);
            }

            LockFreeSlabManager replacement = state.partialSlabs.poll();
            if (replacement == null) {
                // K5: CAS loop — only advance offset on success
                long newOffset;
                while (true) {
                    newOffset = nextSlabOffset.get();
                    if (newOffset + slabSize > slabRegionSize) {
                        return -1L;
                    }
                    if (nextSlabOffset.compareAndSet(newOffset, newOffset + slabSize))
                        break;
                }
                replacement = new LockFreeSlabManager(segment, newOffset, blockSize, slabSize);
                int slabIdx = (int) (newOffset / slabSize);
                if (slabIdx < slabDirectory.length) {
                    slabDirectory[slabIdx] = replacement;
                } else {
                    return -1L;
                }
            }

            if (state.activeSlab.compareAndSet(null, replacement)) {
                long offset = replacement.allocate();
                if (offset >= 0) {
                    allocationCount.add(1);
                    usedBytesCounter.add(blockSize);
                    return AllocationHandle.pack(offset, blockSize, classIndex);
                }
            } else {
                state.partialSlabs.offer(replacement);
            }
        }
    }

    /**
     * Free using a packed long handle (no object allocation).
     */
    public void freePacked(long packedHandle) {
        freeCount.add(1);
        int sc = AllocationHandle.unpackSizeClass(packedHandle);
        long offset = AllocationHandle.unpackOffset(packedHandle);

        if (sc < 0) {
            // Large allocation
            largeLock.lock();
            try {
                Integer size = largeAllocations.remove(offset);
                if (size != null) {
                    usedBytesCounter.add(-size);
                    buddyAllocator.free(offset, size);
                }
            } finally {
                largeLock.unlock();
            }
            return;
        }

        int blockSize = SIZE_CLASSES[sc];
        int slabIdx = (int) (offset / slabSize);
        LockFreeSlabManager slab = slabDirectory[slabIdx];
        if (slab == null) {
            throw new IllegalStateException("Freeing pointer to unknown slab");
        }

        // P2-O3 note: slab.isFull() before slab.free() is racy — two threads can
        // both see wasFull=true, causing duplicate partialSlabs.offer() calls.
        // This is harmless: ConcurrentLinkedQueue handles duplicates gracefully,
        // and the extra dequeue on the allocation side is cheap. Locking around
        // isFull+free would hurt the free-path latency disproportionately.
        boolean wasFull = slab.isFull();
        if (slab.free(offset)) {
            usedBytesCounter.add(-blockSize);
            if (wasFull) {
                classStates[sc].partialSlabs.offer(slab);
            }
        }
    }

    private int findSizeClass(int size) {
        if (size <= 64)
            return 0;
        if (size > LARGE_THRESHOLD)
            return -1;
        // Round up to next power of 2
        int powerOf2 = Integer.highestOneBit(size - 1) << 1;
        // SIZE_CLASSES[0] = 64 = 2^6, so subtract 6 from trailing zeros
        return Integer.numberOfTrailingZeros(powerOf2) - 6;
    }

    @Override
    public AllocatorStats stats() {
        return new AllocatorStats(totalBytes, getUsedBytes(), getFreeBytes(), allocationCount.sum(), freeCount.sum(),
                Collections.emptyList());
    }

    @Override
    public void close() {
        // L3 fix: Free buddy allocator's bitmap segment before main segment.
        if (buddyAllocator != null) {
            buddyAllocator.close();
        }
        if (segment != null) {
            NativeMemory.free(segment);
        }
    }

    private static class SizeClassState {
        final AtomicReference<LockFreeSlabManager> activeSlab = new AtomicReference<>(null);
        final ConcurrentLinkedQueue<LockFreeSlabManager> partialSlabs = new ConcurrentLinkedQueue<>();
    }
}
