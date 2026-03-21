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
 * Serializer that can write values directly into off-heap memory.
 */
public interface SegmentValueSerializer<V> extends StreamingSerializer<V> {
    /**
     * Write value into off-heap memory.
     *
     * @param value  value to serialize
     * @param dest   destination segment (typically NativeMemory.UNLIMITED)
     * @param offset destination offset
     * @param maxLen maximum allowed bytes
     * @return actual bytes written (must be {@literal <=} maxLen)
     */
    int serializeTo(V value, MemorySegment dest, long offset, int maxLen);
}
