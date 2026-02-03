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

    public void free(int slot) {
        if (slot <= 0)
            return;
        int pIdx = slot >>> partitionShift;
        partitions[pIdx].free(slot);
    }

    public long getOffset(int slot) {
        return (long) LONG_HANDLE.getVolatile(offsets, (long) slot * 8L);
    }

    public int getSlotFromOffset(long offset) {
        return NativeMemory.UNLIMITED.get(ValueLayout.JAVA_INT, offset + 16);
    }

    public boolean keyEqualsWithLen(long offset, byte[] keyBytes, int keyLen) {
        if (offset == -1L)
            return false;
        int storedKeyLen = NativeMemory.UNLIMITED.get(ValueLayout.JAVA_INT, offset + 24);
        if (storedKeyLen != keyLen)
            return false;

        long keyStart = offset + 28;
        MemorySegment storedKeySegment = NativeMemory.UNLIMITED.asSlice(keyStart, (long) keyLen);
        MemorySegment keySegment = MemorySegment.ofArray(keyBytes).asSlice(0, (long) keyLen);

        return storedKeySegment.mismatch(keySegment) == -1L;
    }

    public boolean keyEqualsNoLenCheck(long offset, byte[] keyBytes, int keyLen) {
        long keyStart = offset + 28;
        MemorySegment storedKeySegment = NativeMemory.UNLIMITED.asSlice(keyStart, (long) keyLen);
        MemorySegment keySegment = MemorySegment.ofArray(keyBytes).asSlice(0, (long) keyLen);
        return storedKeySegment.mismatch(keySegment) == -1L;
    }

    @SuppressWarnings("unchecked")
    public boolean matches(int slot, Object key, KeySerializer serializer) {
        if (slot == 0)
            return false;
        long offset = getOffset(slot);
        if (offset == -1L)
            return false;
        int keyLen = NativeMemory.UNLIMITED.get(ValueLayout.JAVA_INT, offset + 24);
        long relOffset = offset - baseAddr;
        return serializer.matches(key, baseSegment, relOffset + 28, keyLen);
    }

    public int getKeyHash(int slot) {
        long offset = getOffset(slot);
        return (offset != -1L) ? NativeMemory.UNLIMITED.get(ValueLayout.JAVA_INT, offset + 0) : 0;
    }

    public short getPriority(int slot) {
        long offset = getOffset(slot);
        return (offset != -1L) ? NativeMemory.UNLIMITED.get(ValueLayout.JAVA_SHORT, offset + 22) : 0;
    }

    public long getExpiresAt(int slot) {
        long offset = getOffset(slot);
        return (offset != -1L) ? NativeMemory.UNLIMITED.get(UNALIGNED_LONG, offset + 8) : 0L;
    }

    public boolean isExpired(int slot) {
        long e = getExpiresAt(slot);
        return e > 0 && CoarseClock.getNow() >= e;
    }

    public byte[] readKey(int slot) {
        long offset = getOffset(slot);
        if (offset == -1L)
            return null;
        int keyLen = NativeMemory.UNLIMITED.get(ValueLayout.JAVA_INT, offset + 24);
        byte[] bytes = new byte[keyLen];
        MemorySegment.copy(NativeMemory.UNLIMITED, ValueLayout.JAVA_BYTE, offset + 28, bytes, 0, keyLen);
        return bytes;
    }

    public byte[] readValue(int slot) {
        long offset = getOffset(slot);
        if (offset == -1L)
            return null;
        int keyLen = NativeMemory.UNLIMITED.get(ValueLayout.JAVA_INT, offset + 24);
        long vOffset = offset + 24 + 4 + EntryBlockLayout.pad(keyLen);
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
        return partitions[pIdx].updateValue(slot, valueBytes, valueLen);
    }

    public boolean updateValueWithWriter(int slot, int valueMaxLen, ValueWriter writer) {
        int pIdx = slot >>> partitionShift;
        return partitions[pIdx].updateValueWithWriter(slot, valueMaxLen, writer);
    }

    public int getValueLen(int slot) {
        long offset = getOffset(slot);
        if (offset == -1L)
            return 0;
        int keyLen = NativeMemory.UNLIMITED.get(ValueLayout.JAVA_INT, offset + 24);
        return NativeMemory.UNLIMITED.get(ValueLayout.JAVA_INT, offset + 24 + 4 + EntryBlockLayout.pad(keyLen));
    }

    public MemorySegment getValueSegment(int slot) {
        long offset = getOffset(slot);
        if (offset == -1L)
            return null;
        int keyLen = NativeMemory.UNLIMITED.get(ValueLayout.JAVA_INT, offset + 24);
        long vOffset = offset + 24 + 4 + EntryBlockLayout.pad(keyLen);
        int vLen = NativeMemory.UNLIMITED.get(ValueLayout.JAVA_INT, vOffset);
        return NativeMemory.UNLIMITED.asSlice(vOffset + 4, (long) vLen);
    }

    public void readValueToBuffer(int slot, byte[] buffer, int bufferOffset, int length) {
        long offset = getOffset(slot);
        if (offset == -1L)
            return;
        int keyLen = NativeMemory.UNLIMITED.get(ValueLayout.JAVA_INT, offset + 24);
        long vOffset = offset + 24 + 4 + EntryBlockLayout.pad(keyLen);
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
                    AllocationHandle handle;
                    try {
                        handle = allocator.allocate(totalSize);
                    } catch (Exception e) {
                        freeTop.incrementAndGet();
                        return 0;
                    }

                    int localIdx = freeSlots.getAtIndex(ValueLayout.JAVA_INT, (long) (top - 1));
                    int slot = (id << partitionShift) | localIdx;
                    if (slot == 0) {
                        freeTop.incrementAndGet();
                        return 0;
                    }

                    long absOffset = baseAddr + handle.getOffset();
                    writeHeader(absOffset, keyHash, handle.getCapacity(), handle.getSizeClass(), priority,
                            expiresAtMillis, slot);
                    writeData(absOffset, keyBytes, keyLen, valueBytes, valueLen);

                    LONG_HANDLE.setVolatile(offsets, (long) slot * 8L, absOffset);
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
                    AllocationHandle handle;
                    try {
                        handle = allocator.allocate(totalSize);
                    } catch (Exception e) {
                        freeTop.incrementAndGet();
                        return 0;
                    }

                    int localIdx = freeSlots.getAtIndex(ValueLayout.JAVA_INT, (long) (top - 1));
                    int slot = (id << partitionShift) | localIdx;
                    if (slot == 0) {
                        freeTop.incrementAndGet();
                        return 0;
                    }

                    long absOffset = baseAddr + handle.getOffset();
                    writeHeader(absOffset, keyHash, handle.getCapacity(), handle.getSizeClass(), priority,
                            expiresAtMillis, slot);
                    boolean ok = writeDataWithWriter(absOffset, keyBytes, keyLen, valueMaxLen, writer);
                    if (!ok) {
                        int cap = handle.getCapacity();
                        int sc = handle.getSizeClass();
                        long relOffset = absOffset - baseAddr;
                        allocator.free(new AllocationHandle(baseSegment, relOffset, cap, sc));
                        freeTop.incrementAndGet();
                        return 0;
                    }

                    LONG_HANDLE.setVolatile(offsets, (long) slot * 8L, absOffset);
                    return slot;
                }
            }
        }

        public void free(int slot) {
            int localIdx = slot & partitionMask;
            long absOffset = (long) LONG_HANDLE.getVolatile(offsets, (long) slot * 8L);
            if (absOffset != -1L) {
                int cap = NativeMemory.UNLIMITED.get(ValueLayout.JAVA_INT, absOffset + 4);
                int sc = (int) NativeMemory.UNLIMITED.get(ValueLayout.JAVA_BYTE, absOffset + 20);

                long relOffset = absOffset - baseAddr;
                allocator.free(new AllocationHandle(baseSegment, relOffset, cap, sc));

                LONG_HANDLE.setVolatile(offsets, (long) slot * 8L, -1L);

                while (true) {
                    int top = freeTop.get();
                    freeSlots.setAtIndex(ValueLayout.JAVA_INT, (long) top, localIdx);
                    if (freeTop.compareAndSet(top, top + 1))
                        break;
                }
            }
        }

        public boolean updateValue(int slot, byte[] valueBytes, int valueLen) {
            lock.lock();
            try {
                long absOffset = (long) LONG_HANDLE.getVolatile(offsets, (long) slot * 8L);
                if (absOffset == -1L)
                    return false;
                int keyLen = NativeMemory.UNLIMITED.get(ValueLayout.JAVA_INT, absOffset + 24);
                int cap = NativeMemory.UNLIMITED.get(ValueLayout.JAVA_INT, absOffset + 4);
                int newSize = EntryBlockLayout.computeSize(keyLen, valueLen);

                if (newSize <= cap) {
                    long vOffset = absOffset + 24 + 4 + EntryBlockLayout.pad(keyLen);
                    NativeMemory.UNLIMITED.set(ValueLayout.JAVA_INT, vOffset, valueLen);
                    MemorySegment.copy(valueBytes, 0, NativeMemory.UNLIMITED, ValueLayout.JAVA_BYTE, vOffset + 4,
                            valueLen);
                    return true;
                } else {
                    int h = getKeyHash(slot);
                    byte[] k = readKey(slot);
                    short p = getPriority(slot);
                    long e = getExpiresAt(slot);

                    AllocationHandle newH;
                    try {
                        newH = allocator.allocate(newSize);
                    } catch (Exception err) {
                        return false;
                    }
                    int sc = (int) NativeMemory.UNLIMITED.get(ValueLayout.JAVA_BYTE, absOffset + 20);

                    long relOffset = absOffset - baseAddr;
                    allocator.free(new AllocationHandle(baseSegment, relOffset, cap, sc));

                    long newAbsOffset = baseAddr + newH.getOffset();
                    writeHeader(newAbsOffset, h, newH.getCapacity(), newH.getSizeClass(), p, e, slot);
                    writeData(newAbsOffset, k, k.length, valueBytes, valueLen);

                    LONG_HANDLE.setVolatile(offsets, (long) slot * 8L, newAbsOffset);
                    return true;
                }
            } finally {
                lock.unlock();
            }
        }

        public boolean updateValueWithWriter(int slot, int valueMaxLen, ValueWriter writer) {
            lock.lock();
            try {
                long absOffset = (long) LONG_HANDLE.getVolatile(offsets, (long) slot * 8L);
                if (absOffset == -1L)
                    return false;
                int keyLen = NativeMemory.UNLIMITED.get(ValueLayout.JAVA_INT, absOffset + 24);
                int cap = NativeMemory.UNLIMITED.get(ValueLayout.JAVA_INT, absOffset + 4);
                int newSize = EntryBlockLayout.computeSize(keyLen, valueMaxLen);

                if (newSize <= cap) {
                    long vOffset = absOffset + 24 + 4 + EntryBlockLayout.pad(keyLen);
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

                    AllocationHandle newH;
                    try {
                        newH = allocator.allocate(newSize);
                    } catch (Exception err) {
                        return false;
                    }
                    int sc = (int) NativeMemory.UNLIMITED.get(ValueLayout.JAVA_BYTE, absOffset + 20);

                    long newAbsOffset = baseAddr + newH.getOffset();
                    writeHeader(newAbsOffset, h, newH.getCapacity(), newH.getSizeClass(), p, e, slot);
                    boolean ok = writeDataWithWriter(newAbsOffset, k, k.length, valueMaxLen, writer);
                    if (!ok) {
                        int newCap = newH.getCapacity();
                        int newSc = newH.getSizeClass();
                        long newRelOffset = newAbsOffset - baseAddr;
                        allocator.free(new AllocationHandle(baseSegment, newRelOffset, newCap, newSc));
                        return false;
                    }

                    LONG_HANDLE.setVolatile(offsets, (long) slot * 8L, newAbsOffset);
                    long relOffset = absOffset - baseAddr;
                    allocator.free(new AllocationHandle(baseSegment, relOffset, cap, sc));
                    return true;
                }
            } finally {
                lock.unlock();
            }
        }

        private void writeHeader(long offset, int h, int cap, int sc, short p, long exp, int slot) {
            NativeMemory.UNLIMITED.set(ValueLayout.JAVA_INT, offset + 0, h);
            NativeMemory.UNLIMITED.set(ValueLayout.JAVA_INT, offset + 4, cap);
            NativeMemory.UNLIMITED.set(ValueLayout.JAVA_LONG, offset + 8, exp); // Standard long write
            NativeMemory.UNLIMITED.set(ValueLayout.JAVA_INT, offset + 16, slot);
            NativeMemory.UNLIMITED.set(ValueLayout.JAVA_BYTE, offset + 20, (byte) sc);
            NativeMemory.UNLIMITED.set(ValueLayout.JAVA_SHORT, offset + 22, p);
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
    }
}
