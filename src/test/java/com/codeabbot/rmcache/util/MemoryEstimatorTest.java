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

import com.codeabbot.rmcache.GhostCacheMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MemoryEstimatorTest {

    @Test
    public void estimateProvidesReasonableTotals() {
        MemoryEstimator.MemoryEstimate estimate = MemoryEstimator.estimate(
                1_000_000,
                16,
                256,
                1024,
                128,
                0.6d,
                8192,
                GhostCacheMode.OFF_HEAP,
                0.1d);

        assertTrue(estimate.hashTableSlots() >= 1_000_000);
        assertTrue(estimate.totalBytes() > estimate.dataBytes());
        assertTrue(estimate.bytesPerEntry() > estimate.entrySizeBytes());
    }

    @Test
    public void deriveIndexSizingRejectsTooSmallBudget() {
        assertThrows(IllegalArgumentException.class, () -> MemoryEstimator.deriveIndexSizing(
                1_000_000,
                1024,
                128,
                1_024,
                0,
                GhostCacheMode.DISABLED,
                0.75d));
    }
}
