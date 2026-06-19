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
import org.caffinitas.ohc.CacheSerializer;
import org.caffinitas.ohc.OHCache;
import org.caffinitas.ohc.OHCacheBuilder;
import org.ehcache.CacheManager;
import org.ehcache.config.builders.CacheConfigurationBuilder;
import org.ehcache.config.builders.CacheManagerBuilder;
import org.ehcache.config.builders.ResourcePoolsBuilder;
import org.ehcache.config.units.MemoryUnit;
import org.mapdb.DB;
import org.mapdb.HTreeMap;
import org.openjdk.jmh.annotations.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Sustained-throughput comparison (ops/sec, higher is better) across all peers, at 4
 * threads — i.e. how many GET/PUT operations per second each cache sustains under
 * continuous load. Same caches, sizing and value size as
 * {@link FairComparisonScaleBenchmark}, but JMH {@code Throughput} mode instead of
 * average latency. All caches are pre-allocated and use no eviction, so this measures the
 * read/write fast path, not eviction.
 *
 * <p>Run: {@code ./gradlew jmh -Pjmh.includes="ThroughputComparisonBenchmark"}
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 1, time = 2)
@Measurement(iterations = 2, time = 3)
@Fork(1)
@Threads(4)
public class ThroughputComparisonBenchmark {

    @Param({"100000", "1000000"})
    public int entryCount = 100000;

    @Param("256")
    public int valueSize = 256;

    private OffHeapCache<String, byte[]> rmcache;
    private OffHeapCache<String, byte[]> rmcacheGhost;
    private Cache<String, byte[]> caffeine;
    private ChronicleMap<String, byte[]> chronicleMap;
    private OHCache<String, byte[]> ohc;
    private DB mapDB;
    private HTreeMap<String, byte[]> mapDBMap;
    private org.ehcache.Cache<String, byte[]> ehcache;
    private CacheManager ehcacheManager;

    static final class OhcStringSer implements CacheSerializer<String> {
        @Override public void serialize(String s, ByteBuffer buf) { buf.put(s.getBytes(StandardCharsets.UTF_8)); }
        @Override public String deserialize(ByteBuffer buf) { byte[] b = new byte[buf.remaining()]; buf.get(b); return new String(b, StandardCharsets.UTF_8); }
        @Override public int serializedSize(String s) { return s.getBytes(StandardCharsets.UTF_8).length; }
    }

    static final class OhcBytesSer implements CacheSerializer<byte[]> {
        @Override public void serialize(byte[] v, ByteBuffer buf) { buf.put(v); }
        @Override public byte[] deserialize(ByteBuffer buf) { byte[] b = new byte[buf.remaining()]; buf.get(b); return b; }
        @Override public int serializedSize(byte[] v) { return v.length; }
    }

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

        caffeine = Caffeine.newBuilder().maximumSize(entryCount * 2L).build();
        chronicleMap = ChronicleMapFactory.createMap(entryCount * 2, valueSize);
        ohc = OHCacheBuilder.<String, byte[]>newBuilder()
                .keySerializer(new OhcStringSer())
                .valueSerializer(new OhcBytesSer())
                .capacity(8L << 30)
                .build();
        mapDB = MapDBFactory.createDB();
        mapDBMap = MapDBFactory.createMap(mapDB);

        ehcacheManager = CacheManagerBuilder.newCacheManagerBuilder().build(true);
        ehcache = ehcacheManager.createCache("throughputCache",
                CacheConfigurationBuilder.newCacheConfigurationBuilder(
                        String.class, byte[].class,
                        ResourcePoolsBuilder.newResourcePoolsBuilder().offheap(512, MemoryUnit.MB))
                        .build());

        byte[] value = new byte[valueSize];
        for (int i = 0; i < entryCount; i++) {
            String key = "key-" + i;
            rmcache.put(key, value);
            rmcacheGhost.put(key, value);
            caffeine.put(key, value);
            chronicleMap.put(key, value);
            ohc.put(key, value);
            mapDBMap.put(key, value);
            ehcache.put(key, value);
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() throws IOException {
        if (rmcache != null) rmcache.close();
        if (rmcacheGhost != null) rmcacheGhost.close();
        if (chronicleMap != null) chronicleMap.close();
        if (ohc != null) ohc.close();
        if (mapDB != null) mapDB.close();
        if (ehcacheManager != null) ehcacheManager.close();
    }

    private static int idx(int n) { return ThreadLocalRandom.current().nextInt(n); }

    @Benchmark public byte[] rmcacheGet()      { return rmcache.get("key-" + idx(entryCount)); }
    @Benchmark public byte[] rmcacheGhostGet() { return rmcacheGhost.get("key-" + idx(entryCount)); }
    @Benchmark public byte[] caffeineGet()     { return caffeine.getIfPresent("key-" + idx(entryCount)); }
    @Benchmark public byte[] chronicleGet()    { return chronicleMap.get("key-" + idx(entryCount)); }
    @Benchmark public byte[] ohcGet()          { return ohc.get("key-" + idx(entryCount)); }
    @Benchmark public byte[] mapdbGet()        { return mapDBMap.get("key-" + idx(entryCount)); }
    @Benchmark public byte[] ehcacheGet()      { return ehcache.get("key-" + idx(entryCount)); }

    @Benchmark public void rmcachePut()      { rmcache.put("key-" + idx(entryCount), new byte[valueSize]); }
    @Benchmark public void rmcacheGhostPut() { rmcacheGhost.put("key-" + idx(entryCount), new byte[valueSize]); }
    @Benchmark public void caffeinePut()     { caffeine.put("key-" + idx(entryCount), new byte[valueSize]); }
    @Benchmark public void chroniclePut()    { chronicleMap.put("key-" + idx(entryCount), new byte[valueSize]); }
    @Benchmark public void ohcPut()          { ohc.put("key-" + idx(entryCount), new byte[valueSize]); }
    @Benchmark public void mapdbPut()        { mapDBMap.put("key-" + idx(entryCount), new byte[valueSize]); }
    @Benchmark public void ehcachePut()      { ehcache.put("key-" + idx(entryCount), new byte[valueSize]); }
}
