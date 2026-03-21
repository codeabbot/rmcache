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
package com.codeabbot.rmcache.index;

import java.lang.foreign.MemorySegment;

/**
 * Writes a value directly into off-heap memory.
 */
@FunctionalInterface
public interface ValueWriter {
    /**
     * @param segment destination segment (typically NativeMemory.UNLIMITED)
     * @param offset  destination offset
     * @param maxLen  maximum bytes allowed
     * @return actual bytes written (must be {@literal <=} maxLen)
     */
    int write(MemorySegment segment, long offset, int maxLen);
}
