package com.codeabbot.rmcache.benchmark;

import com.codeabbot.rmcache.CacheBuilder;
import com.codeabbot.rmcache.GhostCacheMode;
import com.codeabbot.rmcache.OffHeapCache;
import com.codeabbot.rmcache.Units;
import com.codeabbot.rmcache.eviction.NoEvictionPolicy;
import com.codeabbot.rmcache.serializer.BuiltInSerializers;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * PUT hot-path microbenchmark:
 * - No eviction
 * - Fixed-size keys/values
 * - Preallocated data (no per-op allocations)
 * - Measures in-place updates (existing keys)
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 1, time = 2)
@Measurement(iterations = 2, time = 3)
@Fork(1)
@Threads(4)
public class PutHotPathBenchmark {

    @Param({ "10000", "100000", "1000000" })
    public int entryCount = 10000;

    @Param("16")
    public int keySize = 16;

    @Param("256")
    public int valueSize = 256;

    private OffHeapCache<byte[], byte[]> rmcache;
    private byte[][] keys;
    private byte[] value;

    @Setup(Level.Trial)
    public void setup() {
        rmcache = new CacheBuilder<byte[], byte[]>()
                .offHeapMemory(Units.gigabytes(8))
                .maxEntries(entryCount * 2)
                .keySerializer(BuiltInSerializers.BYTE_ARRAY_KEY)
                .valueSerializer(BuiltInSerializers.byteArray())
                .eviction(new NoEvictionPolicy())
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .ghostCacheSize(0)
                .backgroundEviction(false)
                .hashTableLoadFactor(0.60d)
                .build();

        keys = new byte[entryCount][keySize];
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < entryCount; i++) {
            byte[] key = new byte[keySize];
            rnd.nextBytes(key);
            keys[i] = key;
        }

        value = new byte[valueSize];
        rnd.nextBytes(value);

        for (int i = 0; i < entryCount; i++) {
            rmcache.put(keys[i], value);
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (rmcache != null) {
            rmcache.close();
        }
    }

    @Benchmark
    public void rmcachePutUpdate() {
        int idx = ThreadLocalRandom.current().nextInt(entryCount);
        rmcache.put(keys[idx], value);
    }
}
