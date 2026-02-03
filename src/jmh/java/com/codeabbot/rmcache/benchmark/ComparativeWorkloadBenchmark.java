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

/**
 * Comparative workload benchmark: Read-Only, Write-Only, and Read/Write
 * (Mixed).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 2)
@Measurement(iterations = 3, time = 3)
@Fork(1)
@Threads(4)
public class ComparativeWorkloadBenchmark {

    @Param({ "10000", "100000", "1000000" })
    public int entryCount = 10000;

    @Param("256")
    public int valueSize = 256;

    private OffHeapCache<String, byte[]> rmcache;
    private NativeMemoryMap<String, byte[]> nmaMap;

    private String[] keys;
    private byte[][] values;

    @Setup(Level.Trial)
    public void setup() {
        System.out.println("\n--- Setup Comparative Workload for " + entryCount + " entries ---");

        keys = new String[entryCount];
        for (int i = 0; i < entryCount; i++) {
            keys[i] = "key-" + i;
        }
        values = new byte[1024][valueSize];
        for (int i = 0; i < 1024; i++) {
            values[i] = new byte[valueSize];
        }

        // RMCache Setup
        rmcache = new CacheBuilder<String, byte[]>()
                .offHeapMemory(Units.gigabytes(8))
                .maxEntries(entryCount * 2)
                .keySerializer(BuiltInSerializers.STRING_KEY)
                .valueSerializer(BuiltInSerializers.byteArray())
                .eviction(new NoEvictionPolicy())
                .ghostCacheSize(8192) // Enable L1 cache
                .build();

        // NMA Setup
        NativeMemoryAllocator nmaAllocator = new NativeMemoryAllocatorBuilder(
                4096,
                Units.gigabytes(8),
                false).build();

        nmaMap = NMAFactory.createMap(
                new ByteArraySerializer(),
                nmaAllocator,
                NativeMemoryMapBackend.CONCURRENT_HASH_MAP);

        // Pre-populate
        for (int i = 0; i < entryCount; i++) {
            byte[] v = values[i % 1024];
            rmcache.put(keys[i], v);
            nmaMap.put(keys[i], v);
        }
        System.out.println("--- Setup Complete ---\n");
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (rmcache != null) {
            rmcache.close();
        }
    }

    // ========== RMCache Workloads ==========

    @Benchmark
    public byte[] rmcache_ReadOnly() {
        int idx = ThreadLocalRandom.current().nextInt(entryCount);
        return rmcache.get(keys[idx]);
    }

    @Benchmark
    public void rmcache_WriteOnly() {
        int idx = ThreadLocalRandom.current().nextInt(entryCount);
        rmcache.put(keys[idx], values[idx % 1024]);
    }

    @Group("rmcacheMixed")
    @GroupThreads(3)
    @Benchmark
    public byte[] rmcache_Mixed_Read() {
        int idx = ThreadLocalRandom.current().nextInt(entryCount);
        return rmcache.get(keys[idx]);
    }

    @Group("rmcacheMixed")
    @GroupThreads(1)
    @Benchmark
    public void rmcache_Mixed_Write() {
        int idx = ThreadLocalRandom.current().nextInt(entryCount);
        rmcache.put(keys[idx], values[idx % 1024]);
    }

    // ========== NMA Workloads ==========

    @Benchmark
    public byte[] nma_ReadOnly() {
        int idx = ThreadLocalRandom.current().nextInt(entryCount);
        return nmaMap.get(keys[idx]);
    }

    @Benchmark
    public void nma_WriteOnly() {
        int idx = ThreadLocalRandom.current().nextInt(entryCount);
        nmaMap.put(keys[idx], values[idx % 1024]);
    }

    @Group("nmaMixed")
    @GroupThreads(3)
    @Benchmark
    public byte[] nma_Mixed_Read() {
        int idx = ThreadLocalRandom.current().nextInt(entryCount);
        return nmaMap.get(keys[idx]);
    }

    @Group("nmaMixed")
    @GroupThreads(1)
    @Benchmark
    public void nma_Mixed_Write() {
        int idx = ThreadLocalRandom.current().nextInt(entryCount);
        nmaMap.put(keys[idx], values[idx % 1024]);
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
