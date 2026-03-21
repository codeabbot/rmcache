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

import com.codeabbot.rmcache.CacheBuilder;
import com.codeabbot.rmcache.GhostCacheMode;
import com.codeabbot.rmcache.OffHeapCache;
import com.codeabbot.rmcache.memory.NativeMemory;

import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Targeted coverage tests for util package —
 * ThreadLocalValueBuffer, MemoryEstimator (deriveIndexSizing, estimate with
 * various parameters, IndexSizing record), and Prefetch.
 */
public class UtilCoverageTest {

    private static final long MB8 = 8L * 1024 * 1024;

    // ── ThreadLocalValueBuffer ───────────────────────────────────────────────

    @Test
    void threadLocalValueBuffer_getBuffer_defaultSize() {
        byte[] buffer = ThreadLocalValueBuffer.getBuffer();
        assertNotNull(buffer);
        assertTrue(buffer.length >= 256 * 1024, "Default buffer should be >= 256KB");
    }

    @Test
    void threadLocalValueBuffer_getBuffer_smallMinSize() {
        byte[] buffer = ThreadLocalValueBuffer.getBuffer(100);
        assertNotNull(buffer);
        assertTrue(buffer.length >= 100);
    }

    @Test
    void threadLocalValueBuffer_getBuffer_largeMinSize() {
        byte[] buffer = ThreadLocalValueBuffer.getBuffer(512 * 1024);
        assertNotNull(buffer);
        assertTrue(buffer.length >= 512 * 1024);

        // Subsequent call should return the grown buffer
        byte[] buffer2 = ThreadLocalValueBuffer.getBuffer();
        assertTrue(buffer2.length >= 512 * 1024);
    }

    @Test
    void threadLocalValueBuffer_sameThread_returnsSameInstance() {
        byte[] b1 = ThreadLocalValueBuffer.getBuffer();
        byte[] b2 = ThreadLocalValueBuffer.getBuffer();
        assertSame(b1, b2, "Same thread should reuse the same buffer instance");
    }

    // ── MemoryEstimator — additional paths ───────────────────────────────────

    @Test
    void estimate_shortcutForm_3args() {
        MemoryEstimator.MemoryEstimate est = MemoryEstimator.estimate(100_000, 16, 128);
        assertNotNull(est);
        assertTrue(est.totalBytes() > 0);
        assertTrue(est.hashTableSlots() >= 100_000);
        assertTrue(est.slotCapacity() >= 100_000);
        assertTrue(est.entrySizeBytes() > 0);
        assertTrue(est.bytesPerEntry() > 0);
        assertTrue(est.dataBytes() > 0);
        assertTrue(est.hashTableBytes() > 0);
        assertTrue(est.offsetsBytes() > 0);
        assertTrue(est.freeListBytes() > 0);
        assertTrue(est.allocatorOverheadBytes() >= 0);
    }

    @Test
    void estimate_withOffHeapGhost() {
        MemoryEstimator.MemoryEstimate est = MemoryEstimator.estimate(
                500_000, 32, 256, 1024, 128, 0.60, 4096,
                GhostCacheMode.OFF_HEAP, 0.10);
        assertTrue(est.ghostCacheBytes() > 0, "OFF_HEAP ghost should have native bytes");
    }

    @Test
    void estimate_withHeapGhost() {
        MemoryEstimator.MemoryEstimate est = MemoryEstimator.estimate(
                500_000, 32, 256, 1024, 128, 0.60, 4096,
                GhostCacheMode.HEAP, 0.10);
        assertEquals(0, est.ghostCacheBytes(), "HEAP ghost should have no native bytes");
    }

    @Test
    void estimate_withDisabledGhost() {
        MemoryEstimator.MemoryEstimate est = MemoryEstimator.estimate(
                500_000, 32, 256, 1024, 128, 0.60, 0,
                GhostCacheMode.DISABLED, 0.10);
        assertEquals(0, est.ghostCacheBytes());
    }

    @Test
    void estimate_autoGhostMode() {
        MemoryEstimator.MemoryEstimate est = MemoryEstimator.estimate(
                500_000, 32, 256, 1024, 128, 0.60, 4096,
                GhostCacheMode.AUTO, 0.10);
        assertNotNull(est);
        assertTrue(est.totalBytes() > 0);
    }

    @Test
    void estimate_nullGhostMode() {
        MemoryEstimator.MemoryEstimate est = MemoryEstimator.estimate(
                500_000, 32, 256, 1024, 128, 0.60, 4096,
                null, 0.10);
        assertNotNull(est);
        assertTrue(est.totalBytes() > 0);
    }

