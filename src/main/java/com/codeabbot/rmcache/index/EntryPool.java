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
package com.codeabbot.rmcache.index;

import com.codeabbot.rmcache.memory.AllocationHandle;
import com.codeabbot.rmcache.memory.NativeMemory;
import com.codeabbot.rmcache.memory.SlabAllocator;
import com.codeabbot.rmcache.serializer.KeySerializer;
import com.codeabbot.rmcache.util.CoarseClock;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Manages cache entries mapping Slot IDs to Off-Heap Memory Offsets.
 *
 * @author Rabindra Meher
 */
public class EntryPool implements AutoCloseable {

    private final SlabAllocator allocator;
    private final SubPool[] partitions;
    private final int partitionMask;
    private final int partitionShift;
    private final int numPartitionsMask;
    private final int slotCapacity;

    private final MemorySegment baseSegment;
    private final long baseAddr;

    // Global offsets array (Stores ABSOLUTE addresses)
    private final MemorySegment offsets;

    private static final VarHandle LONG_HANDLE = ValueLayout.JAVA_LONG.varHandle();
    private static final ValueLayout.OfLong UNALIGNED_LONG = ValueLayout.JAVA_LONG.withByteAlignment(1);
    private static final long FINGERPRINT_OFFSET = 17L;

    // O8: Pack size class into upper 16 bits of offset table entries.
    // On x86-64/AArch64, virtual addresses use at most 48 bits.
    private static final long ADDR_MASK = 0x0000_FFFF_FFFF_FFFFL;

    static long packOffset(long absOffset, int sizeClass) {
        return (absOffset & ADDR_MASK) | ((long) (sizeClass & 0xFF) << 48);
    }

    static long unpackAddr(long packed) {
        return packed & ADDR_MASK;
    }

    static int unpackSC(long packed) {
        // AUDIT-C2: The pack side stores (sizeClass & 0xFF) in bits 48-55.
        // For large allocations (buddy), sizeClass is -1, stored as 0xFF.
        // We must map 0xFF back to -1 to avoid SIZE_CLASSES[255] AIOOBE.
        int sc = (int) ((packed >>> 48) & 0xFF);
        return (sc == 0xFF) ? -1 : sc;
    }

    public EntryPool(SlabAllocator allocator, int maxEntries, int numPartitions) {
        if (numPartitions <= 0 || (numPartitions & (numPartitions - 1)) != 0) {
            throw new IllegalArgumentException("numPartitions must be power of 2, got " + numPartitions);
        }
        this.allocator = allocator;
        this.numPartitionsMask = numPartitions - 1;

        int shift = 0;
        int pSize = 1;
        while (pSize * numPartitions < maxEntries || pSize < 1024) {
            pSize <<= 1;
            shift++;
        }
        this.partitionShift = shift;
        long totalSlots = (long) numPartitions * (1L << shift);
        // E2E-C1: Fail-fast guard — single-server mode caps at Integer.MAX_VALUE slots.
        // At 2B entries × 256B avg = ~600GB RAM, which exceeds single-server capacity.
        // Clustering phase will lift this limit with sharded slot spaces.
        if (totalSlots > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "maxEntries too large for single-server mode: " + totalSlots
                            + " slots > Integer.MAX_VALUE. Reduce maxEntries or partitions.");
        }
        this.slotCapacity = (int) totalSlots;

        this.offsets = NativeMemory.malloc((totalSlots + 1) * 8L);
        this.offsets.fill((byte) 0xFF); // -1L

        this.partitionMask = (1 << shift) - 1;
        this.baseSegment = allocator.getSegment();
        this.baseAddr = baseSegment.address();

