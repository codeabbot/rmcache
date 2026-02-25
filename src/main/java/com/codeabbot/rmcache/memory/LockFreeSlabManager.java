package com.codeabbot.rmcache.memory;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Lock-free slab manager using AtomicLongArray bitmap.
 *
 * @author Rabindra Meher
 */
class LockFreeSlabManager {
    private final MemorySegment segment;
    private final long slabOffset;
    private final int blockSize;
    private final int slabSize;
    private final int blocksPerSlab;
    private final int numWords;

    // Atomic bitmap: 64 bits per word, 0 = free, 1 = used
    private final AtomicLongArray bitmap;

    // Atomic count for fast full/empty checks
    private final AtomicInteger usedCountAtomic = new AtomicInteger(0);

    public LockFreeSlabManager(MemorySegment segment, long slabOffset, int blockSize, int slabSize) {
        this.segment = segment;
        this.slabOffset = slabOffset;
        this.blockSize = blockSize;
        this.slabSize = slabSize;
        this.blocksPerSlab = slabSize / blockSize;
        this.numWords = (blocksPerSlab + 63) / 64;
        this.bitmap = new AtomicLongArray(numWords);
    }

    public int getUsedCount() {
        return usedCountAtomic.get();
    }

    public boolean isFull() {
        return getUsedCount() >= blocksPerSlab;
    }

    public boolean isEmpty() {
        return getUsedCount() == 0;
    }

    /**
     * Allocate a block from this slab.
     * 
     * @return Offset of allocated block, or -1 if slab is full.
     */
    public long allocate() {
        // Fast-path check
        if (usedCountAtomic.get() >= blocksPerSlab) {
            return -1L;
        }

        // Thread-local scan start to avoid cache-line contention on a shared hint
        int hint = (int) (Thread.currentThread().threadId() % numWords);
        if (hint < 0)
            hint += numWords;
        for (int i = 0; i < numWords; i++) {
            int wordIdx = (hint + i) % numWords;
            int attempts = 0;
            while (attempts < 64) { // Limit retries per word
                long word = bitmap.get(wordIdx);

                // Check if word is full (all 1s)
                if (word == -1L) {
                    break;
                }

                // Find first clear bit
                int bit = Long.numberOfTrailingZeros(~word);
                if (bit >= 64) {
                    break; // Should not happen but safety check
                }

                // Calculate actual block index
                int blockIndex = wordIdx * 64 + bit;
                if (blockIndex >= blocksPerSlab) {
                    return -1L;
                }

                // Try to claim this bit with CAS
                long newWord = word | (1L << bit);
                if (bitmap.compareAndSet(wordIdx, word, newWord)) {
                    usedCountAtomic.incrementAndGet();
                    return slabOffset + ((long) blockIndex * blockSize);
                }

                // CAS failed - another thread took it, retry
                attempts++;
            }
        }

        return -1L; // No free block found
    }

    /**
     * Free a block in this slab.
     * 
     * @return true if freed successfully, false if already free (double-free)
     */
    public boolean free(long offset) {
        int blockIndex = (int) ((offset - slabOffset) / blockSize);
        if (blockIndex < 0 || blockIndex >= blocksPerSlab) {
            return false;
        }

        int wordIdx = blockIndex / 64;
        int bitPos = blockIndex % 64;
        long bitMask = 1L << bitPos;

        while (true) {
            long word = bitmap.get(wordIdx);

            // Check if bit is already clear (double-free)
            if ((word & bitMask) == 0L) {
                return false;
            }

            // Try to clear the bit with CAS
            long newWord = word & ~bitMask;
            if (bitmap.compareAndSet(wordIdx, word, newWord)) {
                usedCountAtomic.decrementAndGet();
                return true;
            }
            // CAS failed - retry
        }
    }

    public int getBlockSize() {
        return blockSize;
    }

    public long getSlabOffset() {
        return slabOffset;
    }
}
