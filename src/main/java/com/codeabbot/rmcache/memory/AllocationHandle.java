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

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * A lightweight handle to an allocated memory block.
 * Supports a packed long representation to avoid object allocation on the hot
 * path.
 *
 * Packed format (64 bits): [offset:40 | capacity:20 | sizeClass:4]
 * - offset: 40 bits → up to 1 TB addressable
 * - capacity: 20 bits → up to 1 MB block size (actual capacity = raw {@literal <<} 6, so
 * up to 64 MB)
 * - sizeClass: 4 bits → 0-10 for slab classes, 15 for large (-1 mapped to 0xF)
 *
 * @author Rabindra Meher
 */
public class AllocationHandle {
    private final MemorySegment segment;
    private final long offset;
    private int capacity;
    private final int sizeClass;

    // Packed handle constants
    private static final int SC_BITS = 4;
    private static final int CAP_BITS = 20;
    private static final long SC_MASK = (1L << SC_BITS) - 1; // 0xF
    private static final long CAP_MASK = (1L << CAP_BITS) - 1; // 0xFFFFF
    private static final int CAP_SHIFT = 6; // capacity granularity = 64 bytes

    public AllocationHandle(MemorySegment segment, long offset, int capacity, int sizeClass) {
        this.segment = segment;
        this.offset = offset;
        this.capacity = capacity;
        this.sizeClass = sizeClass;
    }

    /**
     * Pack offset, capacity, and sizeClass into a single long.
     * Avoids object allocation on the hot path.
     */
    public static long pack(long offset, int capacity, int sizeClass) {
        int sc4 = (sizeClass < 0) ? 0xF : (sizeClass & 0xF);
        long capEncoded = ((long) capacity >>> CAP_SHIFT) & CAP_MASK;
        return (offset << (SC_BITS + CAP_BITS)) | (capEncoded << SC_BITS) | sc4;
    }

    public static long unpackOffset(long packed) {
        return packed >>> (SC_BITS + CAP_BITS);
    }

    public static int unpackCapacity(long packed) {
        return (int) ((packed >>> SC_BITS) & CAP_MASK) << CAP_SHIFT;
    }

    public static int unpackSizeClass(long packed) {
        int sc4 = (int) (packed & SC_MASK);
        return (sc4 == 0xF) ? -1 : sc4;
    }

    public MemorySegment getSegment() {
        return segment;
    }

    public long getOffset() {
        return offset;
    }

    public int getCapacity() {
        return capacity;
    }

    public void setCapacity(int capacity) {
        this.capacity = capacity;
    }

    public int getSizeClass() {
        return sizeClass;
    }

    public boolean isLarge() {
        return sizeClass < 0;
    }

    public byte readByte(int index) {
        return segment.get(ValueLayout.JAVA_BYTE, offset + index);
    }

    public void writeByte(int index, byte value) {
        segment.set(ValueLayout.JAVA_BYTE, offset + index, value);
    }

    public int readInt(int index) {
        return segment.get(ValueLayout.JAVA_INT_UNALIGNED, offset + index);
    }

    public void writeInt(int index, int value) {
        segment.set(ValueLayout.JAVA_INT_UNALIGNED, offset + index, value);
    }

    public short readShort(int index) {
        return segment.get(ValueLayout.JAVA_SHORT_UNALIGNED, offset + index);
    }

    public void writeShort(int index, short value) {
        segment.set(ValueLayout.JAVA_SHORT_UNALIGNED, offset + index, value);
    }

    public void readBytes(int index, byte[] dest, int destOffset, int length) {
        MemorySegment.copy(
                segment, ValueLayout.JAVA_BYTE, offset + index,
                dest, destOffset, length);
    }

    public void readBytes(int index, byte[] dest) {
        readBytes(index, dest, 0, dest.length);
    }

    public void writeBytes(int index, byte[] src, int srcOffset, int length) {
        MemorySegment.copy(
                src, srcOffset,
                segment, ValueLayout.JAVA_BYTE, offset + index,
                length);
    }

    public void writeBytes(int index, byte[] src) {
        writeBytes(index, src, 0, src.length);
    }

    /**
     * Get a view of this allocation as a MemorySegment (zero-copy).
     */
    public MemorySegment asSegment() {
        return segment.asSlice(offset, (long) capacity);
    }

    /**
     * Fill with a value.
     */
    public void fill(byte value) {
        asSegment().fill(value);
    }

    @Override
    public String toString() {
        return "AllocationHandle(offset=" + offset + ", capacity=" + capacity + ", sizeClass=" + sizeClass + ")";
    }
}
