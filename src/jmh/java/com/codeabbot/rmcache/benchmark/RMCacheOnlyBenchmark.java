package com.codeabbot.rmcache.benchmark;

import com.codeabbot.rmcache.CacheBuilder;
import com.codeabbot.rmcache.GhostCacheMode;
import com.codeabbot.rmcache.OffHeapCache;
import com.codeabbot.rmcache.Units;
import com.codeabbot.rmcache.eviction.NoEvictionPolicy;
import com.codeabbot.rmcache.serializer.BuiltInSerializers;
import com.codeabbot.rmcache.serializer.StringEncoding;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Lightweight RMCache-only benchmark (no NMA/EhCache dependencies).
 * Used for before/after comparison during code review optimizations.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 1, time = 2)
@Measurement(iterations = 2, time = 3)
@Fork(1)
@Threads(4)
public class RMCacheOnlyBenchmark {

    @Param({ "10000", "100000", "1000000", "10000000" })
    public int entryCount = 10000;

    @Param("256")
    public int valueSize = 256;

    private OffHeapCache<String, byte[]> rmcache;

    @Setup(Level.Trial)
    public void setup() {
        System.out.println("\nSetup RMCache-only benchmark for " + entryCount + " entries...");

        rmcache = new CacheBuilder<String, byte[]>()
                .offHeapMemory(Units.gigabytes(8))
                .maxEntries(entryCount * 2)
                .stringKeyEncoding(StringEncoding.LATIN1)
                .valueSerializer(BuiltInSerializers.byteArray())
                .eviction(new NoEvictionPolicy())
                .hashTableLoadFactor(0.5d)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .ghostCacheSize(0)
                .build();

        byte[] value = new byte[valueSize];
        for (int i = 0; i < entryCount; i++) {
            rmcache.put("key-" + i, value);
        }
        System.out.println("Setup complete.\n");
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (rmcache != null) {
            rmcache.close();
        }
    }

    @Benchmark
    public byte[] get() {
        int idx = ThreadLocalRandom.current().nextInt(entryCount);
        return rmcache.get("key-" + idx);
    }

    @Benchmark
    public void put() {
        int idx = ThreadLocalRandom.current().nextInt(entryCount);
        byte[] value = new byte[valueSize];
        rmcache.put("key-" + idx, value);
    }
}
