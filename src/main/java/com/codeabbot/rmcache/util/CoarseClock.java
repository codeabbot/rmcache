package com.codeabbot.rmcache.util;

/**
 * Coarse-grained clock for TTL calculations.
 *
 * @author Rabindra Meher
 */
public final class CoarseClock {
    private static volatile long now = System.currentTimeMillis();

    static {
        Thread.ofVirtual().start(() -> {
            while (true) {
                try {
                    Thread.sleep(100);
                    now = System.currentTimeMillis();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }

    private CoarseClock() {
    }

    public static long getNow() {
        return now;
    }

    public static long currentTimeMillis() {
        return now;
    }
}
