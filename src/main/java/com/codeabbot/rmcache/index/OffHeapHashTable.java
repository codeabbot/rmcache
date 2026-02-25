package com.codeabbot.rmcache.index;

import com.codeabbot.rmcache.memory.NativeMemory;
import com.codeabbot.rmcache.util.Prefetch;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.locks.StampedLock;

/**
 * High-performance hash table using Robin Hood hashing with packed slots.
 * Ported from Kotlin implementation relative to V11 architecture (Segmented +
 * Raw Memory).
 *
 * @author Rabindra Meher
 */
public class OffHeapHashTable implements AutoCloseable {

    private final EntryPool entryPool;
    private final int numStripes;
    private final boolean enablePrefetch;
    private final double loadFactor;
    private final int initialCapacity;

    private final int stripeShift;
    private final int stripeMask;

    private final long[] tableAddrs;
    private final int[] tableMasks;
    private final int[] tableCapacities;
    // C2 fix: AtomicIntegerArray ensures visibility of count updates on ARM
    private final AtomicIntegerArray tableCounts;
    private final StampedLock[] tableLocks;
    private final MemorySegment[] tableSegments;

    // Async cleanup queue for retired tables — entries are timestamped for
    // grace-period reclamation
    private final ConcurrentLinkedQueue<Object[]> retiredSegments = new ConcurrentLinkedQueue<>();

    private static final int SLOT_SIZE = 8; // 8 bytes packed: Top 32 bits Hash, Bottom 32 bits SlotID
    private static final int MAX_SAME_HASH_PROBE = 32;

    public OffHeapHashTable(EntryPool entryPool, long memoryBytes, int numStripes, boolean enablePrefetch) {
        this(entryPool, memoryBytes, numStripes, enablePrefetch, 256, 0.75d);
    }

    public OffHeapHashTable(EntryPool entryPool, long memoryBytes, int numStripes, boolean enablePrefetch,
            int initialCapacity, double loadFactor) {
        this.entryPool = entryPool;
        // Adjust numStripes to power of 2
        int powerOf2 = 1;
        int shift = 0;
        int targetStripes = (numStripes < 1) ? 16384 : numStripes;

        while (powerOf2 < targetStripes) {
            powerOf2 <<= 1;
            shift++;
        }
        this.numStripes = powerOf2;
        this.stripeMask = powerOf2 - 1;
        this.stripeShift = 32 - shift;
        this.enablePrefetch = enablePrefetch;
        this.initialCapacity = Math.max(2, nextPowerOfTwo(initialCapacity));
        this.loadFactor = Math.max(0.25d, Math.min(loadFactor, 0.95d));

        this.tableAddrs = new long[powerOf2];
        this.tableMasks = new int[powerOf2];
        this.tableCapacities = new int[powerOf2];
        this.tableCounts = new AtomicIntegerArray(powerOf2);
        this.tableLocks = new StampedLock[powerOf2];
        this.tableSegments = new MemorySegment[powerOf2];

        for (int i = 0; i < powerOf2; i++) {
            this.tableLocks[i] = new StampedLock();
            int cap = this.initialCapacity; // Configurable initial capacity
            MemorySegment seg = NativeMemory.calloc(cap, SLOT_SIZE);
            this.tableSegments[i] = seg;
            this.tableAddrs[i] = seg.address();
            this.tableMasks[i] = cap - 1;
            this.tableCapacities[i] = cap;
        }
    }

    @Override
    public void close() {
        // Free active tables
        for (MemorySegment seg : tableSegments) {
            if (seg != null) {
                NativeMemory.free(seg);
            }
        }
        // Force-drain all retired segments on close (no grace period needed)
        Object[] entry;
        while ((entry = retiredSegments.poll()) != null) {
            NativeMemory.free((MemorySegment) entry[1]);
        }
    }

    // Fast-path get
    public int get(int keyHash, byte[] keyBytes) {
        return getWithLen(keyHash, keyBytes, keyBytes.length);
    }

    public int getWithLen(int keyHash, byte[] keyBytes, int keyLen) {
        int sIdx = (keyHash >>> stripeShift) & stripeMask;
        StampedLock lock = tableLocks[sIdx];

        long stamp = lock.tryOptimisticRead();
        // Capture state for optimistic read
        long tableAddr = tableAddrs[sIdx];
        int mask = tableMasks[sIdx];
        int cap = tableCapacities[sIdx];

        int slot = probeWithParams(tableAddr, mask, cap, keyHash, keyBytes, keyLen);

        if (!lock.validate(stamp)) {
            stamp = lock.readLock();
            try {
                // Read fresh state under lock
                return probeWithParams(tableAddrs[sIdx], tableMasks[sIdx], tableCapacities[sIdx], keyHash, keyBytes,
                        keyLen);
            } finally {
                lock.unlockRead(stamp);
            }
        }

        return slot;
    }

