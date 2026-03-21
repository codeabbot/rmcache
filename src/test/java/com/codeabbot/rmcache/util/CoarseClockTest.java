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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class CoarseClockTest {

    @AfterEach
    public void cleanup() {
        CoarseClock.shutdown();
    }

    @Test
    public void testAcquireReleaseCycle() {
        CoarseClock.acquire();
        long now = CoarseClock.getNow();
        assertTrue(now > 0, "getNow() should return a positive value after acquire");
        CoarseClock.release();
    }

    @Test
    public void testGetNowReturnsReasonableTime() {
        CoarseClock.acquire();
        long now = CoarseClock.getNow();
        long system = System.currentTimeMillis();

        // The coarse clock updates every 50ms; allow 200ms tolerance
        assertTrue(Math.abs(system - now) < 200,
                "getNow() should be within 200ms of System.currentTimeMillis(), delta="
                        + Math.abs(system - now));
        CoarseClock.release();
    }

    @Test
    public void testCurrentTimeMillisAlias() {
        CoarseClock.acquire();
        long a = CoarseClock.getNow();
        long b = CoarseClock.currentTimeMillis();
        // Both should return the same volatile field
        assertTrue(Math.abs(a - b) < 100,
                "getNow() and currentTimeMillis() should be close");
        CoarseClock.release();
    }

    @Test
    public void testMultipleAcquireRelease() {
        CoarseClock.acquire();
        CoarseClock.acquire();
        long now = CoarseClock.getNow();
        assertTrue(now > 0);

        CoarseClock.release();
        // Clock should still be running (refcount=1)
        long afterFirst = CoarseClock.getNow();
        assertTrue(afterFirst > 0);

        CoarseClock.release();
        // refcount=0, executor shut down
    }

    @Test
    public void testShutdownResetsEverything() {
        CoarseClock.acquire();
        CoarseClock.acquire();
        CoarseClock.shutdown();
        // After shutdown, re-acquire should work cleanly
        CoarseClock.acquire();
        long now = CoarseClock.getNow();
        assertTrue(now > 0);
        CoarseClock.release();
    }

    @Test
    public void testGetNowBeforeAcquire() {
        // getNow() returns the static volatile field which was initialized at class-load time
        long now = CoarseClock.getNow();
        assertTrue(now > 0, "Even before acquire, getNow() should return a positive value");
    }
}
