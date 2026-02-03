package com.codeabbot.rmcache.eviction;

import com.codeabbot.rmcache.memory.NativeMemory;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Off-Heap implementation of FrequencySketch (Count-Min Sketch).
 * Stores counters in a native long array (packed 4-bit counters).
 * 
 * @author Rabindra Meher
 */
class OffHeapFrequencySketch implements AutoCloseable {
    private final MemorySegment table;
    private final int tableMask;
    private int sampleSize = 0;
    private final int resetThreshold;
    private final int tableSize;

    private static final long RESET_MASK = 0x7777777777777777L;

    public OffHeapFrequencySketch(int expectedSize) {
        int size = ceilingPowerOfTwo(Math.max(expectedSize, 1));
        this.tableSize = size;
        this.tableMask = size - 1;
        this.resetThreshold = expectedSize * 10;
        this.table = NativeMemory.calloc(size, ValueLayout.JAVA_LONG.byteSize());
    }

    @Override
    public void close() {
        NativeMemory.free(table);
    }

    public void increment(int hash) {
        int h = spread(hash);

        int index0 = (h >>> 0) & tableMask;
        int index1 = (h >>> 8) & tableMask;
        int index2 = (h >>> 16) & tableMask;
        int index3 = (h >>> 24) & tableMask;

        int slot0 = (h >>> 0) & 0xF;
        int slot1 = (h >>> 4) & 0xF;
        int slot2 = (h >>> 8) & 0xF;
        int slot3 = (h >>> 12) & 0xF;

        incrementAt(index0, slot0);
        incrementAt(index1, slot1);
        incrementAt(index2, slot2);
        incrementAt(index3, slot3);

        if (++sampleSize >= resetThreshold) {
            reset();
        }
    }

    public int frequency(int hash) {
        int h = spread(hash);

        int index0 = (h >>> 0) & tableMask;
        int index1 = (h >>> 8) & tableMask;
        int index2 = (h >>> 16) & tableMask;
        int index3 = (h >>> 24) & tableMask;

        int slot0 = (h >>> 0) & 0xF;
        int slot1 = (h >>> 4) & 0xF;
        int slot2 = (h >>> 8) & 0xF;
        int slot3 = (h >>> 12) & 0xF;

        return Math.min(Math.min(counterAt(index0, slot0), counterAt(index1, slot1)),
                Math.min(counterAt(index2, slot2), counterAt(index3, slot3)));
    }

    private void reset() {
        for (int i = 0; i < tableSize; i++) {
            long val = table.get(ValueLayout.JAVA_LONG, (long) i * 8);
            table.set(ValueLayout.JAVA_LONG, (long) i * 8, (val >>> 1) & RESET_MASK);
        }
        sampleSize >>>= 1;
    }

    private void incrementAt(int tableIndex, int slot) {
        int offset = slot * 4;
        long value = table.get(ValueLayout.JAVA_LONG, (long) tableIndex * 8);
        long counter = (value >>> offset) & 0xFL;

        if (counter < 15) {
            table.set(ValueLayout.JAVA_LONG, (long) tableIndex * 8, value + (1L << offset));
        }
    }

    private int counterAt(int tableIndex, int slot) {
        int offset = slot * 4;
        long value = table.get(ValueLayout.JAVA_LONG, (long) tableIndex * 8);
        return (int) ((value >>> offset) & 0xFL);
    }

    private int spread(int hash) {
        int h = hash;
        h ^= h >>> 16;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        return h;
    }

    private static int ceilingPowerOfTwo(int n) {
        int x = n - 1;
        x |= x >>> 1;
        x |= x >>> 2;
        x |= x >>> 4;
        x |= x >>> 8;
        x |= x >>> 16;
        return x + 1;
    }
}
