package com.codeabbot.rmcache;

import com.codeabbot.rmcache.index.EntryPool;
import com.codeabbot.rmcache.memory.NativeMemory;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Internal implementation of CacheValueView.
 */
public class CacheValueViewImpl implements CacheValueView {

    private final EntryPool entryPool;
    private final int slot;
    private final long valueOffset;
    private final int valueLen;
    private final Runnable onClose;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public CacheValueViewImpl(EntryPool entryPool, int slot, long valueOffset, int valueLen, Runnable onClose) {
        this.entryPool = entryPool;
        this.slot = slot;
        this.valueOffset = valueOffset;
        this.valueLen = valueLen;
        this.onClose = onClose;
    }

    public CacheValueViewImpl(EntryPool entryPool, int slot, long valueOffset, int valueLen) {
        this(entryPool, slot, valueOffset, valueLen, () -> {
        });
    }

    @Override
    public int size() {
        return valueLen;
    }

    @Override
    public MemorySegment segment() {
        checkValid();
        return NativeMemory.UNLIMITED.asSlice(valueOffset, (long) valueLen);
    }

    @Override
    public byte[] toByteArray() {
        checkValid();
        byte[] bytes = new byte[valueLen];
        MemorySegment.copy(NativeMemory.UNLIMITED, ValueLayout.JAVA_BYTE, valueOffset, bytes, 0, valueLen);
        return bytes;
    }

    @Override
    public void copyTo(OutputStream output, int bufferSize) throws IOException {
        checkValid();
        byte[] buffer = new byte[Math.min(bufferSize, valueLen)];
        int offset = 0;
        while (offset < valueLen) {
            int len = Math.min(bufferSize, valueLen - offset);
            MemorySegment.copy(NativeMemory.UNLIMITED, ValueLayout.JAVA_BYTE, valueOffset + offset, buffer, 0, len);
            output.write(buffer, 0, len);
            offset += len;
        }
    }

    @Override
    public byte[] slice(int offset, int length) {
        checkValid();
        // AUDIT-C4: Use long arithmetic to prevent int overflow bypass.
        // offset + length can wrap negative when both are large positive ints.
        if (offset < 0 || length < 0 || (long) offset + length > valueLen) {
            throw new IllegalArgumentException("Slice exceeds value bounds");
        }
        byte[] bytes = new byte[length];
        MemorySegment.copy(NativeMemory.UNLIMITED, ValueLayout.JAVA_BYTE, valueOffset + offset, bytes, 0, length);
        return bytes;
    }

    @Override
    public boolean isValid() {
        return !closed.get();
    }

    @Override
    public byte getByte(int offset) {
        checkValid();
        checkBounds(offset, 1);
        return NativeMemory.UNLIMITED.get(ValueLayout.JAVA_BYTE, valueOffset + offset);
    }

    @Override
    public int getInt(int offset) {
        checkValid();
        checkBounds(offset, 4);
        return NativeMemory.UNLIMITED.get(ValueLayout.JAVA_INT_UNALIGNED, valueOffset + offset);
    }

    @Override
    public long getLong(int offset) {
        checkValid();
        checkBounds(offset, 8);
        return NativeMemory.UNLIMITED.get(ValueLayout.JAVA_LONG_UNALIGNED, valueOffset + offset);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            onClose.run();
        }
    }

    private void checkBounds(int offset, int size) {
        // AUDIT-C4: Use long arithmetic to prevent int overflow bypass.
        if (offset < 0 || (long) offset + size > valueLen) {
            throw new IndexOutOfBoundsException(
                    "offset=" + offset + " size=" + size + " exceeds value length " + valueLen);
        }
    }

    private void checkValid() {
        if (closed.get())
            throw new IllegalStateException("CacheValueView has been closed");
        // P2-C1 fix: Detect if underlying entry was freed by eviction or realloc.
        // Without this, segment()/toByteArray()/getByte() silently read freed memory.
        if (entryPool.getOffset(slot) == -1L)
            throw new IllegalStateException("CacheValueView's underlying entry has been evicted or reallocated");
    }
}
