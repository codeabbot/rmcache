package com.codeabbot.rmcache.memory;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * A lightweight handle to an allocated memory block.
 *
 * @author Rabindra Meher
 */
public class AllocationHandle {
    private final MemorySegment segment;
    private final long offset;
    private int capacity;
    private final int sizeClass;

    public AllocationHandle(MemorySegment segment, long offset, int capacity, int sizeClass) {
        this.segment = segment;
        this.offset = offset;
        this.capacity = capacity;
        this.sizeClass = sizeClass;
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
