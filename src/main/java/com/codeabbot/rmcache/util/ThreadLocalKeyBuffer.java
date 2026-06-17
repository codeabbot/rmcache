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

/**
 * Thread-local key buffer for zero-allocation string encoding.
 * 
 * @author Rabindra Meher
 */
public final class ThreadLocalKeyBuffer {

    private static final int DEFAULT_CAPACITY = 4096;

    private static final ThreadLocal<byte[]> bufferHolder = ThreadLocal.withInitial(() -> new byte[DEFAULT_CAPACITY]);

    public record BufferResult(byte[] buffer, int length) {
    }

    private ThreadLocalKeyBuffer() {
    }

    /**
     * Encode a String key to bytes without allocation.
     * Returns a BufferResult wrapping the thread-local buffer (reused across calls).
     *
     * @return BufferResult containing the (reused) buffer and actual length.
     */
    public static BufferResult encodeString(String key) {
        int len = encodeStringFast(key);
        return new BufferResult(bufferHolder.get(), len);
    }

    /**
     * Zero-allocation string encoding. Returns the encoded length.
     * Caller must use {@link #getBuffer()} to access the encoded bytes.
     * The buffer is thread-local and reused across calls.
     */
    public static int encodeStringFast(String key) {
        int len = key.length();
        byte[] buffer = bufferHolder.get();

        if (len > buffer.length) {
            buffer = new byte[len * 2];
            bufferHolder.set(buffer);
        }

        int bits = 0;
        for (int i = 0; i < len; i++) {
            char c = key.charAt(i);
            bits |= c;
            buffer[i] = (byte) c;
        }
        // A4: a char above U+00FF cannot be represented in one Latin-1 byte. The
        // old code truncated it silently, letting distinct keys collide
        // (e.g. 'U+0100' and '' both encode to 0x00) -- a silent
        // wrong-value bug. Reject instead. The OR-accumulate keeps the loop
        // branch-free; the check runs once per call (free for ASCII keys).
        if ((bits & 0xFF00) != 0) {
            throw new IllegalArgumentException(
                    "LATIN1 key encoding requires ISO-8859-1 characters (<= U+00FF); "
                            + "use StringEncoding.UTF8 for keys containing higher code points");
        }

        return len;
    }

    public static byte[] getBuffer() {
        return bufferHolder.get();
    }

    public static int encodeStringTo(String key, byte[] buffer) {
        int len = Math.min(key.length(), buffer.length);
        int bits = 0;
        for (int i = 0; i < len; i++) {
            char c = key.charAt(i);
            bits |= c;
            buffer[i] = (byte) c;
        }
        // A4: reject non-Latin-1 chars rather than truncating them (see encodeStringFast).
        if ((bits & 0xFF00) != 0) {
            throw new IllegalArgumentException(
                    "LATIN1 key encoding requires ISO-8859-1 characters (<= U+00FF)");
        }
        return len;
    }

    /**
     * Release the thread-local buffer for the calling thread.
     * Call from {@link com.codeabbot.rmcache.OffHeapCache#cleanupThreadLocals()}
     * to prevent memory leaks in app-server thread pools.
     */
    public static void cleanup() {
        bufferHolder.remove();
    }
}
