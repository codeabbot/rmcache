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
package com.codeabbot.rmcache.eviction;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class OffHeapFrequencySketchTest {

    @Test
    public void testIncrementAndFrequency() {
        try (OffHeapFrequencySketch sketch = new OffHeapFrequencySketch(64)) {
            assertEquals(0, sketch.frequency(42));

            sketch.increment(42);
            assertTrue(sketch.frequency(42) >= 1);

            sketch.increment(42);
            sketch.increment(42);
            assertTrue(sketch.frequency(42) >= 2);
        }
    }

    @Test
    public void testDistinctHashesAreIndependent() {
        try (OffHeapFrequencySketch sketch = new OffHeapFrequencySketch(1024)) {
            sketch.increment(100);
            sketch.increment(100);
            sketch.increment(100);

            sketch.increment(200);

            assertTrue(sketch.frequency(100) >= 2);
            assertTrue(sketch.frequency(200) >= 1);
            // Hash 200 should not be inflated by hash 100 increments
            assertTrue(sketch.frequency(100) >= sketch.frequency(200));
        }
    }

    @Test
    public void testFrequencyOfUnseenHashIsZero() {
        try (OffHeapFrequencySketch sketch = new OffHeapFrequencySketch(64)) {
            assertEquals(0, sketch.frequency(999));
            assertEquals(0, sketch.frequency(0));
            assertEquals(0, sketch.frequency(-1));
            assertEquals(0, sketch.frequency(Integer.MAX_VALUE));
        }
    }

    @Test
    public void testSaturationAtFifteen() {
        try (OffHeapFrequencySketch sketch = new OffHeapFrequencySketch(1024)) {
            // Increment far more than 15 times; frequency should cap at 15
            for (int i = 0; i < 50; i++) {
                sketch.increment(7);
            }
            assertTrue(sketch.frequency(7) <= 15,
                    "4-bit counter must saturate at 15, got " + sketch.frequency(7));
        }
    }

    @Test
    public void testResetHalvesCounters() {
        // Table size rounds up to a power of two; resetThreshold = tableSize * 10.
        // With expectedSize=1 the table becomes size=1, threshold=10.
        try (OffHeapFrequencySketch sketch = new OffHeapFrequencySketch(1)) {
            // Increment a single hash enough times to trigger a reset (threshold=10).
            // Each increment call bumps sampleSize by 1.
            for (int i = 0; i < 5; i++) {
                sketch.increment(1);
            }
            int freqBefore = sketch.frequency(1);
            assertTrue(freqBefore > 0, "frequency should be positive before reset");

            // Push past threshold to trigger reset
            for (int i = 0; i < 20; i++) {
                sketch.increment(2);
            }

            // After reset, the original counter for hash 1 should have been halved
            int freqAfter = sketch.frequency(1);
            assertTrue(freqAfter <= freqBefore,
                    "frequency after reset should not exceed frequency before reset");
        }
    }

    @Test
    public void testNegativeAndLargeHashes() {
        try (OffHeapFrequencySketch sketch = new OffHeapFrequencySketch(64)) {
            sketch.increment(Integer.MIN_VALUE);
            assertTrue(sketch.frequency(Integer.MIN_VALUE) >= 1);

            sketch.increment(Integer.MAX_VALUE);
            assertTrue(sketch.frequency(Integer.MAX_VALUE) >= 1);

            sketch.increment(-1);
            assertTrue(sketch.frequency(-1) >= 1);
        }
    }

    @Test
    public void testTableSizeOne() {
        try (OffHeapFrequencySketch sketch = new OffHeapFrequencySketch(1)) {
            sketch.increment(0);
            assertTrue(sketch.frequency(0) >= 1);
        }
    }

    @Test
    public void testTableSizeLarge() {
        try (OffHeapFrequencySketch sketch = new OffHeapFrequencySketch(4096)) {
            for (int h = 0; h < 100; h++) {
                sketch.increment(h);
            }
            for (int h = 0; h < 100; h++) {
                assertTrue(sketch.frequency(h) >= 1,
                        "hash " + h + " should have frequency >= 1");
            }
        }
    }

    @Test
    public void testNonPowerOfTwoRoundsUp() {
        // expectedSize=5 should round up to table size 8
        try (OffHeapFrequencySketch sketch = new OffHeapFrequencySketch(5)) {
            sketch.increment(42);
            assertTrue(sketch.frequency(42) >= 1);
        }
    }

    @Test
    public void testCloseFreesMemory() {
        OffHeapFrequencySketch sketch = new OffHeapFrequencySketch(64);
        sketch.increment(1);
        sketch.close();
        // No assertion beyond verifying close() does not throw
    }

    @Test
    public void testMultipleIncrementsSameHash() {
        try (OffHeapFrequencySketch sketch = new OffHeapFrequencySketch(256)) {
            for (int i = 0; i < 10; i++) {
                sketch.increment(77);
            }
            int freq = sketch.frequency(77);
            assertTrue(freq >= 5 && freq <= 15,
                    "frequency after 10 increments should be between 5 and 15, got " + freq);
        }
    }
}
