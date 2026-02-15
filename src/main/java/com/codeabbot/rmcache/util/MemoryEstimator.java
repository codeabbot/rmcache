package com.codeabbot.rmcache.util;

import com.codeabbot.rmcache.GhostCacheMode;
import com.codeabbot.rmcache.index.EntryBlockLayout;

/**
 * Estimates RMCache and NMA memory usage based on entry count and key/value sizes.
 */
public final class MemoryEstimator {

    public static final double DEFAULT_LOAD_FACTOR = 0.60d;
    public static final double DEFAULT_ALLOCATOR_OVERHEAD_RATIO = 0.10d;

    private MemoryEstimator() {
    }

    public static MemoryEstimate estimate(long maxEntries, int avgKeySize, int avgValueSize) {
        return estimate(maxEntries, avgKeySize, avgValueSize, autoStripes(maxEntries), autoPartitions(maxEntries),
                DEFAULT_LOAD_FACTOR, autoGhostSize(maxEntries), GhostCacheMode.AUTO, DEFAULT_ALLOCATOR_OVERHEAD_RATIO);
    }

    public static MemoryEstimate estimate(long maxEntries, int avgKeySize, int avgValueSize, int hashTableStripes,
            int entryPoolPartitions, double loadFactor, int ghostCacheSize, GhostCacheMode ghostCacheMode,
            double allocatorOverheadRatio) {
        int stripes = normalizePowerOfTwo(hashTableStripes, autoStripes(maxEntries));
        int partitions = normalizePowerOfTwo(entryPoolPartitions, autoPartitions(maxEntries));

        int slotCapacity = computeSlotCapacity(maxEntries, partitions);
        int perStripeCapacity = computeHashTableCapacityPerStripe(maxEntries, stripes, loadFactor);
        long totalHashSlots = (long) perStripeCapacity * stripes;

        long hashTableBytes = totalHashSlots * 8L;
        long offsetsBytes = ((long) slotCapacity + 1L) * 8L;
        long freeListBytes = (long) slotCapacity * 4L;

        GhostCacheMode effectiveGhost = (ghostCacheMode == null) ? GhostCacheMode.AUTO : ghostCacheMode;
        int ghostSize = (ghostCacheSize > 0) ? normalizePowerOfTwo(ghostCacheSize, ghostCacheSize) : 0;
        long ghostBytes = (effectiveGhost == GhostCacheMode.OFF_HEAP) ? ghostSize * 8L : 0L;

        int entrySize = EntryBlockLayout.computeSize(avgKeySize, avgValueSize);
        long dataBytes = maxEntries * (long) entrySize;

        long indexBytes = hashTableBytes + offsetsBytes + freeListBytes + ghostBytes;
        long allocatorOverhead = (long) Math.ceil(dataBytes * allocatorOverheadRatio);
        long totalBytes = dataBytes + indexBytes + allocatorOverhead;

        return new MemoryEstimate(
                maxEntries,
                avgKeySize,
                avgValueSize,
                stripes,
                partitions,
                loadFactor,
                perStripeCapacity,
                totalHashSlots,
                slotCapacity,
                entrySize,
                dataBytes,
                hashTableBytes,
                offsetsBytes,
                freeListBytes,
                ghostBytes,
                allocatorOverhead,
                totalBytes);
    }

