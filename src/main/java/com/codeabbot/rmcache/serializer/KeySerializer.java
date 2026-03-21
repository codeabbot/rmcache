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
import java.lang.foreign.ValueLayout;

/**
 * Interface for key serialization and matching.
 */
public interface KeySerializer<K> {
    byte[] serialize(K key);

    K deserialize(byte[] bytes);

    int hashCode(K key);

    default boolean matches(K key, MemorySegment segment, long offset, int length) {
        byte[] bytes = serialize(key);
        if (bytes.length != length)
            return false;
        return segment.asSlice(offset, (long) length).mismatch(MemorySegment.ofArray(bytes)) == -1L;
    }

    default int hashCode(MemorySegment segment, long offset, int length) {
        int h = 0;
        for (int i = 0; i < length; i++) {
            h = 31 * h + segment.get(ValueLayout.JAVA_BYTE, offset + i);
        }
        return h;
    }
}