        this.partitions = new SubPool[numPartitions];
        for (int i = 0; i < numPartitions; i++) {
            this.partitions[i] = new SubPool(i, 1 << shift);
        }
    }

    public EntryPool(SlabAllocator allocator, int maxEntries) {
        this(allocator, maxEntries, 128);
    }

    @Override
    public void close() {
        NativeMemory.free(offsets);
        for (SubPool p : partitions) {
            p.close();
        }
    }

    public int allocate(int keyHash, byte[] keyBytes, byte[] valueBytes, short priority, long expiresAtMillis) {
        return allocateWithLen(keyHash, keyBytes, keyBytes.length, valueBytes, valueBytes.length, priority,
                expiresAtMillis);
    }

    public int allocateWithLen(int keyHash, byte[] keyBytes, int keyLen, byte[] valueBytes, int valueLen,
            short priority, long expiresAtMillis) {
        int pIdx = ((keyHash ^ (keyHash >>> 16)) & numPartitionsMask);
        return partitions[pIdx].allocate(keyHash, keyBytes, keyLen, valueBytes, valueLen, priority, expiresAtMillis);
    }

    public int allocateWithWriter(int keyHash, byte[] keyBytes, int keyLen, int valueMaxLen, ValueWriter writer,
            short priority, long expiresAtMillis) {
        int pIdx = ((keyHash ^ (keyHash >>> 16)) & numPartitionsMask);
        return partitions[pIdx].allocateWithWriter(keyHash, keyBytes, keyLen, valueMaxLen, writer, priority,
                expiresAtMillis);
    }

    public <V> int allocateWithSerializer(int keyHash, byte[] keyBytes, int keyLen, int valueMaxLen,
            com.codeabbot.rmcache.serializer.SegmentValueSerializer<V> serializer, V value, short priority,
            long expiresAtMillis) {
        int pIdx = ((keyHash ^ (keyHash >>> 16)) & numPartitionsMask);
        return partitions[pIdx].allocateWithSerializer(keyHash, keyBytes, keyLen, valueMaxLen, serializer, value,
                priority, expiresAtMillis);
    }

    public void free(int slot) {
        if (slot <= 0)
            return;
        int pIdx = slot >>> partitionShift;
        partitions[pIdx].free(slot);
    }

    public long getOffset(int slot) {
        long packed = (long) LONG_HANDLE.getVolatile(offsets, (long) slot * 8L);
        return (packed == -1L) ? -1L : unpackAddr(packed);
    }

    /**
     * O1 fix: Opaque read of offset — avoids volatile barrier on ARM hot path.
     * Safe for read-only callers (get) since the write side uses setVolatile.
     * Provides at-least-opaque ordering which guarantees no word tearing.
     */
    public long getOffsetOpaque(int slot) {
        long packed = (long) LONG_HANDLE.getOpaque(offsets, (long) slot * 8L);
        return (packed == -1L) ? -1L : unpackAddr(packed);
    }

    public int getSlotFromOffset(long offset) {
        return NativeMemory.UNLIMITED.get(ValueLayout.JAVA_INT, offset + 4);
    }

    public boolean keyEqualsWithLen(long offset, byte[] keyBytes, int keyLen) {
        if (offset == -1L)
            return false;
        int storedKeyLen = NativeMemory.UNLIMITED.get(ValueLayout.JAVA_INT, offset + 20);
        if (storedKeyLen != keyLen)
            return false;

        byte expected = computeFingerprint(keyBytes, keyLen);
        byte stored = NativeMemory.UNLIMITED.get(ValueLayout.JAVA_BYTE, offset + FINGERPRINT_OFFSET);
        if (stored != expected)
            return false;

        return compareKeyBytes(offset + 24, keyBytes, keyLen);
    }

    public boolean keyEqualsNoLenCheck(long offset, byte[] keyBytes, int keyLen) {
        byte expected = computeFingerprint(keyBytes, keyLen);
        byte stored = NativeMemory.UNLIMITED.get(ValueLayout.JAVA_BYTE, offset + FINGERPRINT_OFFSET);
        if (stored != expected)
            return false;
        return compareKeyBytes(offset + 24, keyBytes, keyLen);
    }

    /**
     * Fast key comparison using long-word (8 bytes at a time) reads.
     * O2 fix: Reads longs from byte[] via manual packing to avoid
     * MemorySegment.ofArray() allocation on every call.
     */
    private static boolean compareKeyBytes(long keyStart, byte[] keyBytes, int keyLen) {
        int i = 0;
        // Compare 8 bytes at a time using long-word reads
        for (; i + 8 <= keyLen; i += 8) {
            long offHeapWord = NativeMemory.UNLIMITED.get(UNALIGNED_LONG, keyStart + i);
            long onHeapWord = ((long) (keyBytes[i] & 0xFF)) |
                    ((long) (keyBytes[i + 1] & 0xFF) << 8) |
                    ((long) (keyBytes[i + 2] & 0xFF) << 16) |
                    ((long) (keyBytes[i + 3] & 0xFF) << 24) |
                    ((long) (keyBytes[i + 4] & 0xFF) << 32) |
                    ((long) (keyBytes[i + 5] & 0xFF) << 40) |
                    ((long) (keyBytes[i + 6] & 0xFF) << 48) |
                    ((long) (keyBytes[i + 7] & 0xFF) << 56);
            if (offHeapWord != onHeapWord)
                return false;
        }
        // Compare remaining bytes (0-7 tail bytes)
        for (; i < keyLen; i++) {
            if (NativeMemory.UNLIMITED.get(ValueLayout.JAVA_BYTE, keyStart + i) != keyBytes[i])
                return false;
        }
        return true;
    }

    private static byte computeFingerprint(byte[] keyBytes, int keyLen) {
        if (keyLen <= 0) {
            return 0;
        }
        int fp = keyLen;
        fp ^= keyBytes[0];
        fp ^= keyBytes[keyLen >>> 1];
        fp ^= keyBytes[keyLen - 1];
        return (byte) fp;
    }

    @SuppressWarnings("unchecked")
    public boolean matches(int slot, Object key, KeySerializer serializer) {
        if (slot == 0)
            return false;
        long offset = getOffset(slot);
        if (offset == -1L)
            return false;
        return matchesAt(offset, key, serializer);
    }

    @SuppressWarnings("unchecked")
    public boolean matchesAt(long offset, Object key, KeySerializer serializer) {
        int keyLen = NativeMemory.UNLIMITED.get(ValueLayout.JAVA_INT, offset + 20);
        long relOffset = offset - baseAddr;
        return serializer.matches(key, baseSegment, relOffset + 24, keyLen);
    }

    public int getKeyHash(int slot) {
        long offset = getOffset(slot);
        return (offset != -1L) ? NativeMemory.UNLIMITED.get(ValueLayout.JAVA_INT, offset + 0) : 0;
    }

    public short getPriority(int slot) {
        long offset = getOffset(slot);
        return (offset != -1L) ? NativeMemory.UNLIMITED.get(ValueLayout.JAVA_SHORT, offset + 18) : 0;
    }

    public long getExpiresAt(int slot) {
        long offset = getOffset(slot);
        return (offset != -1L) ? NativeMemory.UNLIMITED.get(ValueLayout.JAVA_LONG, offset + 8) : 0L;
    }

    /**
     * Clear the expiration time for a slot (set to 0 = no TTL).
     * Used by OffHeapTimingWheel for O(1) lazy cancellation.
     */
    public void clearExpiresAt(int slot) {
        long offset = getOffset(slot);
        if (offset != -1L) {
            NativeMemory.UNLIMITED.set(ValueLayout.JAVA_LONG, offset + 8, 0L);
        }
    }

    /**
     * AUDIT-H1: Update the expiration time for a slot.
     * Called on in-place value updates to ensure TTL is refreshed.
     */
    public void setExpiresAt(int slot, long expiresAtMillis) {
        long offset = getOffset(slot);
        if (offset != -1L) {
            NativeMemory.UNLIMITED.set(ValueLayout.JAVA_LONG, offset + 8, expiresAtMillis);
        }
    }

    public boolean isExpired(int slot) {
        long e = getExpiresAt(slot);
        return e > 0 && CoarseClock.getNow() >= e;
    }

    public byte[] readKey(int slot) {
        long offset = getOffset(slot);
        if (offset == -1L)
            return null;
        int keyLen = NativeMemory.UNLIMITED.get(ValueLayout.JAVA_INT, offset + 20);
        byte[] bytes = new byte[keyLen];
        MemorySegment.copy(NativeMemory.UNLIMITED, ValueLayout.JAVA_BYTE, offset + 24, bytes, 0, keyLen);
        return bytes;
    }

    public int getKeyLen(int slot) {
        long offset = getOffset(slot);
        if (offset == -1L)
            return 0;
        return NativeMemory.UNLIMITED.get(ValueLayout.JAVA_INT, offset + 20);
    }

    public byte[] readValue(int slot) {
        long offset = getOffset(slot);
        if (offset == -1L)
            return null;
        int keyLen = NativeMemory.UNLIMITED.get(ValueLayout.JAVA_INT, offset + 20);
        long vOffset = offset + 20 + 4 + EntryBlockLayout.pad(keyLen);
        int vLen = NativeMemory.UNLIMITED.get(ValueLayout.JAVA_INT, vOffset);
        byte[] bytes = new byte[vLen];
        MemorySegment.copy(NativeMemory.UNLIMITED, ValueLayout.JAVA_BYTE, vOffset + 4, bytes, 0, vLen);
        return bytes;
    }

    public boolean updateValue(int slot, byte[] valueBytes) {
        return updateValueWithLen(slot, valueBytes, valueBytes.length);
    }

    public boolean updateValueWithLen(int slot, byte[] valueBytes, int valueLen) {
        int pIdx = slot >>> partitionShift;
        return partitions[pIdx].updateValue(slot, valueBytes, valueLen, 0);
    }

    /**
     * AUDIT-C1: Update value with key-hash verification. If expectedKeyHash != 0,
     * the update is rejected if the stored key hash does not match. This catches
     * the ghost-cache TOCTOU race where a slot is freed and reallocated to a
     * different key between the ghost lookup and the updateValue call.
     */
    public boolean updateValueWithLen(int slot, byte[] valueBytes, int valueLen, int expectedKeyHash) {
        int pIdx = slot >>> partitionShift;
        return partitions[pIdx].updateValue(slot, valueBytes, valueLen, expectedKeyHash);
    }

    public boolean updateValueWithWriter(int slot, int valueMaxLen, ValueWriter writer) {
        int pIdx = slot >>> partitionShift;
        return partitions[pIdx].updateValueWithWriter(slot, valueMaxLen, writer);
    }

    public <V> boolean updateValueWithSerializer(int slot, int valueMaxLen,
            com.codeabbot.rmcache.serializer.SegmentValueSerializer<V> serializer, V value) {
        int pIdx = slot >>> partitionShift;
        return partitions[pIdx].updateValueWithSerializer(slot, valueMaxLen, serializer, value);
    }

    public int getValueLen(int slot) {
        long offset = getOffset(slot);
        if (offset == -1L)
            return 0;
        int keyLen = NativeMemory.UNLIMITED.get(ValueLayout.JAVA_INT, offset + 20);
        return NativeMemory.UNLIMITED.get(ValueLayout.JAVA_INT, offset + 20 + 4 + EntryBlockLayout.pad(keyLen));
    }

    public MemorySegment getValueSegment(int slot) {
        long offset = getOffset(slot);
        if (offset == -1L)
            return null;
        int keyLen = NativeMemory.UNLIMITED.get(ValueLayout.JAVA_INT, offset + 20);
        long vOffset = offset + 20 + 4 + EntryBlockLayout.pad(keyLen);
        int vLen = NativeMemory.UNLIMITED.get(ValueLayout.JAVA_INT, vOffset);
        return NativeMemory.UNLIMITED.asSlice(vOffset + 4, (long) vLen);
    }

    /**
     * P3-M1 fix: Return the absolute offset to the value data and its length
     * for a given slot. Encapsulates layout pointer arithmetic so callers
     * (like getView) don't need to know EntryBlockLayout internals.
     *
     * @return long[]{valueDataOffset, valueLen}, or null if slot is freed.
     */
    public long[] getValuePosition(int slot) {
        long offset = getOffset(slot);
        if (offset == -1L)
            return null;
        int keyLen = NativeMemory.UNLIMITED.get(ValueLayout.JAVA_INT, offset + 20);
        long vOffset = offset + 20 + 4 + EntryBlockLayout.pad(keyLen);
        int vLen = NativeMemory.UNLIMITED.get(ValueLayout.JAVA_INT, vOffset);
        return new long[] { vOffset + 4, vLen };
    }

    /**
     * E2E-E2: Allocation-free alternative to getValuePosition().
     * Returns the absolute offset to the start of the value data for a slot,
     * or -1L if the slot is freed. Use with getValueLen() to avoid
     * allocating a long[] per getView() call.
     */
    public long getValueDataOffset(int slot) {
        long offset = getOffset(slot);
        if (offset == -1L)
            return -1L;
        int keyLen = NativeMemory.UNLIMITED.get(ValueLayout.JAVA_INT, offset + 20);
        long vOffset = offset + 20 + 4 + EntryBlockLayout.pad(keyLen);
        return vOffset + 4;
    }

    public void readValueToBuffer(int slot, byte[] buffer, int bufferOffset, int length) {
        long offset = getOffset(slot);
        if (offset == -1L)
            return;
        int keyLen = NativeMemory.UNLIMITED.get(ValueLayout.JAVA_INT, offset + 20);
        long vOffset = offset + 20 + 4 + EntryBlockLayout.pad(keyLen);
        // P2-M2 fix: Clamp length to actual value size to prevent reading
        // garbage bytes from adjacent entry memory.
        int vLen = NativeMemory.UNLIMITED.get(ValueLayout.JAVA_INT, vOffset);
        length = Math.min(length, vLen);
        MemorySegment.copy(NativeMemory.UNLIMITED, ValueLayout.JAVA_BYTE, vOffset + 4, buffer, bufferOffset, length);
    }

    public int slotCapacity() {
        return slotCapacity;
    }

    private class SubPool {
        private final int id;
        private final int maxLocal;
        private final ReentrantLock lock = new ReentrantLock();
        private final MemorySegment freeSlots;
        private final AtomicInteger freeTop = new AtomicInteger(0);

        public SubPool(int id, int maxLocal) {
            this.id = id;
            this.maxLocal = maxLocal;
            this.freeSlots = NativeMemory.malloc((long) maxLocal * 4L);
            for (int i = 0; i < maxLocal; i++) {
                freeSlots.setAtIndex(ValueLayout.JAVA_INT, (long) i, i);
            }
            this.freeTop.set(maxLocal);
        }

        public void close() {
            NativeMemory.free(freeSlots);
        }

        public int allocate(int keyHash, byte[] keyBytes, int keyLen, byte[] valueBytes, int valueLen, short priority,
                long expiresAtMillis) {
            while (true) {
                int top = freeTop.get();
                if (top == 0)
                    return 0;

                if (freeTop.compareAndSet(top, top - 1)) {
                    int totalSize = EntryBlockLayout.computeSize(keyLen, valueLen);
                    long packedHandle;
                    try {
                        packedHandle = allocator.allocatePacked(totalSize);
                    } catch (Exception e) {
                        freeTop.incrementAndGet();
                        return 0;
                    }
                    if (packedHandle == -1L) {
                        freeTop.incrementAndGet();
                        return 0;
                    }

                    int localIdx = freeSlots.getAtIndex(ValueLayout.JAVA_INT, (long) (top - 1));
                    int slot = (id << partitionShift) | localIdx;
                    if (slot == 0) {
                        // E2E-C2 fix: Free the slab block to prevent native memory leak.
                        allocator.freePacked(packedHandle);
                        freeTop.incrementAndGet();
                        return 0;
                    }

                    long absOffset = baseAddr + AllocationHandle.unpackOffset(packedHandle);
                    int capacity = AllocationHandle.unpackCapacity(packedHandle);
                    int sizeClass = AllocationHandle.unpackSizeClass(packedHandle);
                    byte fingerprint = computeFingerprint(keyBytes, keyLen);
                    writeHeader(absOffset, keyHash, capacity, sizeClass, priority,
                            expiresAtMillis, slot, fingerprint);
                    writeData(absOffset, keyBytes, keyLen, valueBytes, valueLen);

                    LONG_HANDLE.setVolatile(offsets, (long) slot * 8L, packOffset(absOffset, sizeClass));
                    return slot;
                }
            }
        }

        public int allocateWithWriter(int keyHash, byte[] keyBytes, int keyLen, int valueMaxLen, ValueWriter writer,
                short priority, long expiresAtMillis) {
            while (true) {
                int top = freeTop.get();
                if (top == 0)
                    return 0;

                if (freeTop.compareAndSet(top, top - 1)) {
                    int totalSize = EntryBlockLayout.computeSize(keyLen, valueMaxLen);
                    long packedHandle;
                    try {
                        packedHandle = allocator.allocatePacked(totalSize);
                    } catch (Exception e) {
                        freeTop.incrementAndGet();
                        return 0;
                    }
                    if (packedHandle == -1L) {
                        freeTop.incrementAndGet();
                        return 0;
                    }

                    int localIdx = freeSlots.getAtIndex(ValueLayout.JAVA_INT, (long) (top - 1));
                    int slot = (id << partitionShift) | localIdx;
                    if (slot == 0) {
                        // E2E-C2 fix: Free the slab block to prevent native memory leak.
                        allocator.freePacked(packedHandle);
                        freeTop.incrementAndGet();
                        return 0;
                    }

                    long absOffset = baseAddr + AllocationHandle.unpackOffset(packedHandle);
                    int capacity = AllocationHandle.unpackCapacity(packedHandle);
                    int sizeClass = AllocationHandle.unpackSizeClass(packedHandle);
                    byte fingerprint = computeFingerprint(keyBytes, keyLen);
                    writeHeader(absOffset, keyHash, capacity, sizeClass, priority,
                            expiresAtMillis, slot, fingerprint);
                    boolean ok = writeDataWithWriter(absOffset, keyBytes, keyLen, valueMaxLen, writer);
                    if (!ok) {
                        long relOffset = absOffset - baseAddr;
                        allocator.freePacked(AllocationHandle.pack(relOffset, capacity, sizeClass));
                        freeTop.incrementAndGet();
                        return 0;
                    }

                    LONG_HANDLE.setVolatile(offsets, (long) slot * 8L, packOffset(absOffset, sizeClass));
                    return slot;
                }
            }
        }

        public <V> int allocateWithSerializer(int keyHash, byte[] keyBytes, int keyLen, int valueMaxLen,
                com.codeabbot.rmcache.serializer.SegmentValueSerializer<V> serializer, V value, short priority,
                long expiresAtMillis) {
            while (true) {
                int top = freeTop.get();
                if (top == 0)
                    return 0;

                if (freeTop.compareAndSet(top, top - 1)) {
                    int totalSize = EntryBlockLayout.computeSize(keyLen, valueMaxLen);
                    long packedHandle;
                    try {
                        packedHandle = allocator.allocatePacked(totalSize);
                    } catch (Exception e) {
                        freeTop.incrementAndGet();
                        return 0;
                    }
                    if (packedHandle == -1L) {
                        freeTop.incrementAndGet();
                        return 0;
                    }

                    int localIdx = freeSlots.getAtIndex(ValueLayout.JAVA_INT, (long) (top - 1));
                    int slot = (id << partitionShift) | localIdx;
                    if (slot == 0) {
                        // E2E-C2 fix: Free the slab block to prevent native memory leak.
                        allocator.freePacked(packedHandle);
                        freeTop.incrementAndGet();
                        return 0;
                    }

                    long absOffset = baseAddr + AllocationHandle.unpackOffset(packedHandle);
                    int capacity = AllocationHandle.unpackCapacity(packedHandle);
                    int sizeClass = AllocationHandle.unpackSizeClass(packedHandle);
                    byte fingerprint = computeFingerprint(keyBytes, keyLen);
                    writeHeader(absOffset, keyHash, capacity, sizeClass, priority,
                            expiresAtMillis, slot, fingerprint);
                    boolean ok = writeDataWithSerializer(absOffset, keyBytes, keyLen, valueMaxLen, serializer, value);
                    if (!ok) {
                        long relOffset = absOffset - baseAddr;
                        allocator.freePacked(AllocationHandle.pack(relOffset, capacity, sizeClass));
                        freeTop.incrementAndGet();
                        return 0;
                    }

                    LONG_HANDLE.setVolatile(offsets, (long) slot * 8L, packOffset(absOffset, sizeClass));
                    return slot;
                }
            }
        }

        public void free(int slot) {
            int localIdx = slot & partitionMask;
            long packed = (long) LONG_HANDLE.getVolatile(offsets, (long) slot * 8L);
            if (packed == -1L) return;

            // ISSUE-006: CAS to atomically claim the right to free this slot.
            // Prevents double-free when two threads race on free() for the same slot.
            if (!LONG_HANDLE.compareAndSet(offsets, (long) slot * 8L, packed, -1L)) {
                return; // Another thread already freed this slot
            }

            long absOffset = unpackAddr(packed);
            int sc = unpackSC(packed);
            int cap = (sc >= 0) ? SlabAllocator.SIZE_CLASSES[sc] : 0;

            long relOffset = absOffset - baseAddr;
            allocator.freePacked(AllocationHandle.pack(relOffset, cap, sc));

            // H1 fix: Use getAndIncrement instead of CAS loop to avoid
            // spin-locking under extreme contention with 128+ threads.
            // P3-C1 fix: Guard against overflow — if freeTop exceeds maxLocal
            // (double-free or logic error), rollback to prevent writing beyond
            // the native freeSlots segment boundary.
            int top = freeTop.getAndIncrement();
            if (top >= maxLocal) {
                freeTop.decrementAndGet(); // rollback — slot lost
                return;
            }
            freeSlots.setAtIndex(ValueLayout.JAVA_INT, (long) top, localIdx);
        }

        // C1 fix: Always lock for in-place value update. The previous lock-free
        // fast path allowed two concurrent threads to interleave value byte writes
        // for the same slot, causing torn reads. The volatile offset re-check only
        // caught reallocation races, not same-offset concurrent writes.
        public boolean updateValue(int slot, byte[] valueBytes, int valueLen, int expectedKeyHash) {
            lock.lock();
            try {
                long packed = (long) LONG_HANDLE.getVolatile(offsets, (long) slot * 8L);
                if (packed == -1L)
                    return false;
                long absOffset = unpackAddr(packed);
                // AUDIT-C1: Key-hash guard. If expectedKeyHash != 0, verify the
                // entry's stored key hash matches. Catches ghost-cache TOCTOU race
                // where slot was freed and reallocated to a different key.
                if (expectedKeyHash != 0) {
                    int storedHash = NativeMemory.UNLIMITED.get(ValueLayout.JAVA_INT, absOffset);
                    if (storedHash != expectedKeyHash)
                        return false;
                }
                int keyLen = NativeMemory.UNLIMITED.get(ValueLayout.JAVA_INT, absOffset + 20);
                int sc = unpackSC(packed);
                int cap = (sc >= 0) ? SlabAllocator.SIZE_CLASSES[sc] : 0;
                int newSize = EntryBlockLayout.computeSize(keyLen, valueLen);

                if (newSize <= cap) {
                    // K1: Write bytes FIRST, then length — prevents torn reads.
                    long vOffset = absOffset + EntryBlockLayout.DATA_OFFSET + EntryBlockLayout.pad(keyLen);
                    MemorySegment.copy(valueBytes, 0, NativeMemory.UNLIMITED, ValueLayout.JAVA_BYTE, vOffset + 4,
                            valueLen);
                    NativeMemory.UNLIMITED.set(ValueLayout.JAVA_INT, vOffset, valueLen);
                    return true;
                }

                // Slow path: reallocation needed (already under lock)
                int h = getKeyHash(slot);
                byte[] k = readKey(slot);
                short p = getPriority(slot);
                long e = getExpiresAt(slot);

                long packedHandle;
                try {
                    packedHandle = allocator.allocatePacked(newSize);
                } catch (Exception err) {
                    return false;
                }
                if (packedHandle == -1L)
                    return false;

                long relOffset = absOffset - baseAddr;
                allocator.freePacked(AllocationHandle.pack(relOffset, cap, sc));

                long newAbsOffset = baseAddr + AllocationHandle.unpackOffset(packedHandle);
                byte fingerprint = computeFingerprint(k, k.length);
                writeHeader(newAbsOffset, h, AllocationHandle.unpackCapacity(packedHandle),
                        AllocationHandle.unpackSizeClass(packedHandle), p, e, slot, fingerprint);
                writeData(newAbsOffset, k, k.length, valueBytes, valueLen);

                LONG_HANDLE.setVolatile(offsets, (long) slot * 8L,
                        packOffset(newAbsOffset, AllocationHandle.unpackSizeClass(packedHandle)));
                return true;
            } finally {
                lock.unlock();
            }
        }

        public boolean updateValueWithWriter(int slot, int valueMaxLen, ValueWriter writer) {
            lock.lock();
            try {
                long packed = (long) LONG_HANDLE.getVolatile(offsets, (long) slot * 8L);
                if (packed == -1L)
                    return false;
                long absOffset = unpackAddr(packed);
                int keyLen = NativeMemory.UNLIMITED.get(ValueLayout.JAVA_INT, absOffset + 20);
                int sc = unpackSC(packed);
                int cap = (sc >= 0) ? SlabAllocator.SIZE_CLASSES[sc] : 0; // derive capacity
                int newSize = EntryBlockLayout.computeSize(keyLen, valueMaxLen);

                if (newSize <= cap) {
                    long vOffset = absOffset + EntryBlockLayout.DATA_OFFSET + EntryBlockLayout.pad(keyLen);
                    int written = writer.write(NativeMemory.UNLIMITED, vOffset + 4, valueMaxLen);
                    if (written < 0 || written > valueMaxLen) {
                        return false;
                    }
                    NativeMemory.UNLIMITED.set(ValueLayout.JAVA_INT, vOffset, written);
                    return true;
                } else {
                    int h = getKeyHash(slot);
                    byte[] k = readKey(slot);
                    short p = getPriority(slot);
                    long e = getExpiresAt(slot);

                    long packedHandle;
                    try {
                        packedHandle = allocator.allocatePacked(newSize);
                    } catch (Exception err) {
                        return false;
                    }
                    if (packedHandle == -1L)
                        return false;
                    // sc already read above at line 520

                    long newAbsOffset = baseAddr + AllocationHandle.unpackOffset(packedHandle);
                    int newCapacity = AllocationHandle.unpackCapacity(packedHandle);
                    int newSc = AllocationHandle.unpackSizeClass(packedHandle);
                    byte fingerprint = computeFingerprint(k, k.length);
                    writeHeader(newAbsOffset, h, newCapacity, newSc, p, e, slot, fingerprint);
                    boolean ok = writeDataWithWriter(newAbsOffset, k, k.length, valueMaxLen, writer);
                    if (!ok) {
                        long newRelOffset = newAbsOffset - baseAddr;
                        allocator.freePacked(AllocationHandle.pack(newRelOffset, newCapacity, newSc));
                        return false;
                    }

                    LONG_HANDLE.setVolatile(offsets, (long) slot * 8L, packOffset(newAbsOffset, newSc));
                    long relOffset = absOffset - baseAddr;
                    allocator.freePacked(AllocationHandle.pack(relOffset, cap, sc));
                    return true;
                }
            } finally {
                lock.unlock();
            }
        }

        public <V> boolean updateValueWithSerializer(int slot, int valueMaxLen,
                com.codeabbot.rmcache.serializer.SegmentValueSerializer<V> serializer, V value) {
            lock.lock();
            try {
                long packed = (long) LONG_HANDLE.getVolatile(offsets, (long) slot * 8L);
                if (packed == -1L)
                    return false;
                long absOffset = unpackAddr(packed);
                int keyLen = NativeMemory.UNLIMITED.get(ValueLayout.JAVA_INT, absOffset + 20);
                int sc = unpackSC(packed);
                int cap = (sc >= 0) ? SlabAllocator.SIZE_CLASSES[sc] : 0; // derive capacity
                int newSize = EntryBlockLayout.computeSize(keyLen, valueMaxLen);

                if (newSize <= cap) {
                    long vOffset = absOffset + EntryBlockLayout.DATA_OFFSET + EntryBlockLayout.pad(keyLen);
                    int written = serializer.serializeTo(value, NativeMemory.UNLIMITED, vOffset + 4, valueMaxLen);
                    if (written < 0 || written > valueMaxLen) {
                        return false;
                    }
                    NativeMemory.UNLIMITED.set(ValueLayout.JAVA_INT, vOffset, written);
                    return true;
                } else {
                    int h = getKeyHash(slot);
                    byte[] k = readKey(slot);
                    short p = getPriority(slot);
                    long e = getExpiresAt(slot);

                    long packedHandle;
                    try {
                        packedHandle = allocator.allocatePacked(newSize);
                    } catch (Exception err) {
                        return false;
                    }
                    if (packedHandle == -1L)
                        return false;
                    // sc already read above at line 578

                    long newAbsOffset = baseAddr + AllocationHandle.unpackOffset(packedHandle);
                    int newCapacity = AllocationHandle.unpackCapacity(packedHandle);
                    int newSc = AllocationHandle.unpackSizeClass(packedHandle);
                    byte fingerprint = computeFingerprint(k, k.length);
                    writeHeader(newAbsOffset, h, newCapacity, newSc, p, e, slot, fingerprint);
                    boolean ok = writeDataWithSerializer(newAbsOffset, k, k.length, valueMaxLen, serializer, value);
                    if (!ok) {
                        long newRelOffset = newAbsOffset - baseAddr;
                        allocator.freePacked(AllocationHandle.pack(newRelOffset, newCapacity, newSc));
                        return false;
                    }

                    LONG_HANDLE.setVolatile(offsets, (long) slot * 8L, packOffset(newAbsOffset, newSc));
                    long relOffset = absOffset - baseAddr;
                    allocator.freePacked(AllocationHandle.pack(relOffset, cap, sc));
                    return true;
                }
            } finally {
                lock.unlock();
            }
        }

        private void writeHeader(long offset, int h, int cap, int sc, short p, long exp, int slot, byte fingerprint) {
            NativeMemory.UNLIMITED.set(ValueLayout.JAVA_INT, offset + 0, h);
            NativeMemory.UNLIMITED.set(ValueLayout.JAVA_INT, offset + 4, slot);
            NativeMemory.UNLIMITED.set(ValueLayout.JAVA_LONG, offset + 8, exp);
            NativeMemory.UNLIMITED.set(ValueLayout.JAVA_BYTE, offset + 16, (byte) sc);
            NativeMemory.UNLIMITED.set(ValueLayout.JAVA_BYTE, offset + FINGERPRINT_OFFSET, fingerprint);
            NativeMemory.UNLIMITED.set(ValueLayout.JAVA_SHORT, offset + 18, p);
        }

        private void writeData(long offset, byte[] keyBytes, int keyLen, byte[] valueBytes, int valueLen) {
            long dataStart = offset + EntryBlockLayout.HEADER_SIZE;
            NativeMemory.UNLIMITED.set(ValueLayout.JAVA_INT, dataStart, keyLen);
            MemorySegment.copy(keyBytes, 0, NativeMemory.UNLIMITED, ValueLayout.JAVA_BYTE, dataStart + 4, keyLen);

            long valLenOffset = dataStart + 4 + EntryBlockLayout.pad(keyLen);
            NativeMemory.UNLIMITED.set(ValueLayout.JAVA_INT, valLenOffset, valueLen);
            MemorySegment.copy(valueBytes, 0, NativeMemory.UNLIMITED, ValueLayout.JAVA_BYTE, valLenOffset + 4,
                    valueLen);
        }

        private boolean writeDataWithWriter(long offset, byte[] keyBytes, int keyLen, int valueMaxLen,
                ValueWriter writer) {
            long dataStart = offset + EntryBlockLayout.HEADER_SIZE;
            NativeMemory.UNLIMITED.set(ValueLayout.JAVA_INT, dataStart, keyLen);
            MemorySegment.copy(keyBytes, 0, NativeMemory.UNLIMITED, ValueLayout.JAVA_BYTE, dataStart + 4, keyLen);

            long valLenOffset = dataStart + 4 + EntryBlockLayout.pad(keyLen);
            int written = writer.write(NativeMemory.UNLIMITED, valLenOffset + 4, valueMaxLen);
            if (written < 0 || written > valueMaxLen) {
                return false;
            }
            NativeMemory.UNLIMITED.set(ValueLayout.JAVA_INT, valLenOffset, written);
            return true;
        }

        private <V> boolean writeDataWithSerializer(long offset, byte[] keyBytes, int keyLen, int valueMaxLen,
                com.codeabbot.rmcache.serializer.SegmentValueSerializer<V> serializer, V value) {
            long dataStart = offset + EntryBlockLayout.HEADER_SIZE;
            NativeMemory.UNLIMITED.set(ValueLayout.JAVA_INT, dataStart, keyLen);
            MemorySegment.copy(keyBytes, 0, NativeMemory.UNLIMITED, ValueLayout.JAVA_BYTE, dataStart + 4, keyLen);

            long valLenOffset = dataStart + 4 + EntryBlockLayout.pad(keyLen);
            int written = serializer.serializeTo(value, NativeMemory.UNLIMITED, valLenOffset + 4, valueMaxLen);
            if (written < 0 || written > valueMaxLen) {
                return false;
            }
            NativeMemory.UNLIMITED.set(ValueLayout.JAVA_INT, valLenOffset, written);
            return true;
        }
    }
}
