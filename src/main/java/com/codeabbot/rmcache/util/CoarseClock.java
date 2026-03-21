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
     *
     * ISSUE-004 fix: Increment refCount inside synchronized block to prevent
     * race with release(). Previously, release() could shut down the executor
     * between our increment (outside lock) and our synchronized block entry.
     */
    public static void acquire() {
        synchronized (LOCK) {
            if (refCount.incrementAndGet() == 1) {
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
