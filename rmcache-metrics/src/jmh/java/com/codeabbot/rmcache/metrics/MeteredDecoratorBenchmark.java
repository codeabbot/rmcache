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
package com.codeabbot.rmcache.metrics;

import com.codeabbot.rmcache.CacheBuilder;
import com.codeabbot.rmcache.OffHeapCache;
import com.codeabbot.rmcache.Units;
import com.codeabbot.rmcache.eviction.NoEvictionPolicy;
import com.codeabbot.rmcache.serializer.BuiltInSerializers;
import com.codeabbot.rmcache.serializer.StringEncoding;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Measures the per-operation overhead the Tier 1 {@link MeteredOffHeapCache} decorator
 * adds. Compares the raw core cache against the same cache wrapped with counting only and
 * with counting + 1-in-1024 latency sampling. All three wrap identically-configured core
 * caches, so any latency delta is purely the decorator's instrumentation cost.
 *
 * <p>Run: {@code ./gradlew :rmcache-metrics:jmh}
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 1, time = 2)
@Measurement(iterations = 2, time = 3)
@Fork(1)
@Threads(4)
public class MeteredDecoratorBenchmark {

    @Param({"100000", "1000000"})
    public int entryCount;

    @Param("256")
    public int valueSize;

    private OffHeapCache<String, byte[]> raw;
    private OffHeapCache<String, byte[]> metered;   // counting only
    private OffHeapCache<String, byte[]> sampled;   // counting + 1-in-1024 latency

    @Setup(Level.Trial)
    public void setup() {
        raw = build();
        metered = new MeteredOffHeapCache<>(build());
        sampled = new MeteredOffHeapCache<>(build(), 1024);
        byte[] value = new byte[valueSize];
        for (int i = 0; i < entryCount; i++) {
            String k = "key-" + i;
            raw.put(k, value);
            metered.put(k, value);
            sampled.put(k, value);
        }
    }

    private OffHeapCache<String, byte[]> build() {
        return new CacheBuilder<String, byte[]>()
                .offHeapMemory(Units.gigabytes(8))
                .maxEntries(entryCount * 2)
                .stringKeyEncoding(StringEncoding.LATIN1)
                .valueSerializer(BuiltInSerializers.byteArray())
                .eviction(new NoEvictionPolicy())
                .hashTableLoadFactor(0.5d)
                .build();
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (raw != null) raw.close();
        if (metered != null) metered.close();
        if (sampled != null) sampled.close();
    }

    @Benchmark
    public byte[] rawGet() {
        return raw.get("key-" + ThreadLocalRandom.current().nextInt(entryCount));
    }

    @Benchmark
    public byte[] meteredGet() {
        return metered.get("key-" + ThreadLocalRandom.current().nextInt(entryCount));
    }

    @Benchmark
    public byte[] sampledGet() {
        return sampled.get("key-" + ThreadLocalRandom.current().nextInt(entryCount));
    }

    @Benchmark
    public void rawPut() {
        raw.put("key-" + ThreadLocalRandom.current().nextInt(entryCount), new byte[valueSize]);
    }

    @Benchmark
    public void meteredPut() {
        metered.put("key-" + ThreadLocalRandom.current().nextInt(entryCount), new byte[valueSize]);
    }

    @Benchmark
    public void sampledPut() {
        sampled.put("key-" + ThreadLocalRandom.current().nextInt(entryCount), new byte[valueSize]);
    }
}
