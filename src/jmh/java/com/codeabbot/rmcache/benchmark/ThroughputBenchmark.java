package com.codeabbot.rmcache.benchmark;

import com.codeabbot.rmcache.CacheBuilder;
import com.codeabbot.rmcache.GhostCacheMode;
import com.codeabbot.rmcache.OffHeapCache;
import com.codeabbot.rmcache.Units;
import com.codeabbot.rmcache.eviction.NoEvictionPolicy;
import com.codeabbot.rmcache.serializer.BuiltInSerializers;
import com.codeabbot.rmcache.serializer.StringEncoding;
import com.target.nativememoryallocator.allocator.NativeMemoryAllocator;
import com.target.nativememoryallocator.allocator.NativeMemoryAllocatorBuilder;
import com.target.nativememoryallocator.buffer.OnHeapMemoryBuffer;
import com.target.nativememoryallocator.map.NativeMemoryMap;
import com.target.nativememoryallocator.map.NativeMemoryMapBackend;
import com.target.nativememoryallocator.map.NativeMemoryMapBuilder;
import com.target.nativememoryallocator.map.NativeMemoryMapSerializer;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.lang.foreign.ValueLayout;

/**
 * Dedicated throughput benchmark for 10k, 100k, and 1M entries.
 * Optimized to minimize heap allocations during measurement.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 2)
@Measurement(iterations = 3, time = 3)
@Fork(1)
@Threads(4)
public class ThroughputBenchmark {

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
        System.out.println("\n--- Setup Throughput Benchmark for " + entryCount + " entries ---");

        // Pre-create keys and values to avoid allocation in hot path
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
                .offHeapMemory(Units.gigabytes(4))
                .maxEntries(entryCount * 2)
                .stringKeyEncoding(StringEncoding.LATIN1)
                .valueSerializer(BuiltInSerializers.byteArray())
                .eviction(new NoEvictionPolicy())
                .hashTableLoadFactor(0.5d)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .ghostCacheSize(0)
                .build();

        // NMA Setup
        NativeMemoryAllocator nmaAllocator = new NativeMemoryAllocatorBuilder(
                4096,
                Units.gigabytes(4),
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
        // NMA manages cleanup via allocator
    }

    // ========== RMCache Benchmarks ==========

    @Benchmark
    public byte[] rmcacheGet() {
        int idx = ThreadLocalRandom.current().nextInt(entryCount);
        return rmcache.get(keys[idx]);
    }

    @Benchmark
    public void rmcacheGetZeroCopy(Blackhole bh) {
        int idx = ThreadLocalRandom.current().nextInt(entryCount);
        Integer v = rmcache.getZeroCopy(keys[idx], seg -> (int) seg.get(ValueLayout.JAVA_BYTE, 0));
        bh.consume(v);
    }

    @Benchmark
    public void rmcachePut() {
        int idx = ThreadLocalRandom.current().nextInt(entryCount);
        rmcache.put(keys[idx], values[idx % 1024]);
    }

    // ========== NMA Benchmarks ==========

    @Benchmark
    public byte[] nmaGet() {
        int idx = ThreadLocalRandom.current().nextInt(entryCount);
        return nmaMap.get(keys[idx]);
    }

    @Benchmark
    public void nmaPut() {
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
