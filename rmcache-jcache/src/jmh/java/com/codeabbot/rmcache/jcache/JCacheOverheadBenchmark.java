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
package com.codeabbot.rmcache.jcache;

import com.codeabbot.rmcache.CacheBuilder;
import com.codeabbot.rmcache.GhostCacheMode;
import com.codeabbot.rmcache.OffHeapCache;
import com.codeabbot.rmcache.serializer.BuiltInSerializers;

import javax.cache.Cache;
import javax.cache.CacheManager;
import javax.cache.Caching;
import javax.cache.spi.CachingProvider;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Measures the overhead of the JSR-107 adapter: RMCache accessed directly via
 * {@link OffHeapCache} vs. the same cache accessed through the {@link Cache javax.cache.Cache}
 * API. Both caches use identical serializers, sizing, eviction and ghost settings — the JCache
 * path's only extras are the {@code Cache} indirection, the per-key striped write lock, and the
 * expiry/null checks. The off-heap <em>core is untouched by the JCache module</em>, so {@code
 * directGet}/{@code directPut} here reproduce main's numbers; the delta is the adapter tax.
 *
 * <p>Run: {@code ./gradlew :rmcache-jcache:jmh -Pjmh.includes="JCacheOverheadBenchmark"}
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 1, time = 2)
@Measurement(iterations = 2, time = 3)
@Fork(1)
@Threads(4)
public class JCacheOverheadBenchmark {

    @Param({"100000", "1000000"})
    public int entryCount;

    @Param("256")
    public int valueSize;

    private OffHeapCache<String, byte[]> direct;
    private Cache<String, byte[]> jcache;
    private CachingProvider provider;
    private CacheManager cacheManager;

    @Setup(Level.Trial)
    public void setup() {
        int maxEntries = entryCount + entryCount / 4;
        // Comfortably above RMCache's floor of maxEntries * (avgKey 32 + avgValue 256 + 24).
        long offHeapBytes = (long) maxEntries * 360L;

        // Direct cache: byte-for-byte the same configuration the JCache wrapper builds internally.
        direct = new CacheBuilder<String, byte[]>()
                .offHeapMemory(offHeapBytes)
                .maxEntries(maxEntries)
                .keySerializer(BuiltInSerializers.STRING_KEY)
                .valueSerializer(BuiltInSerializers.byteArray())
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .build();

        provider = Caching.getCachingProvider();
        cacheManager = provider.getCacheManager();
        jcache = cacheManager.createCache("overhead",
                new RMCacheConfiguration<String, byte[]>()
                        .setTypes(String.class, byte[].class)
                        .setOffHeapMemoryBytes(offHeapBytes)
                        .setMaxEntries(maxEntries));

        byte[] value = new byte[valueSize];
        for (int i = 0; i < entryCount; i++) {
            String key = "key-" + i;
            direct.put(key, value);
            jcache.put(key, value);
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (direct != null) direct.close();
        if (cacheManager != null) cacheManager.close();
        if (provider != null) provider.close();
    }

    private static int idx(int n) {
        return ThreadLocalRandom.current().nextInt(n);
    }

    @Benchmark public byte[] directGet() { return direct.get("key-" + idx(entryCount)); }
    @Benchmark public byte[] jcacheGet() { return jcache.get("key-" + idx(entryCount)); }

    @Benchmark public void directPut() { direct.put("key-" + idx(entryCount), new byte[valueSize]); }
    @Benchmark public void jcachePut() { jcache.put("key-" + idx(entryCount), new byte[valueSize]); }
}
