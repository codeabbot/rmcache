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
import com.codeabbot.rmcache.serializer.StringEncoding;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.openhft.chronicle.map.ChronicleMap;
import org.ehcache.CacheManager;
import org.ehcache.config.builders.CacheConfigurationBuilder;
import org.ehcache.config.builders.CacheManagerBuilder;
import org.ehcache.config.builders.ResourcePoolsBuilder;
import org.ehcache.config.units.MemoryUnit;
import org.mapdb.DB;
import org.mapdb.HTreeMap;
import org.openjdk.jmh.annotations.*;

import java.io.IOException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Fair latency benchmark across 10k / 100k / 1M entry scales.
 *
 * <p>Competitors: RMCache (plain + OFF_HEAP GhostCache), MapDB, ChronicleMap, EhCache.
 * All caches pre-allocated at 8 GB to avoid eviction during measurement.
 * No eviction policy — pure read/write latency, no eviction overhead.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 1, time = 2)
@Measurement(iterations = 2, time = 3)
@Fork(1)
@Threads(4)
public class FairComparisonScaleBenchmark {

    @Param({"10000", "100000", "1000000"})
    public int entryCount = 10000;

    @Param("256")
    public int valueSize = 256;

    private OffHeapCache<String, byte[]> rmcache;
    private OffHeapCache<String, byte[]> rmcacheGhost;
    private DB mapDB;
    private HTreeMap<String, byte[]> mapDBMap;
    private ChronicleMap<String, byte[]> chronicleMap;
    private org.ehcache.Cache<String, byte[]> ehcache;
    private CacheManager ehcacheManager;
    private Cache<String, byte[]> caffeine;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        System.out.println("\nSetup scale test for " + entryCount + " entries...");

        // ── RMCache (no ghost cache) ──────────────────────────────────────────
        rmcache = new CacheBuilder<String, byte[]>()
                .offHeapMemory(Units.gigabytes(8))
                .maxEntries(entryCount * 2)
                .stringKeyEncoding(StringEncoding.LATIN1)
                .valueSerializer(BuiltInSerializers.byteArray())
                .eviction(new NoEvictionPolicy())
                .hashTableLoadFactor(0.5d)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .ghostCacheSize(0)
                .withCacheName("benchmark")
                .build();

        // ── RMCache (with OFF_HEAP GhostCache) ───────────────────────────────
        rmcacheGhost = new CacheBuilder<String, byte[]>()
                .offHeapMemory(Units.gigabytes(8))
                .maxEntries(entryCount * 2)
                .stringKeyEncoding(StringEncoding.LATIN1)
                .valueSerializer(BuiltInSerializers.byteArray())
                .eviction(new NoEvictionPolicy())
                .hashTableLoadFactor(0.5d)
                .ghostCacheMode(GhostCacheMode.OFF_HEAP)
                .ghostCacheSize(entryCount * 2)
                .build();

        // ── MapDB (off-heap memory map) ───────────────────────────────────────
        mapDB = MapDBFactory.createDB();
        mapDBMap = MapDBFactory.createMap(mapDB);

        // ── ChronicleMap (off-heap mmap) ──────────────────────────────────────
        chronicleMap = ChronicleMapFactory.createMap(entryCount * 2, valueSize);

        // ── EhCache (off-heap) ────────────────────────────────────────────────
        ehcacheManager = CacheManagerBuilder.newCacheManagerBuilder().build(true);
        ehcache = ehcacheManager.createCache("benchmarkCache",
                CacheConfigurationBuilder.newCacheConfigurationBuilder(
                        String.class, byte[].class,
                        ResourcePoolsBuilder.newResourcePoolsBuilder()
                                .offheap(512, MemoryUnit.MB))
                        .build());

        // ── Caffeine (on-heap reference; on-heap caps the achievable latency) ─
        caffeine = Caffeine.newBuilder().maximumSize(entryCount * 2L).build();

        // ── Pre-populate ──────────────────────────────────────────────────────
        byte[] value = new byte[valueSize];
        for (int i = 0; i < entryCount; i++) {
            String key = "key-" + i;
            rmcache.put(key, value);
            rmcacheGhost.put(key, value);
            mapDBMap.put(key, value);
            chronicleMap.put(key, value);
            ehcache.put(key, value);
            caffeine.put(key, value);
        }
        System.out.println("Setup complete for " + entryCount + " entries.\n");
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (rmcache != null) rmcache.close();
        if (rmcacheGhost != null) rmcacheGhost.close();
        if (mapDB != null) mapDB.close();
        if (chronicleMap != null) chronicleMap.close();
        if (ehcacheManager != null) ehcacheManager.close();
    }

    // ── RMCache GET / PUT ────────────────────────────────────────────────────

    @Benchmark
    public byte[] rmcacheGet() {
        int idx = ThreadLocalRandom.current().nextInt(entryCount);
        return rmcache.get("key-" + idx);
    }

    @Benchmark
    public byte[] rmcacheGhostGet() {
        int idx = ThreadLocalRandom.current().nextInt(entryCount);
        return rmcacheGhost.get("key-" + idx);
    }

    @Benchmark
    public void rmcachePut() {
        int idx = ThreadLocalRandom.current().nextInt(entryCount);
        rmcache.put("key-" + idx, new byte[valueSize]);
    }

    @Benchmark
    public void rmcacheGhostPut() {
        int idx = ThreadLocalRandom.current().nextInt(entryCount);
        rmcacheGhost.put("key-" + idx, new byte[valueSize]);
    }

    // ── MapDB GET / PUT ──────────────────────────────────────────────────────

    @Benchmark
    public byte[] mapdbGet() {
        int idx = ThreadLocalRandom.current().nextInt(entryCount);
        return mapDBMap.get("key-" + idx);
    }

    @Benchmark
    public void mapdbPut() {
        int idx = ThreadLocalRandom.current().nextInt(entryCount);
        mapDBMap.put("key-" + idx, new byte[valueSize]);
    }

    // ── ChronicleMap GET / PUT ───────────────────────────────────────────────

    @Benchmark
    public byte[] chronicleGet() {
        int idx = ThreadLocalRandom.current().nextInt(entryCount);
        return chronicleMap.get("key-" + idx);
    }

    @Benchmark
    public void chroniclePut() {
        int idx = ThreadLocalRandom.current().nextInt(entryCount);
        chronicleMap.put("key-" + idx, new byte[valueSize]);
    }

    // ── EhCache GET / PUT ────────────────────────────────────────────────────

    @Benchmark
    public byte[] ehcacheGet() {
        int idx = ThreadLocalRandom.current().nextInt(entryCount);
        return ehcache.get("key-" + idx);
    }

    @Benchmark
    public void ehcachePut() {
        int idx = ThreadLocalRandom.current().nextInt(entryCount);
        ehcache.put("key-" + idx, new byte[valueSize]);
    }

    // ── Caffeine GET / PUT (on-heap reference) ───────────────────────────────

    @Benchmark
    public byte[] caffeineGet() {
        int idx = ThreadLocalRandom.current().nextInt(entryCount);
        return caffeine.getIfPresent("key-" + idx);
    }

    @Benchmark
    public void caffeinePut() {
        int idx = ThreadLocalRandom.current().nextInt(entryCount);
        caffeine.put("key-" + idx, new byte[valueSize]);
    }
}
