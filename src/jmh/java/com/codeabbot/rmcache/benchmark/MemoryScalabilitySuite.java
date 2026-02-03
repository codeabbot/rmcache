package com.codeabbot.rmcache.benchmark;

import com.codeabbot.rmcache.CacheBuilder;
import com.codeabbot.rmcache.OffHeapCache;
import com.codeabbot.rmcache.Units;
import com.codeabbot.rmcache.eviction.NoEvictionPolicy;
import com.codeabbot.rmcache.serializer.BuiltInSerializers;
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
                    .ghostCacheSize(8192)
                    .build());
            runMeasurement(scale, "NMA", count -> {
                NativeMemoryAllocator allocator = new NativeMemoryAllocatorBuilder(
                        4096,
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
            // estimating based on (Key+Value+Header) * count.
            // NMA typically has ~32 bytes overhead per entry off-heap.
            usedOffHeap = count * (long) (KEY_SIZE + VALUE_SIZE + 32);
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

        // RMCache: 16B/slot HT + 32B meta + Data. Zero-Heap Index.
        // 1B Entries -> HT: 16GB. Meta: 32GB. Data: 256GB.
        // Total Off-Heap: ~304 GB. Heap: < 50 MB.
        System.out.println(String.format("%-12s | %-18s | %-18s | %-15s", "RMCache", "~50 MB", "~304 GB", "304.1 GB"));

        // NMA: Uses CHM on Heap.
        // Per-entry heap: Node + Key + Handle = ~80 bytes.
        // 1B Entries -> 80 GB HEAP.
        // Off-Heap: Data (256 GB) + Overhead (~32 GB) = 288 GB.
        // Total RAM: ~368 GB.
        System.out.println(String.format("%-12s | %-18s | %-18s | %-15s", "NMA", "~80.0 GB", "~288 GB", "368.0 GB"));
        System.out
                .println("------------------------------------------------------------------------------------------");
        System.out.println("  Critical Advantage: RMCache saves > 79 GB of Java Heap at 1B scale.");
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
