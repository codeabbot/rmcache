package com.codeabbot.rmcache;

import com.codeabbot.rmcache.index.EntryPool;
import com.codeabbot.rmcache.memory.NativeMemory;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Zero-copy view into a cached value.
 *
 * @author Rabindra Meher
 */
public interface CacheValueView extends AutoCloseable {

    /** Size of the value in bytes. */
    int size();

    /**
     * Direct read-only access to the off-heap memory segment.
     *
     * <p>
     * <b>WARNING: UNSAFE.</b> The returned segment points directly into off-heap
     * memory that may be freed if the entry is evicted or the cache is closed.
     * Do NOT hold this reference beyond the immediate scope of your read.
     * Accessing a freed segment causes a JVM crash (SIGSEGV).
     */
    MemorySegment segment();

    /** Copy the value to a byte array. */
    byte[] toByteArray();

    /** Copy the value to an output stream without intermediate heap allocation. */
    void copyTo(OutputStream output, int bufferSize) throws IOException;

    default void copyTo(OutputStream output) throws IOException {
        copyTo(output, 8192);
    }

    /** Copy a portion of the value to a byte array. */
    byte[] slice(int offset, int length);

    /** Check if the view is still valid. */
    boolean isValid();

    /** Read a single byte at the given offset. */
    byte getByte(int offset);

    /** Read an int at the given offset. */
    int getInt(int offset);

    /** Read a long at the given offset. */
    long getLong(int offset);

    @Override
    void close();
}
