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
import com.codeabbot.rmcache.OffHeapCache;
import com.codeabbot.rmcache.serializer.BuiltInSerializers;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * <b>Hit-rate quality</b> simulation (not a throughput benchmark): single-threaded, it replays one
 * identical request sequence through RMCache's default eviction, Caffeine's W-TinyLFU, and a plain
 * access-order LRU at matched capacities, and reports the resulting hit rates. This is the guardrail
 * for any future eviction <em>speed</em> change — it must not regress eviction <em>quality</em>.
 *
 * <p>Workloads: Zipfian (the realistic skewed-popularity case, where good admission shows its value)
 * across cache/universe ratios and skews, plus a looping scan (the classic recency-policy killer).
 *
 * <p>Run via the JMH jar's classpath (Caffeine + core are on it):
 * {@code java -cp build/libs/rmcache-0.0.2-jmh.jar --enable-native-access=ALL-UNNAMED \
 *   com.codeabbot.rmcache.benchmark.HitRateSimulation}
 */
public final class HitRateSimulation {

    private static final int VALUE = 1; // hit rate is value-independent; use a constant

    public static void main(String[] args) {
        int universe = 1_000_000;
        int requests = 8_000_000;
        int[] caps = {10_000, 100_000}; // 1% and 10% of the universe
        double[] skews = {0.80, 0.99};

        System.out.printf("# Hit-rate simulation — Zipfian, universe=%,d, requests=%,d%n", universe, requests);
        System.out.println("# 'size' = actual resident entries; Caffeine@rm matches Caffeine to RMCache's true size (fair).");
        System.out.printf("%-8s %-5s  %-18s %-18s %-12s %-8s%n",
                "cap", "skew", "RMCache(size)", "Caffeine(size)", "Caffeine@rm", "LRU");
        System.out.println("-".repeat(78));
        for (double skew : skews) {
            int[] seq = zipfianSequence(universe, requests, skew, 42L);
            for (int cap : caps) {
                Result rm = rmcache(seq, cap);
                Result cf = caffeine(seq, cap);
                Result cfNorm = caffeine(seq, (int) rm.size); // Caffeine sized to RMCache's actual resident
                Result lru = lru(seq, cap);
                System.out.printf("%-8d %-5.2f  %6.2f%% (%,8d) %6.2f%% (%,8d) %10.2f%% %6.2f%%%n",
                        cap, skew, rm.hitRate * 100, rm.size, cf.hitRate * 100, cf.size,
                        cfNorm.hitRate * 100, lru.hitRate * 100);
            }
        }

        // Looping scan: working set 10% larger than the cache, accessed in a cycle.
        int cap = 100_000;
        int workingSet = 110_000;
        int[] loop = loopSequence(workingSet, requests);
        Result rmL = rmcache(loop, cap);
        Result cfL = caffeine(loop, cap);
        Result cfLNorm = caffeine(loop, (int) rmL.size);
        Result lruL = lru(loop, cap);
        System.out.printf("%n# Looping scan — workingSet=%,d, cap=%,d (recency-policy stress)%n", workingSet, cap);
        System.out.printf("%-8s %-5s  %-18s %-18s %-12s %-8s%n",
                "", "", "RMCache(size)", "Caffeine(size)", "Caffeine@rm", "LRU");
        System.out.println("-".repeat(78));
        System.out.printf("loop                %6.2f%% (%,8d) %6.2f%% (%,8d) %10.2f%% %6.2f%%%n",
                rmL.hitRate * 100, rmL.size, cfL.hitRate * 100, cfL.size, cfLNorm.hitRate * 100, lruL.hitRate * 100);
    }

    private record Result(double hitRate, long size) {
    }

    private static int[] zipfianSequence(int universe, int requests, double skew, long seed) {
        double[] cdf = new double[universe];
        double sum = 0;
        for (int i = 0; i < universe; i++) {
            sum += 1.0 / Math.pow(i + 1, skew);
            cdf[i] = sum;
        }
        Random rng = new Random(seed);
        int[] seq = new int[requests];
        for (int r = 0; r < requests; r++) {
            double u = rng.nextDouble() * sum;
            int lo = 0, hi = universe - 1;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (cdf[mid] < u) lo = mid + 1;
                else hi = mid;
            }
            seq[r] = lo; // popularity rank used directly as the key
        }
        return seq;
    }

    private static int[] loopSequence(int workingSet, int requests) {
        int[] seq = new int[requests];
        for (int r = 0; r < requests; r++) {
            seq[r] = r % workingSet;
        }
        return seq;
    }

    private static Result rmcache(int[] seq, int cap) {
        OffHeapCache<Integer, Integer> cache = new CacheBuilder<Integer, Integer>()
                .offHeapMemory(Math.max(8L << 20, (long) cap * 460L))
                .maxEntries(cap)
                .keySerializer(BuiltInSerializers.INT_KEY)
                .valueSerializer(BuiltInSerializers.INT_VALUE)
                .build();
        long hits = 0;
        for (int k : seq) {
            if (cache.get(k) != null) {
                hits++;
            } else {
                cache.put(k, VALUE);
            }
        }
        long size = cache.size();
        cache.close();
        return new Result((double) hits / seq.length, size);
    }

    private static Result caffeine(int[] seq, int cap) {
        Cache<Integer, Integer> cache = Caffeine.newBuilder().maximumSize(cap).build();
        long hits = 0;
        for (int k : seq) {
            if (cache.getIfPresent(k) != null) {
                hits++;
            } else {
                cache.put(k, VALUE);
            }
        }
        cache.cleanUp(); // settle pending evictions before measuring resident size
        return new Result((double) hits / seq.length, cache.estimatedSize());
    }

    private static Result lru(int[] seq, int cap) {
        LinkedHashMap<Integer, Integer> cache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, Integer> eldest) {
                return size() > cap;
            }
        };
        long hits = 0;
        for (int k : seq) {
            if (cache.get(k) != null) {
                hits++;
            } else {
                cache.put(k, VALUE);
            }
        }
        return new Result((double) hits / seq.length, cache.size());
    }

    private HitRateSimulation() {
    }
}
