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
package com.codeabbot.rmcache.serializer;

import java.lang.foreign.MemorySegment;

/**
 * Serializer that writes values <b>directly</b> into off-heap memory — a zero-copy fast path
 * that avoids an intermediate {@code byte[]}.
 *
 * <p><b>Trusted extension point.</b> For performance, {@link #serializeTo} receives an
 * <em>unbounded</em> native destination and runs without per-write bounds checks (much like a
 * custom allocator hook). An implementation <b>must not write more than {@code maxLen}
 * bytes</b>; writing beyond it corrupts adjacent off-heap memory and can crash the JVM. The
 * built-in serializers honor this by construction. When developing or running untrusted
 * serializers, set {@code CacheBuilder.strictSegmentSerializerBounds(true)} to run custom
 * serializers against a {@code maxLen}-bounded slice, so an over-write throws
 * {@link IndexOutOfBoundsException} instead of corrupting memory (at a small per-write cost).
 */
public interface SegmentValueSerializer<V> extends StreamingSerializer<V> {
    /**
     * Write value into off-heap memory, starting at {@code offset} and writing at most
     * {@code maxLen} bytes. Implementations MUST NOT write past {@code offset + maxLen}.
     *
     * @param value  value to serialize
     * @param dest   destination segment (the cache passes the unbounded native segment unless
     *               {@code strictSegmentSerializerBounds} is enabled, in which case a bounded
     *               slice is passed)
     * @param offset destination offset
     * @param maxLen maximum allowed bytes — writing beyond this is a contract violation
     * @return actual bytes written (must be {@literal <=} maxLen)
     */
    int serializeTo(V value, MemorySegment dest, long offset, int maxLen);
}
