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
 * Optional key serializer that can match keys without allocating temporary byte arrays.
 */
public interface FastKeySerializer<K> extends KeySerializer<K> {

    /**
     * Match a key directly against off-heap bytes without allocations.
     */
    boolean matchesFast(K key, MemorySegment segment, long offset, int length);

    @Override
    default boolean matches(K key, MemorySegment segment, long offset, int length) {
        return matchesFast(key, segment, offset, length);
    }
}
