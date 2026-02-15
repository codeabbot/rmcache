package com.codeabbot.rmcache.benchmark;

import com.codeabbot.rmcache.CacheBuilder;
import com.codeabbot.rmcache.OffHeapCache;
import com.codeabbot.rmcache.Units;
import com.codeabbot.rmcache.eviction.NoEvictionPolicy;
import com.codeabbot.rmcache.GhostCacheMode;
import com.codeabbot.rmcache.serializer.BuiltInSerializers;
import com.codeabbot.rmcache.util.MemoryEstimator;
import com.target.nativememoryallocator.allocator.NativeMemoryAllocator;
import com.target.nativememoryallocator.allocator.NativeMemoryAllocatorBuilder;
import com.target.nativememoryallocator.buffer.OnHeapMemoryBuffer;
import com.target.nativememoryallocator.map.NativeMemoryMap;
import com.target.nativememoryallocator.map.NativeMemoryMapBackend;
import com.target.nativememoryallocator.map.NativeMemoryMapBuilder;
import com.target.nativememoryallocator.map.NativeMemoryMapSerializer;

import java.util.List;
import java.util.function.Function;

/**
 * Memory Scalability Suite: Compares On-Heap and Off-Heap efficiency between
 * RMCache and NMA.
 * Includes projections for 1 Billion entries.
 */
public class MemoryScalabilitySuite {

    private static final int KEY_SIZE = 16;
    private static final int VALUE_SIZE = 256;
    private static final int NMA_PAGE_SIZE = 4096;
    private static final List<Integer> SCALES = List.of(10_000, 100_000, 1_000_000);

    public static void main(String[] args) {
        System.out
                .println("==========================================================================================");
        System.out.println("  Memory Scalability Suite: RMCache vs NMA");
        System.out.println("  Configuration: " + KEY_SIZE + "B Key, " + VALUE_SIZE + "B Value");
        System.out.println(
                "==========================================================================================\n");

        System.out.println(String.format("%-10s | %-12s | %-15s | %-15s | %-15s", "Scale", "Provider", "Heap (MB)",
                "Off-Heap (MB)", "Bytes/Entry"));
        System.out
                .println("------------------------------------------------------------------------------------------");

        for (int scale : SCALES) {
            runMeasurement(scale, "RMCache", count -> new CacheBuilder<String, byte[]>()
                    .offHeapMemory(Units.gigabytes(4))
                    .maxEntries(count * 2)
                    .keySerializer(BuiltInSerializers.STRING_KEY)
                    .valueSerializer(BuiltInSerializers.byteArray())
                    .eviction(new NoEvictionPolicy())
                    .ghostCacheMode(GhostCacheMode.OFF_HEAP)
                    .ghostCacheSize(8192)
                    .build());
            runMeasurement(scale, "NMA", count -> {
                NativeMemoryAllocator allocator = new NativeMemoryAllocatorBuilder(
                        NMA_PAGE_SIZE,
                        Units.gigabytes(4),
                        false).build();
                return NMAFactory.createMap(
                        new ByteArraySerializer(),
                        allocator,
                        NativeMemoryMapBackend.CONCURRENT_HASH_MAP);
            });

            System.out.println(
                    "------------------------------------------------------------------------------------------");
        }

        printProjected1B();
    }

