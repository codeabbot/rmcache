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
package com.codeabbot.rmcache.util;

import com.codeabbot.rmcache.memory.NativeMemory;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Memory prefetching utilities for JDK 25+ FFM API.
 *
 * @author Rabindra Meher
 */
public final class Prefetch {
    private static final long CACHE_LINE_SIZE = 64L;

    private Prefetch() {
    }

    public static void prefetchRead(MemorySegment segment, long offset) {
        if (offset >= 0 && offset < segment.byteSize()) {
            segment.get(ValueLayout.JAVA_BYTE, offset);
        }
    }

    public static void prefetchReadAddr(long address) {
        if (address != 0L) {
            NativeMemory.UNLIMITED.get(ValueLayout.JAVA_BYTE, address);
        }
    }

    public static void prefetchRange(MemorySegment segment, long startOffset, int byteCount) {
        long endOffset = Math.min(startOffset + byteCount, segment.byteSize());
        for (long offset = startOffset; offset < endOffset; offset += CACHE_LINE_SIZE) {
            segment.get(ValueLayout.JAVA_BYTE, offset);
        }
    }

    public static void prefetchNextSlots(long tableAddr, int currentIndex, int mask, int slotSize, int lookahead) {
        int nextIdx1 = (currentIndex + 1) & mask;
        long addr1 = tableAddr + ((long) nextIdx1 * slotSize);
        prefetchReadAddr(addr1);

        if (lookahead > 1) {
            int nextIdx2 = (currentIndex + 2) & mask;
            long addr2 = tableAddr + ((long) nextIdx2 * slotSize);
            prefetchReadAddr(addr2);
        }
    }

    public static void prefetchNextSlots(long tableAddr, int currentIndex, int mask) {
        prefetchNextSlots(tableAddr, currentIndex, mask, 8, 2);
    }

    public static void prefetchNextSlots(long tableAddr, int currentIndex, int mask, int lookahead) {
        prefetchNextSlots(tableAddr, currentIndex, mask, 8, lookahead);
    }
}