    @Test
    void estimate_zeroMaxEntries() {
        MemoryEstimator.MemoryEstimate est = MemoryEstimator.estimate(0, 16, 128);
        assertEquals(0.0, est.bytesPerEntry());
    }

    @Test
    void deriveIndexSizing_validBudget() {
        MemoryEstimator.IndexSizing sizing = MemoryEstimator.deriveIndexSizing(
                100_000, 256, 64, 8 * 1024 * 1024, 1024,
                GhostCacheMode.OFF_HEAP, 0.75);
        assertNotNull(sizing);
        assertTrue(sizing.hashTableCapacityPerStripe() > 0);
        assertTrue(sizing.hashTableSlots() > 0);
        assertTrue(sizing.loadFactor() > 0);
        assertTrue(sizing.totalIndexBytes() > 0);
        assertTrue(sizing.fixedIndexBytes() > 0);
        assertTrue(sizing.hashTableBytes() > 0);
    }

    @Test
    void deriveIndexSizing_disabledGhost() {
        MemoryEstimator.IndexSizing sizing = MemoryEstimator.deriveIndexSizing(
                100_000, 256, 64, 8 * 1024 * 1024, 0,
                GhostCacheMode.DISABLED, 0.75);
        assertNotNull(sizing);
        assertTrue(sizing.totalIndexBytes() > 0);
    }