    public int getWithKey(int keyHash, Object key, com.codeabbot.rmcache.serializer.KeySerializer serializer) {
        int sIdx = (keyHash >>> stripeShift) & stripeMask;
        StampedLock lock = tableLocks[sIdx];

        long stamp = lock.tryOptimisticRead();
        long tableAddr = tableAddrs[sIdx];
        int mask = tableMasks[sIdx];
        int cap = tableCapacities[sIdx];

        int slot = probeWithKey(tableAddr, mask, cap, keyHash, key, serializer);

        if (!lock.validate(stamp)) {
            stamp = lock.readLock();
            try {
                return probeWithKey(tableAddrs[sIdx], tableMasks[sIdx], tableCapacities[sIdx], keyHash, key,
                        serializer);
            } finally {
                lock.unlockRead(stamp);
            }
        }

        return slot;
    }

    private int probeWithParams(long tableAddr, int mask, int cap, int keyHash, byte[] keyBytes, int keyLen) {
        int index = keyHash & mask;
        int dist = 0;
        int sameHashProbes = 0;

        if (enablePrefetch) {
            Prefetch.prefetchNextSlots(tableAddr, index, mask);
        }

        while (true) {
            long entryAddr = tableAddr + ((long) index << 3); // SLOT_SIZE = 8 (2^3)
            long entry = NativeMemory.UNLIMITED.get(ValueLayout.JAVA_LONG, entryAddr);

            if (entry == 0L)
                return 0;

            if (enablePrefetch) {
                Prefetch.prefetchNextSlots(tableAddr, index, mask, 1);
            }

            // Unpack: Hash is upper 32 bits
            int storedHash = (int) (entry >>> 32);

            if (storedHash == keyHash) {
                int slot = (int) entry; // Slot is lower 32 bits
                long offset = entryPool.getOffset(slot);
                if (offset != -1L) {
                    // Use keyEqualsWithLen since we don't store length in the table anymore
                    if (entryPool.keyEqualsWithLen(offset, keyBytes, keyLen)) {
                        return slot;
                    }
                }
                if (++sameHashProbes >= MAX_SAME_HASH_PROBE) {
                    return 0;
                }
            }

            int ideal = storedHash & mask;
            int probeDist = (index - ideal + cap) & mask;
            if (probeDist < dist)
                return 0;

            index = (index + 1) & mask;
            dist++;
        }
    }

    private int probeWithKey(long tableAddr, int mask, int cap, int keyHash, Object key,
            com.codeabbot.rmcache.serializer.KeySerializer serializer) {
        int index = keyHash & mask;
        int dist = 0;
        int sameHashProbes = 0;

        if (enablePrefetch) {
            Prefetch.prefetchNextSlots(tableAddr, index, mask);
        }

        while (true) {
            long entryAddr = tableAddr + ((long) index << 3);
            long entry = NativeMemory.UNLIMITED.get(ValueLayout.JAVA_LONG, entryAddr);

            if (entry == 0L)
                return 0;

            if (enablePrefetch) {
                Prefetch.prefetchNextSlots(tableAddr, index, mask, 1);
            }

            int storedHash = (int) (entry >>> 32);

            if (storedHash == keyHash) {
                int slot = (int) entry;
                long offset = entryPool.getOffset(slot);
                if (offset != -1L) {
                    if (entryPool.matchesAt(offset, key, serializer)) {
                        return slot;
                    }
                }
                if (++sameHashProbes >= MAX_SAME_HASH_PROBE) {
                    return 0;
                }
            }

            int ideal = storedHash & mask;
            int probeDist = (index - ideal + cap) & mask;
            if (probeDist < dist)
                return 0;

            index = (index + 1) & mask;
            dist++;
        }
    }

