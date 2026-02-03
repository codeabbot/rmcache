package com.codeabbot.rmcache.memory;

import java.lang.foreign.MemorySegment;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
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

    private final AtomicLong allocationCount = new AtomicLong(0);
    private final AtomicLong freeCount = new AtomicLong(0);
    private final AtomicLong usedBytesCounter = new AtomicLong(0);


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

        this.buddyAllocator = new BuddyAllocator(segment, slabRegionSize, buddySize);

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
        return usedBytesCounter.get();
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
                allocationCount.incrementAndGet();
                usedBytesCounter.addAndGet(blockSize);
                return new AllocationHandle(segment, offset, blockSize, classIndex);
            }
        }

        // 2. Slow path: Active full or null. CAS loop to promote new active.
        while (true) {
            LockFreeSlabManager currentActive = state.activeSlab.get();

            if (currentActive != null) {
                long offset = currentActive.allocate();
                if (offset >= 0) {
                    allocationCount.incrementAndGet();
                    usedBytesCounter.addAndGet(blockSize);
                    return new AllocationHandle(segment, offset, blockSize, classIndex);
                }
                state.activeSlab.compareAndSet(currentActive, null);
            }

            LockFreeSlabManager replacement = state.partialSlabs.poll();

            if (replacement == null) {
                long newOffset = nextSlabOffset.getAndAdd(slabSize);
                if (newOffset + slabSize > slabRegionSize) {
                    throw new IllegalStateException("Out of memory: Slab Region Full");
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
                    allocationCount.incrementAndGet();
                    usedBytesCounter.addAndGet(blockSize);
                    return new AllocationHandle(segment, offset, blockSize, classIndex);
                }
            } else {
                state.partialSlabs.offer(replacement);
            }
        }
    }

    private AllocationHandle allocateLarge(int sizeBytes) {
        if (buddySize == 0) {
            throw new IllegalStateException("Large allocation not supported for small heaps");
        }
        largeLock.lock();
        try {
            long offset = buddyAllocator.allocate(sizeBytes);
            if (offset < 0) {
                throw new IllegalStateException("OOM Large");
            }
            largeAllocations.put(offset, sizeBytes);
            allocationCount.incrementAndGet();
            usedBytesCounter.addAndGet(sizeBytes);
            return new AllocationHandle(segment, offset, sizeBytes, -1);
        } finally {
            largeLock.unlock();
        }
    }

    @Override
    public void free(AllocationHandle handle) {
        freeCount.incrementAndGet();
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
            usedBytesCounter.addAndGet(-blockSize);
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
                usedBytesCounter.addAndGet(-size);
                buddyAllocator.free(handle.getOffset(), size);
            }
        } finally {
            largeLock.unlock();
        }
    }

    private int findSizeClass(int size) {
        for (int i = 0; i < SIZE_CLASSES.length; i++) {
            if (size <= SIZE_CLASSES[i]) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public AllocatorStats stats() {
        return new AllocatorStats(totalBytes, getUsedBytes(), getFreeBytes(), allocationCount.get(), freeCount.get(),
                Collections.emptyList());
    }

    @Override
    public void close() {
        if (segment != null) {
            NativeMemory.free(segment);
        }
    }

    private static class SizeClassState {
        final AtomicReference<LockFreeSlabManager> activeSlab = new AtomicReference<>(null);
        final ConcurrentLinkedQueue<LockFreeSlabManager> partialSlabs = new ConcurrentLinkedQueue<>();
    }
}