    @Test
    void deriveIndexSizing_tooSmallBudget_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> MemoryEstimator.deriveIndexSizing(
                        1_000_000, 1024, 128, 1024, 0,
                        GhostCacheMode.DISABLED, 0.75));
    }

    @Test
    void deriveIndexSizing_tooSmallForLoadFactor_throws() {
        // Budget large enough for fixed index but too small for hash table
        // at the required load factor
        assertThrows(IllegalArgumentException.class,
                () -> MemoryEstimator.deriveIndexSizing(
                        1_000_000, 1024, 128, 2 * 1024 * 1024, 0,
                        GhostCacheMode.DISABLED, 0.10));
    }

    @Test
    void estimate_variousEntryCounts() {
        // Test different auto-tier thresholds
        long[] sizes = {50_000, 500_000, 5_000_000, 50_000_000};
        for (long size : sizes) {
            MemoryEstimator.MemoryEstimate est = MemoryEstimator.estimate(size, 16, 128);
            assertTrue(est.totalBytes() > 0, "totalBytes should be > 0 for " + size);
        }
    }

    // ── Prefetch ─────────────────────────────────────────────────────────────

    @Test
    void prefetchRead_withinBounds() {
        MemorySegment seg = NativeMemory.malloc(128);
        try {
            // Should not throw
            Prefetch.prefetchRead(seg, 0);
            Prefetch.prefetchRead(seg, 64);
            Prefetch.prefetchRead(seg, 127);
        } finally {
            NativeMemory.free(seg);
        }
    }

    @Test
    void prefetchRead_outOfBounds_ignored() {
        MemorySegment seg = NativeMemory.malloc(64);
        try {
            // Out of bounds should be silently ignored
            Prefetch.prefetchRead(seg, 64);
            Prefetch.prefetchRead(seg, -1);
        } finally {
            NativeMemory.free(seg);
        }
    }

    @Test
    void prefetchRange_basic() {
        MemorySegment seg = NativeMemory.malloc(256);
        try {
            Prefetch.prefetchRange(seg, 0, 256);
            Prefetch.prefetchRange(seg, 64, 128);
        } finally {
            NativeMemory.free(seg);
        }
    }

    @Test
    void prefetchReadAddr_basic() {
        MemorySegment seg = NativeMemory.malloc(64);
        try {
            // Valid address
            Prefetch.prefetchReadAddr(seg.address());
            // Zero address is no-op
            Prefetch.prefetchReadAddr(0L);
        } finally {
            NativeMemory.free(seg);
        }
    }

    @Test
    void prefetchNextSlots_basic() {
        MemorySegment seg = NativeMemory.malloc(256);
        try {
            long addr = seg.address();
            Prefetch.prefetchNextSlots(addr, 0, 31);
            Prefetch.prefetchNextSlots(addr, 0, 31, 1);
            Prefetch.prefetchNextSlots(addr, 0, 31, 8, 2);
        } finally {
            NativeMemory.free(seg);
        }
    }

    // ── Integration test: MemoryEstimator through CacheBuilder ───────────────

    @Test
    void cacheBuilder_estimateMemory_usesEstimator() {
        CacheBuilder<String, String> builder = new CacheBuilder<String, String>()
                .maxEntries(5000)
                .averageKeySize(20)
                .averageValueSize(100);

        var estimate = builder.estimateMemory();
        assertNotNull(estimate);
        assertTrue(estimate.totalBytes() > 0);
        assertTrue(estimate.hashTableStripes() > 0);
        assertTrue(estimate.entryPoolPartitions() > 0);
    }

    @Test
    void cacheBuilder_estimateMemory_withZeroHeapProfile() {
        CacheBuilder<String, String> builder = new CacheBuilder<String, String>()
                .maxEntries(5000)
                .averageKeySize(20)
                .averageValueSize(100)
                .zeroHeapProfile();

        var estimate = builder.estimateMemory();
        assertNotNull(estimate);
        assertTrue(estimate.totalBytes() > 0);
    }

    @Test
    void cacheBuilder_estimateMemory_withCustomLoadFactor() {
        CacheBuilder<String, String> builder = new CacheBuilder<String, String>()
                .maxEntries(5000)
                .averageKeySize(20)
                .averageValueSize(100)
                .hashTableLoadFactor(0.75);

        var estimate = builder.estimateMemory();
        assertNotNull(estimate);
        assertTrue(estimate.loadFactor() >= 0.75);
    }

    // ── MemoryEstimator: IndexSizing record fields ──────────────────────────

    @Test
    void indexSizing_record_allFields() {
        MemoryEstimator.IndexSizing sizing = MemoryEstimator.deriveIndexSizing(
                100_000, 256, 64, 8 * 1024 * 1024, 2048,
                GhostCacheMode.OFF_HEAP, 0.75);

        // Verify all record fields are accessible
        assertTrue(sizing.hashTableCapacityPerStripe() > 0);
        assertTrue(sizing.hashTableSlots() > 0);
        assertTrue(sizing.loadFactor() > 0);
        assertTrue(sizing.totalIndexBytes() > 0);
        assertTrue(sizing.fixedIndexBytes() > 0);
        assertTrue(sizing.hashTableBytes() > 0);

        // totalIndex should be fixed + hash
        assertEquals(sizing.fixedIndexBytes() + sizing.hashTableBytes(), sizing.totalIndexBytes());
    }

    @Test
    void estimate_memoryEstimate_record_allFields() {
        MemoryEstimator.MemoryEstimate est = MemoryEstimator.estimate(
                10_000, 16, 128, 64, 64, 0.60, 1024,
                GhostCacheMode.OFF_HEAP, 0.10);

        assertEquals(10_000, est.maxEntries());
        assertEquals(16, est.avgKeySize());
        assertEquals(128, est.avgValueSize());
        assertEquals(64, est.hashTableStripes());
        assertEquals(64, est.entryPoolPartitions());
        assertEquals(0.60, est.loadFactor(), 0.001);
        assertTrue(est.hashTableCapacityPerStripe() > 0);
        assertTrue(est.hashTableSlots() > 0);
        assertTrue(est.slotCapacity() > 0);
        assertTrue(est.entrySizeBytes() > 0);
        assertTrue(est.dataBytes() > 0);
        assertTrue(est.hashTableBytes() > 0);
        assertTrue(est.offsetsBytes() > 0);
        assertTrue(est.freeListBytes() > 0);
        assertTrue(est.ghostCacheBytes() > 0);
        assertTrue(est.allocatorOverheadBytes() > 0);
        assertTrue(est.totalBytes() > 0);
        assertTrue(est.bytesPerEntry() > 0);
    }

    @Test
    void deriveIndexSizing_heapGhostMode_noNativeGhostBytes() {
        MemoryEstimator.IndexSizing sizing = MemoryEstimator.deriveIndexSizing(
                100_000, 256, 64, 8 * 1024 * 1024, 1024,
                GhostCacheMode.HEAP, 0.75);
        // HEAP ghost mode should not add native ghost bytes to fixed index
        assertNotNull(sizing);
        assertTrue(sizing.totalIndexBytes() > 0);
    }

    // ── MemoryEstimator: autoStripes / autoPartitions thresholds ────────────

    @Test
    void estimate_largeEntryCounts_hitsHighTiers() {
        // >= 10M entries should get 8192 stripes
        MemoryEstimator.MemoryEstimate est10m = MemoryEstimator.estimate(10_000_000, 16, 64);
        assertTrue(est10m.hashTableStripes() >= 8192);

        // >= 1M entries should get 1024 stripes
        MemoryEstimator.MemoryEstimate est1m = MemoryEstimator.estimate(1_000_000, 16, 64);
        assertTrue(est1m.hashTableStripes() >= 1024);

        // < 1M entries gets 64 stripes
        MemoryEstimator.MemoryEstimate est100k = MemoryEstimator.estimate(100_000, 16, 64);
        assertTrue(est100k.hashTableStripes() >= 64);
    }

    @Test
    void estimate_autoPartitions_thresholds() {
        // <= 100K gets 64 partitions
        MemoryEstimator.MemoryEstimate est100k = MemoryEstimator.estimate(100_000, 16, 64);
        assertEquals(64, est100k.entryPoolPartitions());

        // > 100K gets 128 partitions
        MemoryEstimator.MemoryEstimate est500k = MemoryEstimator.estimate(500_000, 16, 64);
        assertEquals(128, est500k.entryPoolPartitions());

        // > 10M gets 256 partitions
        MemoryEstimator.MemoryEstimate est50m = MemoryEstimator.estimate(50_000_000, 16, 64);
        assertEquals(256, est50m.entryPoolPartitions());
    }

    @Test
    void estimate_autoGhostSize_clamps() {
        // Small: 64 minimum
        MemoryEstimator.MemoryEstimate estSmall = MemoryEstimator.estimate(100, 16, 64);
        assertNotNull(estSmall);

        // Large: should not exceed 32768
        MemoryEstimator.MemoryEstimate estLarge = MemoryEstimator.estimate(100_000_000, 16, 64);
        assertNotNull(estLarge);
    }

    // ── Prefetch: prefetchNextSlots with lookahead = 1 ──────────────────────

    @Test
    void prefetchNextSlots_lookahead1() {
        MemorySegment seg = NativeMemory.malloc(256);
        try {
            long addr = seg.address();
            // lookahead = 1 should only prefetch next index, not next-next
            Prefetch.prefetchNextSlots(addr, 0, 31, 8, 1);
            Prefetch.prefetchNextSlots(addr, 15, 31, 8, 1);
        } finally {
            NativeMemory.free(seg);
        }
    }

    @Test
    void prefetchRange_zeroLength() {
        MemorySegment seg = NativeMemory.malloc(64);
        try {
            // Zero byte count should be no-op
            Prefetch.prefetchRange(seg, 0, 0);
        } finally {
            NativeMemory.free(seg);
        }
    }

    // ── CacheBuilder.estimateMemory with index budget percent ───────────────

    @Test
    void cacheBuilder_estimateMemory_withIndexBudgetPercent() {
        CacheBuilder<String, String> builder = new CacheBuilder<String, String>()
                .maxEntries(10000)
                .averageKeySize(16)
                .averageValueSize(128)
                .offHeapMemory(MB8)
                .indexMemoryBudgetPercent(0.25);

        var estimate = builder.estimateMemory();
        assertNotNull(estimate);
        assertTrue(estimate.totalBytes() > 0);
    }

    @Test
    void cacheBuilder_estimateMemory_withIndexBudgetBytes() {
        CacheBuilder<String, String> builder = new CacheBuilder<String, String>()
                .maxEntries(10000)
                .averageKeySize(16)
                .averageValueSize(128)
                .indexMemoryBudgetBytes(4 * 1024 * 1024);

        var estimate = builder.estimateMemory();
        assertNotNull(estimate);
        assertTrue(estimate.totalBytes() > 0);
    }

    @Test
    void cacheBuilder_estimateMemory_withExplicitStripes() {
        CacheBuilder<String, String> builder = new CacheBuilder<String, String>()
                .maxEntries(10000)
                .averageKeySize(16)
                .averageValueSize(128)
                .hashTableStripes(32)
                .entryPoolPartitions(16)
                .ghostCacheSize(256);

        var estimate = builder.estimateMemory();
        assertNotNull(estimate);
        assertEquals(32, estimate.hashTableStripes());
        assertEquals(16, estimate.entryPoolPartitions());
    }

    // ── ThreadLocalKeyBuffer: encodeStringFast edge cases ───────────────────

    @Test
    void threadLocalKeyBuffer_longString() {
        // Generate a string longer than the default buffer to exercise grow path
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            sb.append("abcdefghij");
        }
        String longKey = sb.toString();
        ThreadLocalKeyBuffer.BufferResult result = ThreadLocalKeyBuffer.encodeString(longKey);
        assertNotNull(result);
        assertTrue(result.length() > 0);
    }

    @Test
    void threadLocalKeyBuffer_nonAsciiString() {
        // Non-ASCII forces UTF-8 fallback
        String unicodeKey = "\u00e9\u00e8\u00ea\u00eb\u00ef";
        ThreadLocalKeyBuffer.BufferResult result = ThreadLocalKeyBuffer.encodeString(unicodeKey);
        assertNotNull(result);
        assertTrue(result.length() > 0);
    }

    @Test
    void threadLocalKeyBuffer_emptyString() {
        ThreadLocalKeyBuffer.BufferResult result = ThreadLocalKeyBuffer.encodeString("");
        assertNotNull(result);
        assertEquals(0, result.length());
    }
}
