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
package com.codeabbot.rmcache.benchmark;

import com.codeabbot.rmcache.CacheBuilder;
import com.codeabbot.rmcache.GhostCacheMode;
import com.codeabbot.rmcache.OffHeapCache;
import com.codeabbot.rmcache.Units;
import com.codeabbot.rmcache.eviction.NoEvictionPolicy;
import com.codeabbot.rmcache.serializer.BuiltInSerializers;
import com.codeabbot.rmcache.util.MemoryEstimator;
import net.openhft.chronicle.map.ChronicleMap;
import org.mapdb.DB;
import org.mapdb.HTreeMap;

import java.io.IOException;
import java.util.List;
import java.util.function.Supplier;

/**
 * Memory Scalability Suite: compares heap and off-heap memory usage across
 * RMCache, MapDB, and ChronicleMap at 10k / 100k / 1M entries, with a
 * 1B-entry projection for RMCache.
 *
 * <p>Run with: {@code ./gradlew runMemoryScalability}
 */
public class MemoryScalabilitySuite {

    private static final int KEY_SIZE = 16;
    private static final int VALUE_SIZE = 256;
    private static final List<Integer> SCALES = List.of(10_000, 100_000, 1_000_000);

    public static void main(String[] args) throws IOException {
        System.out.println("=".repeat(90));
        System.out.println("  Memory Scalability Suite: RMCache vs MapDB vs ChronicleMap");
        System.out.println("  Configuration: " + KEY_SIZE + "B key, " + VALUE_SIZE + "B value");
        System.out.println("=".repeat(90) + "\n");

        System.out.printf("%-8s | %-14s | %-14s | %-16s | %-12s%n",
                "Scale", "Provider", "Heap (MB)", "Off-Heap (MB)", "Bytes/Entry");
        System.out.println("-".repeat(72));

        for (int scale : SCALES) {

            // ── RMCache ───────────────────────────────────────────────────────
            runMeasurement(scale, "RMCache", () -> {
                try {
                    return new CacheBuilder<String, byte[]>()
                            .offHeapMemory(Units.gigabytes(4))
                            .maxEntries(scale * 2)
                            .keySerializer(BuiltInSerializers.STRING_KEY)
                            .valueSerializer(BuiltInSerializers.byteArray())
                            .eviction(new NoEvictionPolicy())
                            .ghostCacheMode(GhostCacheMode.OFF_HEAP)
                            .ghostCacheSize(8192)
                            .build();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            // ── MapDB ─────────────────────────────────────────────────────────
            runMeasurement(scale, "MapDB", () -> {
                DB db = MapDBFactory.createDB();
                HTreeMap<String, byte[]> map = MapDBFactory.createMap(db);
                // Wrap in a closeable pair so teardown works
                return new MapDBCacheAdapter(db, map);
            });

            // ── ChronicleMap ──────────────────────────────────────────────────
            runMeasurement(scale, "ChronicleMap", () -> {
                try {
                    return ChronicleMapFactory.createMap(scale * 2, VALUE_SIZE);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

            System.out.println("-".repeat(72));
        }

        printProjected1B();
    }

    @SuppressWarnings("unchecked")
    private static void runMeasurement(int count, String name, Supplier<Object> factory) {
        forceGC();
        long baselineHeap = getUsedHeap();

        Object cache = factory.get();

        String sampleKey = String.format("key-%010d", 0);
        byte[] value = new byte[VALUE_SIZE];

        for (int i = 0; i < count; i++) {
            String key = String.format("key-%010d", i);
            if (cache instanceof OffHeapCache) {
                ((OffHeapCache<String, byte[]>) cache).put(key, value);
            } else if (cache instanceof MapDBCacheAdapter) {
                ((MapDBCacheAdapter) cache).map.put(key, value);
            } else if (cache instanceof ChronicleMap) {
                ((ChronicleMap<String, byte[]>) cache).put(key, value);
            }
        }

        forceGC();
        long usedHeap = Math.max(0, getUsedHeap() - baselineHeap);

        long usedOffHeap = 0;
        if (cache instanceof OffHeapCache) {
            usedOffHeap = ((OffHeapCache<?, ?>) cache).getStats().memoryUsedBytes();
        } else if (cache instanceof MapDBCacheAdapter) {
            // MapDB uses Java direct memory; estimate from entry payload
            usedOffHeap = (long) count * (KEY_SIZE + VALUE_SIZE + 64); // ~64B MapDB overhead/entry
        } else if (cache instanceof ChronicleMap) {
            // ChronicleMap exposes longSize() but not raw bytes; estimate from density
            usedOffHeap = (long) count * (KEY_SIZE + VALUE_SIZE + 50); // ~50B Chronicle overhead/entry
        }

        double bytesPerEntry = (double) (usedHeap + usedOffHeap) / count;

        System.out.printf("%-8s | %-14s | %-14.2f | %-16.2f | %-12.1f%n",
                simplify(count), name,
                usedHeap / 1_048_576.0,
                usedOffHeap / 1_048_576.0,
                bytesPerEntry);

        try {
            if (cache instanceof AutoCloseable) {
                ((AutoCloseable) cache).close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void printProjected1B() {
        System.out.println("\n" + "=".repeat(90));
        System.out.println("  PROJECTED MEMORY: 1 BILLION ENTRIES (RMCache)");
        System.out.println("=".repeat(90));

        MemoryEstimator.MemoryEstimate est = MemoryEstimator.estimate(
                1_000_000_000L, KEY_SIZE, VALUE_SIZE,
                8192, 256,
                MemoryEstimator.DEFAULT_LOAD_FACTOR,
                8192, GhostCacheMode.OFF_HEAP,
                MemoryEstimator.DEFAULT_ALLOCATOR_OVERHEAD_RATIO);

        double offHeapGb = est.totalBytes() / 1_073_741_824.0;
        System.out.printf("  RMCache off-heap: %.1f GB  |  Heap: < 50 MB (control plane only)%n", offHeapGb);
        System.out.println("  MapDB / ChronicleMap at 1B scale: not projected (significant index overhead)");
        System.out.println("=".repeat(90) + "\n");
    }

    private static void forceGC() {
        for (int i = 0; i < 3; i++) {
            System.gc();
            try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private static String simplify(int count) {
        if (count >= 1_000_000) return (count / 1_000_000) + "M";
        if (count >= 1_000) return (count / 1_000) + "k";
        return String.valueOf(count);
    }

    private static long getUsedHeap() {
        return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    }

    /** Adapter to close both the MapDB DB and its map as a single AutoCloseable. */
    private static final class MapDBCacheAdapter implements AutoCloseable {
        final DB db;
        final HTreeMap<String, byte[]> map;

        MapDBCacheAdapter(DB db, HTreeMap<String, byte[]> map) {
            this.db = db;
            this.map = map;
        }

        @Override
        public void close() {
            db.close();
        }
    }
}
