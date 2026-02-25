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
     * @deprecated Use {@link #encodeStringFast(String)} + {@link #getBuffer()} for
     *             zero-allocation.
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

        for (int i = 0; i < len; i++) {
            buffer[i] = (byte) key.charAt(i);
        }

        return len;
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