    public static IndexSizing deriveIndexSizing(long maxEntries, int stripes, int partitions, long indexBudgetBytes,
            int ghostCacheSize, GhostCacheMode ghostCacheMode, double maxLoadFactor) {
        int slotCapacity = computeSlotCapacity(maxEntries, partitions);
        long offsetsBytes = ((long) slotCapacity + 1L) * 8L;
        long freeListBytes = (long) slotCapacity * 4L;
        long ghostBytes = (ghostCacheMode == GhostCacheMode.OFF_HEAP && ghostCacheSize > 0)
                ? (long) normalizePowerOfTwo(ghostCacheSize, ghostCacheSize) * 8L
                : 0L;

        long fixedIndexBytes = offsetsBytes + freeListBytes + ghostBytes;
        long remaining = indexBudgetBytes - fixedIndexBytes;
        if (remaining <= 0) {
            throw new IllegalArgumentException("indexMemoryBudgetBytes is too small. Minimum required index bytes: "
                    + fixedIndexBytes);
        }

        long maxHashSlots = remaining / 8L;
        double computedLoad = (double) maxEntries / (double) maxHashSlots;
        if (computedLoad > maxLoadFactor) {
            throw new IllegalArgumentException(
                    "indexMemoryBudgetBytes too small for maxEntries/loadFactor. Required loadFactor <= "
                            + maxLoadFactor + ", computed " + computedLoad);
        }

        double effectiveLoad = Math.max(0.25d, computedLoad);
        int perStripeCapacity = computeHashTableCapacityPerStripe(maxEntries, stripes, effectiveLoad);
        long totalHashSlots = (long) perStripeCapacity * stripes;
        double actualLoad = (double) maxEntries / (double) totalHashSlots;

        long hashTableBytes = totalHashSlots * 8L;
        long totalIndexBytes = hashTableBytes + fixedIndexBytes;

        return new IndexSizing(perStripeCapacity, totalHashSlots, actualLoad, totalIndexBytes, fixedIndexBytes,
                hashTableBytes);
    }

    public static NmaEstimate estimateNma(long entries, int keySize, int valueSize, int pageSizeBytes,
            int perEntryOverheadBytes) {
        int payload = keySize + valueSize + perEntryOverheadBytes;
        int pages = (int) Math.ceil(payload / (double) pageSizeBytes);
        long offHeapBytes = entries * (long) pages * pageSizeBytes;
        return new NmaEstimate(entries, pageSizeBytes, perEntryOverheadBytes, offHeapBytes);
    }

    private static int computeSlotCapacity(long maxEntries, int partitions) {
        int shift = 0;
        int pSize = 1;
        while ((long) pSize * partitions < maxEntries || pSize < 1024) {
            pSize <<= 1;
            shift++;
        }
        return partitions * (1 << shift);
    }

    private static int computeHashTableCapacityPerStripe(long maxEntries, int stripes, double loadFactor) {
        int perStripeEntries = Math.max(2, (int) Math.ceil(maxEntries / (double) stripes));
        int targetCapacity = (int) Math.ceil(perStripeEntries / loadFactor);
        return nextPowerOfTwo(Math.max(16, targetCapacity));
    }

    private static int normalizePowerOfTwo(int requested, int fallback) {
        if (requested <= 0)
            return fallback;
        int cap = 1;
        while (cap < requested)
            cap <<= 1;
        return cap;
    }

    private static int nextPowerOfTwo(int value) {
        int v = value - 1;
        v |= v >>> 1;
        v |= v >>> 2;
        v |= v >>> 4;
        v |= v >>> 8;
        v |= v >>> 16;
        return v + 1;
    }

    private static int autoStripes(long entries) {
        if (entries >= 10_000_000L)
            return 8192;
        if (entries >= 1_000_000L)
            return 1024;
        return 64;
    }

    private static int autoPartitions(long entries) {
        if (entries <= 100_000L)
            return 64;
        if (entries <= 10_000_000L)
            return 128;
        return 256;
    }

    private static int autoGhostSize(long entries) {
        int suggested = (int) (entries / 1000);
        return Math.max(64, Math.min(suggested, 32768));
    }

    public record MemoryEstimate(
            long maxEntries,
            int avgKeySize,
            int avgValueSize,
            int hashTableStripes,
            int entryPoolPartitions,
            double loadFactor,
            int hashTableCapacityPerStripe,
            long hashTableSlots,
            int slotCapacity,
            int entrySizeBytes,
            long dataBytes,
            long hashTableBytes,
            long offsetsBytes,
            long freeListBytes,
            long ghostCacheBytes,
            long allocatorOverheadBytes,
            long totalBytes) {

        public double bytesPerEntry() {
            return (maxEntries == 0) ? 0.0 : (double) totalBytes / (double) maxEntries;
        }
    }

    public record IndexSizing(
            int hashTableCapacityPerStripe,
            long hashTableSlots,
            double loadFactor,
            long totalIndexBytes,
            long fixedIndexBytes,
            long hashTableBytes) {
    }

    public record NmaEstimate(
            long entries,
            int pageSizeBytes,
            int perEntryOverheadBytes,
            long offHeapBytes) {

        public double bytesPerEntry() {
            return (entries == 0) ? 0.0 : (double) offHeapBytes / (double) entries;
        }
    }
}
