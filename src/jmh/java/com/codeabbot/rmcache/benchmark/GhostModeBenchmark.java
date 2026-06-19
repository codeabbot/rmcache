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
import com.codeabbot.rmcache.eviction.NoEvictionPolicy;
import com.codeabbot.rmcache.serializer.BuiltInSerializers;
import org.openjdk.jmh.annotations.*;

import java.lang.ref.Reference;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Ghost-cache mode comparison: {@code DISABLED} vs on-heap {@code HEAP} (the default) vs
 * {@code OFF_HEAP} (what the published comparison benchmark used). Throughput (ops/s, higher
 * better) for get/put; {@link #main} reports the on-heap footprint each mode adds.
 *
 * <p>Run perf: {@code ./gradlew jmh -Pjmh.includes="GhostModeBenchmark"}<br>
 * Run heap:  {@code java -cp build/libs/rmcache-0.0.2-jmh.jar --enable-native-access=ALL-UNNAMED
 *   com.codeabbot.rmcache.benchmark.GhostModeBenchmark}
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 1, time = 2)
@Measurement(iterations = 2, time = 3)
@Fork(1)
@Threads(4)
public class GhostModeBenchmark {

    @Param({"DISABLED", "HEAP", "OFF_HEAP"})
    public String ghostMode;

    @Param({"1000000"})
    public int entryCount;

    @Param("256")
    public int valueSize;

    private OffHeapCache<String, byte[]> cache;

    private static OffHeapCache<String, byte[]> build(String ghostMode, int entryCount) {
        CacheBuilder<String, byte[]> b = new CacheBuilder<String, byte[]>()
                .offHeapMemory((long) entryCount * 460L)
                .maxEntries(entryCount)
                .keySerializer(BuiltInSerializers.STRING_KEY)
                .valueSerializer(BuiltInSerializers.byteArray())
                .eviction(new NoEvictionPolicy())
                .ghostCacheMode(GhostCacheMode.valueOf(ghostMode));
        if (!"DISABLED".equals(ghostMode)) {
            b.ghostCacheSize(entryCount);
        }
        return b.build();
    }

    @Setup(Level.Trial)
    public void setup() {
        cache = build(ghostMode, entryCount);
        byte[] v = new byte[valueSize];
        for (int i = 0; i < entryCount; i++) {
            cache.put("key-" + i, v);
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (cache != null) {
            cache.close();
        }
    }

    private static int idx(int n) {
        return ThreadLocalRandom.current().nextInt(n);
    }

    @Benchmark
    public byte[] get() {
        return cache.get("key-" + idx(entryCount));
    }

    @Benchmark
    public void put() {
        cache.put("key-" + idx(entryCount), new byte[valueSize]);
    }

    /** Measures the retained on-heap footprint each ghost mode adds (cache held live, post-GC). */
    public static void main(String[] args) throws Exception {
        int n = 1_000_000, vs = 256;
        System.out.printf("# On-heap footprint by ghost mode (maxEntries=%,d, ghostCacheSize=%,d)%n", n, n);
        long baseline = -1;
        for (String mode : new String[]{"DISABLED", "OFF_HEAP", "HEAP"}) {
            settle();
            OffHeapCache<String, byte[]> c = build(mode, n);
            byte[] v = new byte[vs];
            for (int i = 0; i < n; i++) {
                c.put("key-" + i, v);
            }
            settle();
            long used = usedHeap();
            if (baseline < 0) baseline = used; // DISABLED is the baseline
            System.out.printf("ghost=%-9s  onHeapUsed=%,d MB  (delta vs DISABLED = %+,d MB)%n",
                    mode, used / 1048576, (used - baseline) / 1048576);
            Reference.reachabilityFence(c);
            c.close();
        }
    }

    private static void settle() throws InterruptedException {
        for (int i = 0; i < 3; i++) {
            System.gc();
            Thread.sleep(250);
        }
    }

    private static long usedHeap() {
        Runtime r = Runtime.getRuntime();
        return r.totalMemory() - r.freeMemory();
    }
}
