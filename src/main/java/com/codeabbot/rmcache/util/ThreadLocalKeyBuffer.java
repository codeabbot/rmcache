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
     * 
     * @return BufferResult containing the (reused) buffer and actual length.
     */
    public static BufferResult encodeString(String key) {
        int len = key.length();
        byte[] buffer = bufferHolder.get();

        if (len > buffer.length) {
            buffer = new byte[len * 2];
            bufferHolder.set(buffer);
        }

        for (int i = 0; i < len; i++) {
            buffer[i] = (byte) key.charAt(i);
        }

        return new BufferResult(buffer, len);
    }

    public static byte[] getBuffer() {
        return bufferHolder.get();
    }

    public static int encodeStringTo(String key, byte[] buffer) {
        int len = Math.min(key.length(), buffer.length);
        for (int i = 0; i < len; i++) {
            buffer[i] = (byte) key.charAt(i);
        }
        return len;
    }
}
