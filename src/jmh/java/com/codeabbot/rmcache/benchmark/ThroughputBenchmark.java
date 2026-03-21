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
import net.openhft.chronicle.map.ChronicleMap;
import org.mapdb.DB;
import org.mapdb.HTreeMap;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Throughput benchmark: ops/second across 10k / 100k / 1M entry scales.
 *
 * <p>Pre-creates keys and values to avoid allocation on the measurement path.
 * RMCache zero-copy path ({@code getZeroCopy}) is included as an additional
 * RMCache-specific data point.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 2)
@Measurement(iterations = 3, time = 3)
@Fork(1)
@Threads(4)
public class ThroughputBenchmark {

    @Param({"10000", "100000", "1000000"})
    public int entryCount = 10000;

    @Param("256")
    public int valueSize = 256;

    private OffHeapCache<String, byte[]> rmcache;
    private DB mapDB;
    private HTreeMap<String, byte[]> mapDBMap;
    private ChronicleMap<String, byte[]> chronicleMap;

    private String[] keys;
    private byte[][] values;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        System.out.println("\n--- Setup Throughput Benchmark for " + entryCount + " entries ---");

        keys = new String[entryCount];
        for (int i = 0; i < entryCount; i++) {
            keys[i] = "key-" + i;
        }
        values = new byte[1024][valueSize];
        for (int i = 0; i < 1024; i++) {
            values[i] = new byte[valueSize];
        }

        // ── RMCache ───────────────────────────────────────────────────────────
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

        // ── MapDB ─────────────────────────────────────────────────────────────
        mapDB = MapDBFactory.createDB();
        mapDBMap = MapDBFactory.createMap(mapDB);

        // ── ChronicleMap ──────────────────────────────────────────────────────
        chronicleMap = ChronicleMapFactory.createMap(entryCount * 2, valueSize);

        // ── Pre-populate ──────────────────────────────────────────────────────
        for (int i = 0; i < entryCount; i++) {
            byte[] v = values[i % 1024];
            rmcache.put(keys[i], v);
            mapDBMap.put(keys[i], v);
            chronicleMap.put(keys[i], v);
        }
        System.out.println("--- Setup Complete ---\n");
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (rmcache != null) rmcache.close();
        if (mapDB != null) mapDB.close();
        if (chronicleMap != null) chronicleMap.close();
    }

    // ── RMCache ───────────────────────────────────────────────────────────────

    @Benchmark
    public byte[] rmcacheGet() {
        int idx = ThreadLocalRandom.current().nextInt(entryCount);
        return rmcache.get(keys[idx]);
    }

    /** Zero-copy read — no intermediate byte[] allocation. */
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

    // ── MapDB ─────────────────────────────────────────────────────────────────

    @Benchmark
    public byte[] mapdbGet() {
        int idx = ThreadLocalRandom.current().nextInt(entryCount);
        return mapDBMap.get(keys[idx]);
    }

    @Benchmark
    public void mapdbPut() {
        int idx = ThreadLocalRandom.current().nextInt(entryCount);
        mapDBMap.put(keys[idx], values[idx % 1024]);
    }

    // ── ChronicleMap ──────────────────────────────────────────────────────────

    @Benchmark
    public byte[] chronicleGet() {
        int idx = ThreadLocalRandom.current().nextInt(entryCount);
        return chronicleMap.get(keys[idx]);
    }

    @Benchmark
    public void chroniclePut() {
        int idx = ThreadLocalRandom.current().nextInt(entryCount);
        chronicleMap.put(keys[idx], values[idx % 1024]);
    }
}
