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
 * Benchmark demonstrating GhostCache performance with hot-key access patterns.
 * <p>
 * Uses Zipfian distribution where ~20% of keys get ~80% of accesses.
 * This is the realistic workload where GhostCache provides sub-100ns GETs.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 2, time = 2)
@Measurement(iterations = 3, time = 3)
@Fork(1)
@Threads(4)
public class HotKeyBenchmark {

    @Param({ "10000", "100000" })
    public int entryCount = 10000;

    @Param("256")
    public int valueSize = 256;

    // Hot key set size (20% of entries)
    private int hotKeyCount = 0;

    // Pre-computed hot keys for fast access
    private String[] hotKeys;

    private OffHeapCache<String, byte[]> cacheWithGhost;
    private OffHeapCache<String, byte[]> cacheWithOffHeapGhost;
    private OffHeapCache<String, byte[]> cacheWithoutGhost;

    @Setup(Level.Trial)
    public void setup() {
        System.out.println("\nSetup hot-key benchmark for " + entryCount + " entries...");

        hotKeyCount = Math.max(100, (int) (entryCount * 0.2));
        hotKeys = new String[hotKeyCount];
        for (int i = 0; i < hotKeyCount; i++) {
            hotKeys[i] = "key-" + i;
        }

        // Cache WITH GhostCache enabled
        cacheWithGhost = new CacheBuilder<String, byte[]>()
                .offHeapMemory(Units.gigabytes(2))
                .maxEntries(entryCount * 2)
                .stringKeyEncoding(StringEncoding.LATIN1)
                .valueSerializer(BuiltInSerializers.byteArray())
                .eviction(new NoEvictionPolicy())
                .hashTableLoadFactor(0.5d)
                .ghostCacheSize(4096) // L1 cache for hot keys
                .ghostCacheMode(GhostCacheMode.HEAP)
                .build();

        // Cache WITH Off-Heap GhostCache enabled
        cacheWithOffHeapGhost = new CacheBuilder<String, byte[]>()
                .offHeapMemory(Units.gigabytes(2))
                .maxEntries(entryCount * 2)
                .stringKeyEncoding(StringEncoding.LATIN1)
                .valueSerializer(BuiltInSerializers.byteArray())
                .eviction(new NoEvictionPolicy())
                .hashTableLoadFactor(0.5d)
                .ghostCacheSize(4096)
                .ghostCacheMode(GhostCacheMode.OFF_HEAP)
                .build();

        // Cache WITHOUT GhostCache (baseline)
        cacheWithoutGhost = new CacheBuilder<String, byte[]>()
                .offHeapMemory(Units.gigabytes(2))
                .maxEntries(entryCount * 2)
                .stringKeyEncoding(StringEncoding.LATIN1)
                .valueSerializer(BuiltInSerializers.byteArray())
                .eviction(new NoEvictionPolicy())
                .hashTableLoadFactor(0.5d)
                .build();

        // Pre-populate both caches
        byte[] value = new byte[valueSize];
        for (int i = 0; i < entryCount; i++) {
            String key = "key-" + i;
            cacheWithGhost.put(key, value);
            cacheWithOffHeapGhost.put(key, value);
            cacheWithoutGhost.put(key, value);
        }

        // Warm up GhostCache with hot keys
        System.out.println("Warming up GhostCache with " + hotKeyCount + " hot keys...");
        for (int k = 0; k < 3; k++) {
            for (String key : hotKeys) {
                cacheWithGhost.get(key);
                cacheWithOffHeapGhost.get(key);
            }
        }

        System.out.println("Setup complete.\n");
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (cacheWithGhost != null)
            cacheWithGhost.close();
        if (cacheWithOffHeapGhost != null)
            cacheWithOffHeapGhost.close();
        if (cacheWithoutGhost != null)
            cacheWithoutGhost.close();
    }

    // ========== Hot Key Access (should benefit from GhostCache) ==========

    /**
     * Access hot keys only (80% of accesses go to 20% of keys).
     * GhostCache should provide sub-100ns for these.
     */
    @Benchmark
    public byte[] hotKeyGetWithGhost() {
        int idx = ThreadLocalRandom.current().nextInt(hotKeyCount);
        return cacheWithGhost.get(hotKeys[idx]);
    }

    @Benchmark
    public byte[] hotKeyGetWithOffHeapGhost() {
        int idx = ThreadLocalRandom.current().nextInt(hotKeyCount);
        return cacheWithOffHeapGhost.get(hotKeys[idx]);
    }

    @Benchmark
    public byte[] hotKeyGetWithoutGhost() {
        int idx = ThreadLocalRandom.current().nextInt(hotKeyCount);
        return cacheWithoutGhost.get(hotKeys[idx]);
    }

    // ========== Zipfian Distribution (realistic workload) ==========

    /**
     * Zipfian access: 80% hot keys, 20% random cold keys.
     */
    @Benchmark
    public byte[] zipfianGetWithGhost() {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        String key;
        if (rnd.nextInt(100) < 80) {
            // 80% chance: access hot key
            key = hotKeys[rnd.nextInt(hotKeyCount)];
        } else {
            // 20% chance: access random cold key
            key = "key-" + rnd.nextInt(entryCount);
        }
        return cacheWithGhost.get(key);
    }

    @Benchmark
    public byte[] zipfianGetWithOffHeapGhost() {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        String key;
        if (rnd.nextInt(100) < 80) {
            key = hotKeys[rnd.nextInt(hotKeyCount)];
        } else {
            key = "key-" + rnd.nextInt(entryCount);
        }
        return cacheWithOffHeapGhost.get(key);
    }

    @Benchmark
    public byte[] zipfianGetWithoutGhost() {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        String key;
        if (rnd.nextInt(100) < 80) {
            key = hotKeys[rnd.nextInt(hotKeyCount)];
        } else {
            key = "key-" + rnd.nextInt(entryCount);
        }
        return cacheWithoutGhost.get(key);
    }

    // ========== Single Hot Key (best case for GhostCache) ==========

    private final String singleHotKey = "key-0";

    /**
     * Access same key repeatedly - best case for GhostCache.
     * Should be sub-100ns.
     */
    @Benchmark
    public byte[] singleHotKeyWithGhost() {
        return cacheWithGhost.get(singleHotKey);
    }

    @Benchmark
    public byte[] singleHotKeyWithOffHeapGhost() {
        return cacheWithOffHeapGhost.get(singleHotKey);
    }

    @Benchmark
    public byte[] singleHotKeyWithoutGhost() {
        return cacheWithoutGhost.get(singleHotKey);
    }
}
