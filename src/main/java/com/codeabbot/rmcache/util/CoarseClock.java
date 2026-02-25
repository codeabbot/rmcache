package com.codeabbot.rmcache.util;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Coarse-grained clock for TTL calculations.
 * H6 fix: Uses reference counting to start/stop the clock thread
 * automatically when cache instances are created/closed.
 *
 * @author Rabindra Meher
 */
public final class CoarseClock {
    private static volatile long now = System.currentTimeMillis();
    private static volatile ScheduledExecutorService clockExecutor;
    private static final AtomicInteger refCount = new AtomicInteger(0);
    private static final Object LOCK = new Object();

    private CoarseClock() {
    }

    /**
     * Called when a new cache instance is created.
     * Starts the clock thread on first reference.
     */
    public static void acquire() {
        if (refCount.incrementAndGet() == 1) {
            synchronized (LOCK) {
                if (clockExecutor == null || clockExecutor.isShutdown()) {
                    clockExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                        Thread t = new Thread(r, "rmcache-coarse-clock");
                        t.setDaemon(true);
                        return t;
                    });
                    clockExecutor.scheduleAtFixedRate(
                            () -> now = System.currentTimeMillis(),
                            50, 50, TimeUnit.MILLISECONDS);
                }
            }
        }
    }

    /**
     * Called when a cache instance is closed.
     * Stops the clock thread when the last reference is released.
     */
    public static void release() {
        // C5 fix: Perform decrement inside synchronized to prevent race where
        // concurrent acquire() starts the executor between our decrement and
        // the shutdown, causing the newly started executor to be killed.
        synchronized (LOCK) {
            if (refCount.decrementAndGet() == 0 && clockExecutor != null) {
                clockExecutor.shutdown();
                clockExecutor = null;
            }
        }
    }

    public static long getNow() {
        return now;
    }

    public static long currentTimeMillis() {
        return now;
    }

    /**
     * Shutdown the clock executor. Should be called during application shutdown
     * or when the last cache instance is closed to prevent thread leaks.
     */
    public static void shutdown() {
        synchronized (LOCK) {
            if (clockExecutor != null) {
                clockExecutor.shutdown();
                clockExecutor = null;
            }
            refCount.set(0);
        }
    }
}
