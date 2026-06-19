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

import com.codeabbot.rmcache.OffHeapCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.cache.Cache;
import javax.cache.CacheManager;
import javax.cache.Caching;
import javax.cache.configuration.MutableConfiguration;
import javax.cache.expiry.Duration;
import javax.cache.expiry.ModifiedExpiryPolicy;
import javax.cache.processor.EntryProcessor;
import javax.cache.processor.MutableEntry;
import javax.cache.spi.CachingProvider;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RMCacheJCacheTest {

    private CachingProvider provider;
    private CacheManager cacheManager;

    @BeforeEach
    void setUp() {
        provider = Caching.getCachingProvider();
        cacheManager = provider.getCacheManager();
    }

    @AfterEach
    void tearDown() {
        cacheManager.close();
        provider.close();
    }

    private <K, V> Cache<K, V> newCache(String name, Class<K> k, Class<V> v) {
        return cacheManager.createCache(name,
                new MutableConfiguration<K, V>().setTypes(k, v));
    }

    @Test
    void providerIsDiscoveredViaSpi() {
        assertThat(provider).isInstanceOf(RMCacheCachingProvider.class);
        assertThat(provider.isSupported(javax.cache.configuration.OptionalFeature.STORE_BY_REFERENCE)).isFalse();
    }

    @Test
    void basicPutGetContainsRemove() {
        Cache<String, String> cache = newCache("basic", String.class, String.class);
        assertThat(cache.get("a")).isNull();
        cache.put("a", "1");
        assertThat(cache.get("a")).isEqualTo("1");
        assertThat(cache.containsKey("a")).isTrue();
        assertThat(cache.remove("a")).isTrue();
        assertThat(cache.containsKey("a")).isFalse();
    }

    @Test
    void conditionalOperations() {
        Cache<String, String> cache = newCache("conditional", String.class, String.class);

        assertThat(cache.putIfAbsent("k", "v1")).isTrue();
        assertThat(cache.putIfAbsent("k", "v2")).isFalse();
        assertThat(cache.get("k")).isEqualTo("v1");

        assertThat(cache.replace("k", "WRONG", "v3")).isFalse();
        assertThat(cache.replace("k", "v1", "v3")).isTrue();
        assertThat(cache.get("k")).isEqualTo("v3");

        assertThat(cache.replace("absent", "x")).isFalse();
        assertThat(cache.replace("k", "v4")).isTrue();

        assertThat(cache.getAndPut("k", "v5")).isEqualTo("v4");
        assertThat(cache.getAndReplace("k", "v6")).isEqualTo("v5");
        assertThat(cache.getAndRemove("k")).isEqualTo("v6");
        assertThat(cache.containsKey("k")).isFalse();

        cache.put("d", "x");
        assertThat(cache.remove("d", "WRONG")).isFalse();
        assertThat(cache.remove("d", "x")).isTrue();
    }

    @Test
    void getAllPutAllRemoveAll() {
        Cache<String, String> cache = newCache("bulk", String.class, String.class);
        cache.putAll(Map.of("a", "1", "b", "2", "c", "3"));
        Set<String> keys = new HashSet<>(Set.of("a", "b", "missing"));
        Map<String, String> got = cache.getAll(keys);
        assertThat(got).containsOnly(Map.entry("a", "1"), Map.entry("b", "2"));

        cache.removeAll(Set.of("a"));
        assertThat(cache.containsKey("a")).isFalse();
        cache.removeAll();
        assertThat(cache.containsKey("b")).isFalse();
    }

    @Test
    void invokeCreatesUpdatesAndRemoves() {
        Cache<String, Integer> cache = newCache("invoke", String.class, Integer.class);

        // create
        Integer created = cache.invoke("k", (MutableEntry<String, Integer> e, Object[] args) -> {
            assertThat(e.exists()).isFalse();
            assertThat(e.getValue()).isNull();
            e.setValue(10);
            return e.getValue();
        });
        assertThat(created).isEqualTo(10);
        assertThat(cache.get("k")).isEqualTo(10);

        // update
        cache.invoke("k", (e, args) -> {
            assertThat(e.exists()).isTrue();
            e.setValue(e.getValue() + 5);
            return null;
        });
        assertThat(cache.get("k")).isEqualTo(15);

        // remove
        cache.invoke("k", (e, args) -> {
            e.remove();
            assertThat(e.exists()).isFalse();
            return null;
        });
        assertThat(cache.containsKey("k")).isFalse();
    }

    @Test
    void invokeAllReturnsResults() {
        Cache<String, Integer> cache = newCache("invokeAll", String.class, Integer.class);
        cache.putAll(Map.of("a", 1, "b", 2));
        EntryProcessor<String, Integer, Integer> doubler = (e, args) -> {
            if (e.exists()) {
                e.setValue(e.getValue() * 2);
            }
            return e.getValue();
        };
        Map<String, javax.cache.processor.EntryProcessorResult<Integer>> results =
                cache.invokeAll(Set.of("a", "b"), doubler);
        assertThat(results.get("a").get()).isEqualTo(2);
        assertThat(results.get("b").get()).isEqualTo(4);
        assertThat(cache.get("a")).isEqualTo(2);
        assertThat(cache.get("b")).isEqualTo(4);
    }

    @Test
    void invokeIsAtomicUnderConcurrency() throws Exception {
        Cache<String, Integer> cache = newCache("counter", String.class, Integer.class);
        cache.put("c", 0);
        int threads = 4;
        int perThread = 2000;
        EntryProcessor<String, Integer, Void> increment = (e, args) -> {
            e.setValue(e.getValue() + 1);
            return null;
        };
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            Future<?>[] futures = new Future<?>[threads];
            for (int t = 0; t < threads; t++) {
                futures[t] = pool.submit(() -> {
                    for (int i = 0; i < perThread; i++) {
                        cache.invoke("c", increment);
                    }
                });
            }
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(cache.get("c")).isEqualTo(threads * perThread);
    }

    @Test
    void storeByValueIsolatesMutations() {
        Cache<String, int[]> cache = newCache("sbv", String.class, int[].class);
        int[] value = {1, 2, 3};
        cache.put("k", value);
        value[0] = 999; // mutate the original after put
        assertThat(cache.get("k")).containsExactly(1, 2, 3); // cached copy unaffected
        int[] got1 = cache.get("k");
        got1[1] = -1; // mutate a retrieved copy
        assertThat(cache.get("k")).containsExactly(1, 2, 3); // still unaffected
    }

    @Test
    void modifiedExpiryPolicyExpiresEntries() throws InterruptedException {
        Cache<String, String> cache = cacheManager.createCache("ttl",
                new MutableConfiguration<String, String>()
                        .setTypes(String.class, String.class)
                        .setExpiryPolicyFactory(ModifiedExpiryPolicy.factoryOf(
                                new Duration(TimeUnit.MILLISECONDS, 200))));
        cache.put("k", "v");
        assertThat(cache.get("k")).isEqualTo("v");
        Thread.sleep(800);
        assertThat(cache.get("k")).isNull();
    }

    @Test
    void statisticsExposedViaJmx() throws Exception {
        Cache<String, String> cache = cacheManager.createCache("stats",
                new MutableConfiguration<String, String>()
                        .setTypes(String.class, String.class)
                        .setStatisticsEnabled(true));
        cache.put("a", "1");
        cache.get("a");      // hit
        cache.get("missing"); // miss

        MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
        Set<ObjectName> names = mbs.queryNames(
                new ObjectName("javax.cache:type=CacheStatistics,*"), null);
        ObjectName statsName = names.stream()
                .filter(n -> n.getKeyProperty("Cache").contains("stats"))
                .findFirst().orElseThrow();
        assertThat((Long) mbs.getAttribute(statsName, "CacheHits")).isEqualTo(1L);
        assertThat((Long) mbs.getAttribute(statsName, "CacheMisses")).isEqualTo(1L);
        assertThat((Long) mbs.getAttribute(statsName, "CachePuts")).isGreaterThanOrEqualTo(1L);
    }

    @Test
    void unwrapExposesUnderlyingOffHeapCache() {
        Cache<String, String> cache = newCache("unwrap", String.class, String.class);
        OffHeapCache<?, ?> core = cache.unwrap(OffHeapCache.class);
        assertThat(core).isNotNull();
        assertThat(cache.unwrap(RMCacheCache.class)).isSameAs(cache);
    }

    @Test
    void closeMarksCacheClosedAndRejectsOps() {
        Cache<String, String> cache = newCache("closeme", String.class, String.class);
        assertThat(cache.isClosed()).isFalse();
        cache.close();
        assertThat(cache.isClosed()).isTrue();
        assertThatThrownBy(() -> cache.put("a", "1")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void iteratorAndListenersAreDeferredInPhase1() {
        Cache<String, String> cache = newCache("deferred", String.class, String.class);
        assertThatThrownBy(cache::iterator).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void integerTypedCacheUsesFastSerializers() {
        Cache<Integer, Long> cache = newCache("typed", Integer.class, Long.class);
        cache.put(42, 1_000_000_000_000L);
        assertThat(cache.get(42)).isEqualTo(1_000_000_000_000L);
        assertThat(cache.get(7)).isNull();
    }
}