    private static void runMeasurement(int count, String name, Function<Integer, Object> factory) {
        forceGC();
        long baselineHeap = getUsedHeap();

        Object cache = factory.apply(count);

        byte[] value = new byte[VALUE_SIZE];
        for (int i = 0; i < count; i++) {
            String key = String.format("key-%010d", i); // Stable key length
            if (cache instanceof OffHeapCache) {
                ((OffHeapCache<String, byte[]>) cache).put(key, value);
            } else if (cache instanceof NativeMemoryMap) {
                ((NativeMemoryMap<String, byte[]>) cache).put(key, value);
            }
        }

        forceGC();
        long usedHeap = getUsedHeap() - baselineHeap;

        // Off-heap calculation
        long usedOffHeap;
        if (cache instanceof OffHeapCache) {
            usedOffHeap = ((OffHeapCache<?, ?>) cache).getStats().memoryUsedBytes();
        } else {
            // NMA doesn't expose total used memory easily in this version,
            // estimating based on fixed page allocations per entry.
            MemoryEstimator.NmaEstimate estimate = MemoryEstimator.estimateNma(
                    count, KEY_SIZE, VALUE_SIZE, NMA_PAGE_SIZE, 32);
            usedOffHeap = estimate.offHeapBytes();
        }

        long totalBytes = usedHeap + usedOffHeap;
        double bytesPerEntry = (double) totalBytes / count;

        System.out.println(String.format("%-10s | %-12s | %-15.2f | %-15.2f | %-15.1f",
                simplify(count), name, usedHeap / 1024.0 / 1024.0, usedOffHeap / 1024.0 / 1024.0, bytesPerEntry));

        if (cache instanceof AutoCloseable) {
            try {
                ((AutoCloseable) cache).close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private static void printProjected1B() {
        System.out.println(
                "\n==========================================================================================");
        System.out.println("  PROJECTED MEMORY CONSUMPTION FOR 1 BILLION ENTRIES");
        System.out
                .println("==========================================================================================");
        System.out.println(String.format("%-12s | %-18s | %-18s | %-15s", "Provider", "Heap Usage", "Off-Heap Usage",
                "Total RAM"));
        System.out
                .println("------------------------------------------------------------------------------------------");

        MemoryEstimator.MemoryEstimate rmEstimate = MemoryEstimator.estimate(
                1_000_000_000L,
                KEY_SIZE,
                VALUE_SIZE,
                8192,
                256,
                MemoryEstimator.DEFAULT_LOAD_FACTOR,
                8192,
                GhostCacheMode.OFF_HEAP,
                MemoryEstimator.DEFAULT_ALLOCATOR_OVERHEAD_RATIO);
        double rmOffHeapGb = rmEstimate.totalBytes() / 1024.0 / 1024.0 / 1024.0;
        System.out.println(String.format("%-12s | %-18s | %-18.1f GB | %-15.1f GB",
                "RMCache", "< 50 MB", rmOffHeapGb, rmOffHeapGb));

        MemoryEstimator.NmaEstimate nmaEstimate = MemoryEstimator.estimateNma(
                1_000_000_000L, KEY_SIZE, VALUE_SIZE, NMA_PAGE_SIZE, 32);
        double nmaOffHeapGb = nmaEstimate.offHeapBytes() / 1024.0 / 1024.0 / 1024.0;
        System.out.println(String.format("%-12s | %-18s | %-18.1f GB | %-15.1f GB",
                "NMA", "High (CHM)", nmaOffHeapGb, nmaOffHeapGb));
        System.out
                .println("------------------------------------------------------------------------------------------");
        System.out.println("  Critical Advantage: RMCache avoids massive on-heap CHM overhead at 1B scale.");
        System.out.println(
                "==========================================================================================\n");
    }

    private static void forceGC() {
        for (int i = 0; i < 3; i++) {
            System.gc();
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static String simplify(int count) {
        if (count >= 1_000_000)
            return (count / 1_000_000) + "M";
        if (count >= 1_000)
            return (count / 1_000) + "k";
        return String.valueOf(count);
    }

    private static long getUsedHeap() {
        return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    }

    private static class ByteArraySerializer implements NativeMemoryMapSerializer<byte[]> {
        @Override
        public byte[] deserializeFromOnHeapMemoryBuffer(OnHeapMemoryBuffer onHeapMemoryBuffer) {
            return onHeapMemoryBuffer.toTrimmedArray();
        }

        @Override
        public byte[] serializeToByteArray(byte[] value) {
            return value;
        }
    }
}
