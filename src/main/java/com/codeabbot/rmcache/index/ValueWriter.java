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
     * @return actual bytes written (must be <= maxLen)
     */
    int write(MemorySegment segment, long offset, int maxLen);
}
