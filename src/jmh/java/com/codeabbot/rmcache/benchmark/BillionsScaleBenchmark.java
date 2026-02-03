package com.codeabbot.rmcache.benchmark;

import com.codeabbot.rmcache.CacheBuilder;
import com.codeabbot.rmcache.OffHeapCache;
import com.codeabbot.rmcache.eviction.LRUPolicy;
import com.codeabbot.rmcache.serializer.BuiltInSerializers;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Benchmark designed to stress test RMCache at scale.
 * <p>
 * Default config: 10 Million entries (fits in ~4GB off-heap).
 * Intended to be run with higher parameters on large machines to verify 1B
 * scaling.
 * <p>
 * Focuses on:
 * - High concurrency (Alloc/Free churn)
 * - Large entry counts (Hash Table & LRU efficiency)
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 2)
@Measurement(iterations = 3, time = 5)
@Fork(1)
@Threads(16) // High concurrency to stress Lock-Free Allocator
public class BillionsScaleBenchmark {

    // Default to 10M for safety in typical env. Can be overridden to 100M or 1B.
    @Param("10000000")
    public int entryCount = 10_000_000;

    @Param("256")
    public int valueSize = 256;

    // Explicitly test SlabAllocator efficiency by forcing high churn?
    // Random overwrites will force free + allocate.

    private OffHeapCache<String, byte[]> cache;
    private byte[] valueBytes;

    @Setup(Level.Trial)
    public void setup() {
        System.out.println("\n=== Setup BillionsScaleBenchmark ===");
        System.out.println("Target Entries: " + entryCount);

        // Calculate memory: 10M * (256 + 32 + 40) ≈ 3.2 GB
        // For 1B entries, need ~320 GB.
        // We allocate based on entryCount with safety margin.
        int avgEntrySize = valueSize + 32 + 40;
        long memoryNeeded = (long) (entryCount * avgEntrySize * 1.2);

        System.out.println("Allocating Off-Heap Memory: " + (memoryNeeded / 1024 / 1024) + " MB");

        cache = new CacheBuilder<String, byte[]>()
                .maxEntries(entryCount)
                .offHeapMemory(memoryNeeded)
                .keySerializer(BuiltInSerializers.STRING_KEY)
                .valueSerializer(BuiltInSerializers.byteArray())
                .eviction(new LRUPolicy(entryCount)) // Use LRU to test eviction overhead
                .ghostCacheSize(131072) // Large ghost cache for 1B scale
                .build();

        valueBytes = new byte[valueSize];

        System.out.println("Pre-loading " + entryCount + " entries...");
        int preloadBatch = 100_000;
        for (int i = 0; i < entryCount; i++) {
            cache.put("key-" + i, valueBytes);
            if (i > 0 && i % preloadBatch == 0) {
                System.out.print(".");
                if (i % (preloadBatch * 80) == 0)
                    System.out.println();
            }
        }
        System.out.println("\nSetup Complete.");
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (cache != null) {
            cache.close();
        }
    }

    @Benchmark
    public byte[] getRand() {
        int idx = ThreadLocalRandom.current().nextInt(entryCount);
        return cache.get("key-" + idx);
    }

    @Benchmark
    public void putRand() {
        // Random overwrite to stress SlabAllocator (free + alloc)
        int idx = ThreadLocalRandom.current().nextInt(entryCount);
        cache.put("key-" + idx, valueBytes);
    }

    @Benchmark
    public void putNewChurn() {
        // Insert NEW keys (triggering eviction if full)
        // Use a range clearly outside the pre-loaded range to force churn vs expansion
        // Actually, if we use random int, we hit new keys rarely if range is huge.
        // Let's iterate a moving window?
        // JMH threads are independent.
        // Just use random keys in a range 2x larger than capacity
        int idx = ThreadLocalRandom.current().nextInt(entryCount * 2);
        cache.put("key-" + idx, valueBytes);
    }
}
