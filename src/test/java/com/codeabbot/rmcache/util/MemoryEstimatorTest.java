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