    public int putEntry(int keyHash, byte[] keyBytes, int keyLen, int slot, boolean putIfAbsent) {
        int sIdx = (keyHash >>> stripeShift) & stripeMask;
        StampedLock lock = tableLocks[sIdx];

        if (putIfAbsent) {
            // Optimistic path: check if key exists first (for updates)
            long optimisticStamp = lock.tryOptimisticRead();
            if (optimisticStamp != 0L) {
                long tableAddr = tableAddrs[sIdx];
                int mask = tableMasks[sIdx];
                int cap = tableCapacities[sIdx];

                int existingSlot = probeWithParams(tableAddr, mask, cap, keyHash, keyBytes, keyLen);

                // If key exists and optimistic read validates, this is an update
                if (existingSlot > 0 && lock.validate(optimisticStamp)) {
                    // Key exists - this is an update, return the existing slot
                    return existingSlot;
                }
            }
        }

        // Standard write path for new inserts or failed optimistic reads
        long stamp = lock.writeLock();
        try {
            if (tableCounts.get(sIdx) >= (int) (tableCapacities[sIdx] * loadFactor)) {
                resize(sIdx);
            }

            long tableAddr = tableAddrs[sIdx];
            int mask = tableMasks[sIdx];
            int cap = tableCapacities[sIdx];

            int index = keyHash & mask;
            int dist = 0;
            int sameHashProbes = 0;

            // Pack: Hash (High) | Slot (Low)
            long currEntry = ((long) keyHash << 32) | (slot & 0xFFFFFFFFL);

            while (true) {
                long entryAddr = tableAddr + ((long) index << 3);
                long existingEntry = NativeMemory.UNLIMITED.get(ValueLayout.JAVA_LONG, entryAddr);

                if (existingEntry == 0L) {
                    NativeMemory.UNLIMITED.set(ValueLayout.JAVA_LONG, entryAddr, currEntry);
                    tableCounts.incrementAndGet(sIdx);
                    return 0;
                }

                int existingHash = (int) (existingEntry >>> 32);

                if (existingHash == keyHash) {
                    int existingSlot = (int) existingEntry;
                    long offset = entryPool.getOffset(existingSlot);
                    if (entryPool.keyEqualsWithLen(offset, keyBytes, keyLen)) {
                        if (putIfAbsent) {
                            return existingSlot; // Duplicate found, return old slot
                        } else {
                            NativeMemory.UNLIMITED.set(ValueLayout.JAVA_LONG, entryAddr, currEntry);
                            return existingSlot; // Overwritten, return old slot so caller can free it
                        }
                    }
                    if (++sameHashProbes >= MAX_SAME_HASH_PROBE) {
                        return -1;
                    }
                }

                int ideal = existingHash & mask;
                int existingDist = (index - ideal + cap) & mask;

                if (existingDist < dist) {
                    // Robin Hood swap
                    long tempEntry = existingEntry;
                    NativeMemory.UNLIMITED.set(ValueLayout.JAVA_LONG, entryAddr, currEntry);
                    currEntry = tempEntry;
                    dist = existingDist;
                }

                index = (index + 1) & mask;
                dist++;
            }
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    public int remove(int keyHash, byte[] keyBytes) {
        int sIdx = (keyHash >>> stripeShift) & stripeMask;
        StampedLock lock = tableLocks[sIdx];
        long stamp = lock.writeLock();
        try {
            long tableAddr = tableAddrs[sIdx];
            int mask = tableMasks[sIdx];
            int cap = tableCapacities[sIdx];

            int index = keyHash & mask;
            int dist = 0;
            int sameHashProbes = 0;

            while (true) {
                long entryAddr = tableAddr + ((long) index << 3);
                long existingEntry = NativeMemory.UNLIMITED.get(ValueLayout.JAVA_LONG, entryAddr);

                if (existingEntry == 0L)
                    return 0;

                int existingHash = (int) (existingEntry >>> 32);

                if (existingHash == keyHash) {
                    int slot = (int) existingEntry;
                    long offset = entryPool.getOffset(slot);
                    if (entryPool.keyEqualsWithLen(offset, keyBytes, keyBytes.length)) {
                        backwardShift(tableAddr, index, mask, cap);
                        tableCounts.decrementAndGet(sIdx);
                        return slot;
                    }
                    if (++sameHashProbes >= MAX_SAME_HASH_PROBE) {
                        return 0;
                    }
                }

                int ideal = existingHash & mask;
                int probeDist = (index - ideal + cap) & mask;
                if (probeDist < dist)
                    return 0;

                index = (index + 1) & mask;
                dist++;
            }
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    private void backwardShift(long tableAddr, int startIdx, int mask, int cap) {
        int idx = startIdx;
        while (true) {
            int nextIdx = (idx + 1) & mask;
            long nextAddr = tableAddr + ((long) nextIdx << 3);
            long nextEntry = NativeMemory.UNLIMITED.get(ValueLayout.JAVA_LONG, nextAddr);

            if (nextEntry == 0L) {
                long currAddr = tableAddr + ((long) idx << 3);
                NativeMemory.UNLIMITED.set(ValueLayout.JAVA_LONG, currAddr, 0L);
                return;
            }

            int nextHash = (int) (nextEntry >>> 32);
            int ideal = nextHash & mask;

            if (ideal == nextIdx) {
                long currAddr = tableAddr + ((long) idx << 3);
                NativeMemory.UNLIMITED.set(ValueLayout.JAVA_LONG, currAddr, 0L);
                return;
            }

            long currAddr = tableAddr + ((long) idx << 3);
            NativeMemory.UNLIMITED.set(ValueLayout.JAVA_LONG, currAddr, nextEntry);

            idx = nextIdx;
        }
    }

    private void resize(int sIdx) {
        int oldCap = tableCapacities[sIdx];
        int newCap = oldCap * 2;
        int newMask = newCap - 1;
        MemorySegment newTable = NativeMemory.calloc(newCap, SLOT_SIZE);
        long oldAddr = tableAddrs[sIdx];
        long newAddr = newTable.address();

        for (int i = 0; i < oldCap; i++) {
            long oldEntryAddr = oldAddr + ((long) i << 3);
            long oldEntry = NativeMemory.UNLIMITED.get(ValueLayout.JAVA_LONG, oldEntryAddr);

            if (oldEntry != 0L) {
                int hash = (int) (oldEntry >>> 32);
                int idx = hash & newMask;
                int dist = 0;
                long currEntry = oldEntry;

                while (true) {
                    long newEntryAddr = newAddr + ((long) idx << 3);
                    long existingEntry = NativeMemory.UNLIMITED.get(ValueLayout.JAVA_LONG, newEntryAddr);

                    if (existingEntry == 0L) {
                        NativeMemory.UNLIMITED.set(ValueLayout.JAVA_LONG, newEntryAddr, currEntry);
                        break;
                    }

                    int existingHash = (int) (existingEntry >>> 32);
                    int ideal = existingHash & newMask;
                    int existingDist = (idx - ideal + newCap) & newMask;

                    if (existingDist < dist) {
                        long temp = existingEntry;
                        NativeMemory.UNLIMITED.set(ValueLayout.JAVA_LONG, newEntryAddr, currEntry);
                        currEntry = temp;
                        dist = existingDist;
                    }

                    idx = (idx + 1) & newMask;
                    dist++;
                }
            }
        }

        retiredSegments.offer(new Object[] { System.nanoTime(), tableSegments[sIdx] });
        tableSegments[sIdx] = newTable;
        tableAddrs[sIdx] = newAddr;
        tableCapacities[sIdx] = newCap;
        tableMasks[sIdx] = newMask;

        // M3 fix: Inline-drain retired segments past the grace period to prevent
        // unbounded queue growth during burst resizes.
        long now = System.nanoTime();
        long graceNanos = 500_000_000L;
        Object[] retired;
        while ((retired = retiredSegments.peek()) != null) {
            if (now - (long) retired[0] < graceNanos)
                break;
            retiredSegments.poll();
            NativeMemory.free((MemorySegment) retired[1]);
        }
    }

    public long size() {
        long sum = 0;
        for (int i = 0; i < tableCounts.length(); i++)
            sum += tableCounts.get(i);
        return sum;
    }

    public void drainCleanupQueue() {
        // Grace period: delay freeing retired tables to avoid use-after-free
        // by optimistic readers that captured a stale tableAddr before resize.
        long now = System.nanoTime();
        long graceNanos = 500_000_000L; // 500ms
        Object[] entry;
        while ((entry = retiredSegments.peek()) != null) {
            long retiredAt = (long) entry[0];
            if (now - retiredAt < graceNanos) {
                break; // Not yet safe to free
            }
            retiredSegments.poll();
            NativeMemory.free((MemorySegment) entry[1]);
        }
    }

    public void clear() {
        for (int i = 0; i < tableSegments.length; i++) {
            StampedLock lock = tableLocks[i];
            long stamp = lock.writeLock();
            try {
                retiredSegments.offer(new Object[] { System.nanoTime(), tableSegments[i] });
                int cap = tableCapacities[i];
                MemorySegment seg = NativeMemory.calloc(cap, SLOT_SIZE);
                tableSegments[i] = seg;
                tableAddrs[i] = seg.address();
                tableCounts.set(i, 0);
            } finally {
                lock.unlockWrite(stamp);
            }
        }
    }

    private static int nextPowerOfTwo(int value) {
        int v = value - 1;
        v |= v >>> 1;
        v |= v >>> 2;
        v |= v >>> 4;
        v |= v >>> 8;
        v |= v >>> 16;
        return v + 1;
    }
}
