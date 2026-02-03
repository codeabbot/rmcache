package com.codeabbot.rmcache.util;

/**
 * Thread-local value buffer for zero-allocation value handling.
 */
public final class ThreadLocalValueBuffer {

    private static final int DEFAULT_CAPACITY = 256 * 1024; // 256KB

    private static final ThreadLocal<byte[]> bufferHolder =
            ThreadLocal.withInitial(() -> new byte[DEFAULT_CAPACITY]);

    private ThreadLocalValueBuffer() {
    }

    public static byte[] getBuffer() {
        return bufferHolder.get();
    }

    public static byte[] getBuffer(int minSize) {
        byte[] buffer = bufferHolder.get();
        if (minSize > buffer.length) {
            buffer = new byte[minSize];
            bufferHolder.set(buffer);
        }
        return buffer;
    }
}
