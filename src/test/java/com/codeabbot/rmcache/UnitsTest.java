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
package com.codeabbot.rmcache;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

public class UnitsTest {

    // ---- long overloads ----

    @Test
    public void testKilobytesLong() {
        assertEquals(1024L, Units.kilobytes(1L));
        assertEquals(10240L, Units.kilobytes(10L));
        assertEquals(0L, Units.kilobytes(0L));
    }

    @Test
    public void testMegabytesLong() {
        assertEquals(1024L * 1024L, Units.megabytes(1L));
        assertEquals(64L * 1024 * 1024, Units.megabytes(64L));
        assertEquals(0L, Units.megabytes(0L));
    }

    @Test
    public void testGigabytesLong() {
        assertEquals(1024L * 1024 * 1024, Units.gigabytes(1L));
        assertEquals(4L * 1024 * 1024 * 1024, Units.gigabytes(4L));
        assertEquals(0L, Units.gigabytes(0L));
    }

    // ---- int overloads ----

    @Test
    public void testKilobytesInt() {
        assertEquals(1024L, Units.kilobytes(1));
        assertEquals(10240L, Units.kilobytes(10));
        assertEquals(0L, Units.kilobytes(0));
    }

    @Test
    public void testMegabytesInt() {
        assertEquals(1024L * 1024, Units.megabytes(1));
        assertEquals(64L * 1024 * 1024, Units.megabytes(64));
    }

    @Test
    public void testGigabytesInt() {
        assertEquals(1024L * 1024 * 1024, Units.gigabytes(1));
        assertEquals(4L * 1024 * 1024 * 1024, Units.gigabytes(4));
    }

    @Test
    public void testGigabytesIntNoOverflow() {
        // int 4 * 1024^3 would overflow int; the method casts to long first
        long result = Units.gigabytes(4);
        assertTrue(result > Integer.MAX_VALUE,
                "4 GB should exceed Integer.MAX_VALUE");
    }

    // ---- Duration helpers ----

    @Test
    public void testSecondsLong() {
        assertEquals(Duration.ofSeconds(30), Units.seconds(30L));
    }

    @Test
    public void testMinutesLong() {
        assertEquals(Duration.ofMinutes(5), Units.minutes(5L));
    }

    @Test
    public void testHoursLong() {
        assertEquals(Duration.ofHours(2), Units.hours(2L));
    }

    @Test
    public void testSecondsInt() {
        assertEquals(Duration.ofSeconds(30), Units.seconds(30));
    }

    @Test
    public void testMinutesInt() {
        assertEquals(Duration.ofMinutes(5), Units.minutes(5));
    }

    @Test
    public void testHoursInt() {
        assertEquals(Duration.ofHours(2), Units.hours(2));
    }
}
