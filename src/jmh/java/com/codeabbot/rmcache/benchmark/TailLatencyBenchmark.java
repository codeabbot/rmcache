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
import org.openjdk.jmh.annotations.*;

import java.io.IOException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Tail-latency (percentile) comparison using JMH {@code SampleTime} mode, which records
 * a distribution of individual call latencies and reports p50 / p90 / p99 / p99.9 / max.
 * This is the metric that matters for latency-sensitive services: RMCache's lock-free
 * optimistic reads and Robin Hood probing are designed to keep the tail flat at scale,
 * and an average alone hides that.
 *
 * <p>Scope is deliberately narrow so the story is clear. The off-heap rival worth
 * comparing tails against is Chronicle Map; Caffeine is the on-heap reference ceiling.
 * MapDB and EhCache are excluded — their averages are already 5-15x slower, so their
 * tails add no insight. OHC stays in its own class ({@link OHCComparisonBenchmark}) so
 * its {@code Unsafe}-based allocator cannot destabilize this shared trial.
 *
 * <p>Run: {@code ./gradlew jmh -Pjmh.includes="TailLatencyBenchmark"}
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 2, time = 3)
@Measurement(iterations = 4, time = 5)
@Fork(1)
@Threads(4)
public class TailLatencyBenchmark {

    @Param({"1000000"})
    public int entryCount = 1_000_000;

    @Param("256")
    public int valueSize = 256;

    private OffHeapCache<String, byte[]> rmcache;
    private OffHeapCache<String, byte[]> rmcacheGhost;
    private ChronicleMap<String, byte[]> chronicleMap;
    private Cache<String, byte[]> caffeine;

    @Setup(Level.Trial)
    public void setup() throws IOException {
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

        chronicleMap = ChronicleMapFactory.createMap(entryCount * 2, valueSize);
        caffeine = Caffeine.newBuilder().maximumSize(entryCount * 2L).build();

        byte[] value = new byte[valueSize];
        for (int i = 0; i < entryCount; i++) {
            String key = "key-" + i;
            rmcache.put(key, value);
            rmcacheGhost.put(key, value);
            chronicleMap.put(key, value);
            caffeine.put(key, value);
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (rmcache != null) rmcache.close();
        if (rmcacheGhost != null) rmcacheGhost.close();
        if (chronicleMap != null) chronicleMap.close();
    }

    // ── GET tail ─────────────────────────────────────────────────────────────

    @Benchmark
    public byte[] rmcacheGet() {
        return rmcache.get("key-" + ThreadLocalRandom.current().nextInt(entryCount));
    }

    @Benchmark
    public byte[] rmcacheGhostGet() {
        return rmcacheGhost.get("key-" + ThreadLocalRandom.current().nextInt(entryCount));
    }

    @Benchmark
    public byte[] chronicleGet() {
        return chronicleMap.get("key-" + ThreadLocalRandom.current().nextInt(entryCount));
    }

    @Benchmark
    public byte[] caffeineGet() {
        return caffeine.getIfPresent("key-" + ThreadLocalRandom.current().nextInt(entryCount));
    }

    // ── PUT tail ─────────────────────────────────────────────────────────────

    @Benchmark
    public void rmcachePut() {
        rmcache.put("key-" + ThreadLocalRandom.current().nextInt(entryCount), new byte[valueSize]);
    }

    @Benchmark
    public void rmcacheGhostPut() {
        rmcacheGhost.put("key-" + ThreadLocalRandom.current().nextInt(entryCount), new byte[valueSize]);
    }

    @Benchmark
    public void chroniclePut() {
        chronicleMap.put("key-" + ThreadLocalRandom.current().nextInt(entryCount), new byte[valueSize]);
    }

    @Benchmark
    public void caffeinePut() {
        caffeine.put("key-" + ThreadLocalRandom.current().nextInt(entryCount), new byte[valueSize]);
    }
}
