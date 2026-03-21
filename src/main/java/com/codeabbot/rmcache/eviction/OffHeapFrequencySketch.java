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
package com.codeabbot.rmcache.eviction;

import com.codeabbot.rmcache.memory.NativeMemory;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Off-Heap implementation of FrequencySketch (Count-Min Sketch).
 * Stores counters in a native long array (packed 4-bit counters).
 * 
 * @author Rabindra Meher
 */
class OffHeapFrequencySketch implements AutoCloseable {
    private static final VarHandle LONG_HANDLE = ValueLayout.JAVA_LONG.varHandle();

    private final MemorySegment table;
    private final int tableMask;
    private final AtomicInteger sampleSize = new AtomicInteger(0); // H2 fix: atomic to prevent race
    private final AtomicBoolean resetting = new AtomicBoolean(false); // H2 fix: single-writer reset guard
    private final int resetThreshold;
    private final int tableSize;

    private static final long RESET_MASK = 0x7777777777777777L;

    private static final int MAX_TABLE_SIZE = 1 << 24; // 16M entries = 128 MB max

    public OffHeapFrequencySketch(int expectedSize) {
        int size = Math.min(ceilingPowerOfTwo(Math.max(expectedSize, 1)), MAX_TABLE_SIZE);
        this.tableSize = size;
        this.tableMask = size - 1;
        this.resetThreshold = size * 10;
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

        // H2 fix: atomic increment + CAS-guarded reset
        if (sampleSize.incrementAndGet() >= resetThreshold) {
            if (resetting.compareAndSet(false, true)) {
                try {
                    reset();
                } finally {
                    resetting.set(false);
                }
            }
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
        sampleSize.updateAndGet(v -> v >>> 1);
    }

    // ISSUE-014: Single-attempt CAS to avoid lost counter updates under contention.
    // No retry on CAS failure — the sketch is approximate by design, so occasional
    // lost increments are acceptable. This provides better correctness than plain
    // read-modify-write with minimal overhead (single CAS, no spin).
    private void incrementAt(int tableIndex, int slot) {
        int shift = slot * 4;
        long byteOffset = (long) tableIndex * 8;
        long value = (long) LONG_HANDLE.getVolatile(table, byteOffset);
        long counter = (value >>> shift) & 0xFL;

        if (counter < 15) {
            LONG_HANDLE.compareAndExchange(table, byteOffset, value, value + (1L << shift));
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
