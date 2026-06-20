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
package com.codeabbot.rmcache;

import com.codeabbot.rmcache.index.EntryPool;
import com.codeabbot.rmcache.memory.NativeMemory;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Zero-copy view into a cached value's off-heap memory.
 *
 * <p><b>EXPERIMENTAL — Use-after-free risk.</b> This view holds a raw pointer
 * into off-heap memory. If the underlying entry is evicted, removed, or
 * reallocated (e.g., value resize) while the view is open, reads through
 * this view may return corrupt data or crash the JVM (SIGSEGV).
 *
 * <p><b>Safe usage pattern:</b>
 * <pre>{@code
 * try (CacheValueView view = cache.getView(key)) {
 *     if (view != null && view.isValid()) {
 *         byte[] data = view.toByteArray(); // copy immediately
 *     }
 * }
 * // Do NOT store the view or pass it to another thread.
 * }</pre>
 *
 * <p>The {@link #isValid()} check detects this view being closed and simple
 * prior removals/evictions where the slot is currently free. It cannot prove
 * that the slot still contains the original value: slot reuse (ABA), cache
 * close, and concurrent eviction/update during a read remain unsafe. For
 * absolute safety, use {@link OffHeapCache#get(Object)} which copies the value
 * to the Java heap.
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
