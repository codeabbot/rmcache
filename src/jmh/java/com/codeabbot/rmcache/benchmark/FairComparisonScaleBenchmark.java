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
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.ehcache.config.units.MemoryUnit;
import org.ehcache.CacheManager;
import org.ehcache.config.builders.CacheManagerBuilder;
import org.ehcache.config.builders.CacheConfigurationBuilder;
import org.ehcache.config.builders.ResourcePoolsBuilder;

/**
 * Fair benchmark comparison for 10k and 100k entries.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 1, time = 2)
@Measurement(iterations = 2, time = 3)
@Fork(1)
@Threads(4)
public class FairComparisonScaleBenchmark {

    @Param({ "10000", "100000", "1000000" })
    public int entryCount = 10000;

    @Param("256")
    public int valueSize = 256;

    private OffHeapCache<String, byte[]> rmcache;
    private NativeMemoryMap<String, byte[]> nmaMap;
    private org.ehcache.Cache<String, byte[]> ehcache;
    private CacheManager ehcacheManager;

    @Setup(Level.Trial)
    public void setup() {
        System.out.println("\nSetup scale test for " + entryCount + " entries...");

        // RMCache with GhostCache enabled + NO eviction (fair comparison)
        // Allocate 8GB to handle 10M entries + metadata comfortably
        rmcache = new CacheBuilder<String, byte[]>()
                .offHeapMemory(Units.gigabytes(8))
                .maxEntries(entryCount * 2) // Handle 1M entries comfortably
                .keySerializer(BuiltInSerializers.STRING_KEY)
                .valueSerializer(BuiltInSerializers.byteArray())
                .eviction(new NoEvictionPolicy())
                .ghostCacheSize(8192) // L1 cache for hot keys
                .build();

        // NMA allocator + Map (Using 8GB as requested to prevent breaking)
        // Set pageSizeBytes to 512 to handle 10M entries (approx 5GB if 1 page per
        // entry)
        NativeMemoryAllocator nmaAllocator = new NativeMemoryAllocatorBuilder(
                512,
                8L * 1024 * 1024 * 1024,
                false).build();

        nmaMap = NMAFactory.createMap(
                new ByteArraySerializer(),
                nmaAllocator,
                NativeMemoryMapBackend.CONCURRENT_HASH_MAP);

        // Ehcache Setup
        ehcacheManager = CacheManagerBuilder.newCacheManagerBuilder().build(true);
        ehcache = ehcacheManager.createCache("benchmarkCache",
                CacheConfigurationBuilder.newCacheConfigurationBuilder(
                        String.class, byte[].class,
                        ResourcePoolsBuilder.newResourcePoolsBuilder()
                                .offheap(8, MemoryUnit.GB))
                        .build());

        // Pre-populate
        byte[] value = new byte[valueSize];
        for (int i = 0; i < entryCount; i++) {
            String key = "key-" + i;
            rmcache.put(key, value);
            nmaMap.put(key, value);
            ehcache.put(key, value);
            if (i > 0 && i % 1_000_000 == 0) {
                System.out.println("Loaded " + i + " entries...");
            }
        }
        System.out.println("Setup complete for " + entryCount + " entries.\n");
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (rmcache != null) {
            rmcache.close();
        }
        if (ehcacheManager != null) {
            ehcacheManager.close();
        }
    }

    // ========== GET Benchmarks ==========

    @Benchmark
    public byte[] rmcacheGet() {
        int idx = ThreadLocalRandom.current().nextInt(entryCount);
        return rmcache.get("key-" + idx);
    }

    @Benchmark
    public byte[] nmaGet() {
        int idx = ThreadLocalRandom.current().nextInt(entryCount);
        return nmaMap.get("key-" + idx);
    }

    // ========== PUT Benchmarks ==========

    @Benchmark
    public void rmcachePut() {
        int idx = ThreadLocalRandom.current().nextInt(entryCount);
        byte[] value = new byte[valueSize];
        rmcache.put("key-" + idx, value);
    }

    @Benchmark
    public void nmaPut() {
        int idx = ThreadLocalRandom.current().nextInt(entryCount);
        byte[] value = new byte[valueSize];
        nmaMap.put("key-" + idx, value);
    }

    // ========== EHCACHE Benchmarks ==========

    @Benchmark
    public byte[] ehcacheGet() {
        int idx = ThreadLocalRandom.current().nextInt(entryCount);
        return ehcache.get("key-" + idx);
    }

    @Benchmark
    public void ehcachePut() {
        int idx = ThreadLocalRandom.current().nextInt(entryCount);
        byte[] value = new byte[valueSize];
        ehcache.put("key-" + idx, value);
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
