package com.codeabbot.rmcache.index;

import com.codeabbot.rmcache.memory.NativeMemory;
import com.codeabbot.rmcache.serializer.KeySerializer;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Off-heap L1 cache storing (hash, slot) pairs for hot keys.
 * Direct-mapped, best-effort, zero-heap.
 *
 * <p><b>Implementation note:</b> This cache uses <b>plain (non-volatile)</b> 64-bit reads and writes
 *           for maximum throughput. Under concurrent access, a torn read may
 *           observe the
 *           upper 32 bits (hash) from one entry and the lower 32 bits (slot)
 *           from
 *           another.
 *           This is safe because: (a) a hash mismatch causes a cache miss
 *           (L33-L41), and
 *           (b) a slot mismatch is re-validated against the hash table (A2 fix
 *           in
 *           OffHeapCacheImpl.get).
 *           The worst case is a spurious miss, never corruption.
 */
public final class OffHeapGhostCache implements AutoCloseable {
    private static final int SLOT_SIZE = 8;

    private final int capacity;
    private final int mask;
    private final MemorySegment table;
    private final long baseAddr;

    public OffHeapGhostCache(int capacity) {
        int cap = 1;
        while (cap < capacity) {
            cap <<= 1;
        }
        this.capacity = cap;
        this.mask = cap - 1;
        this.table = NativeMemory.calloc(cap, SLOT_SIZE);
        this.baseAddr = table.address();
    }

    public int getSlot(Object key, int keyHash, EntryPool entryPool, KeySerializer serializer) {
        int idx = keyHash & mask;
        long entry = NativeMemory.UNLIMITED.get(ValueLayout.JAVA_LONG, baseAddr + ((long) idx << 3));
        if (entry == 0L) {
            return 0;
        }

        int storedHash = (int) (entry >>> 32);
        if (storedHash != keyHash) {
            return 0;
        }

        int slot = (int) entry;
        if (slot == 0) {
            return 0;
        }

        long offset = entryPool.getOffset(slot);
        if (offset == -1L) {
            return 0;
        }

        // P3-O2 fix: Use matchesAt(offset, ...) to skip the redundant
        // getOffset(slot) call inside matches(). Saves one volatile read
        // on the ghost cache hot path.
        if (!entryPool.matchesAt(offset, key, serializer)) {
            return 0;
        }

        return slot;
    }

    public void put(int keyHash, int slot) {
        if (slot == 0) {
            return;
        }
        int idx = keyHash & mask;
        long entry = ((long) keyHash << 32) | (slot & 0xFFFFFFFFL);
        NativeMemory.UNLIMITED.set(ValueLayout.JAVA_LONG, baseAddr + ((long) idx << 3), entry);
    }

    public void invalidate(int keyHash) {
        int idx = keyHash & mask;
        long addr = baseAddr + ((long) idx << 3);
        long entry = NativeMemory.UNLIMITED.get(ValueLayout.JAVA_LONG, addr);
        if (entry == 0L) {
            return;
        }
        int storedHash = (int) (entry >>> 32);
        if (storedHash == keyHash) {
            NativeMemory.UNLIMITED.set(ValueLayout.JAVA_LONG, addr, 0L);
        }
    }

    public void clear() {
        table.fill((byte) 0);
    }

    public int capacity() {
        return capacity;
    }

    @Override
    public void close() {
        NativeMemory.free(table);
    }
}
