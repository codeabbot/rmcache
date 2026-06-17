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

import com.codeabbot.rmcache.CacheValueView;
import com.codeabbot.rmcache.OffHeapCache;

import java.lang.foreign.MemorySegment;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;

/**
 * A metrics decorator over any {@link OffHeapCache} that records operation counts and
 * (optionally) sampled latency, with a deliberately tiny per-operation cost.
 *
 * <p><b>Tier 1 — opt-in.</b> You only pay for it if you wrap your cache in it. The core
 * cache is never modified.
 *
 * <p><b>Counting</b> uses {@link LongAdder}s, which stripe across threads and stay
 * contention-free under load — each instrumented operation adds a single increment.
 * <b>Latency</b> is measured only on a 1-in-{@code latencySampleRate} sample (two
 * {@code System.nanoTime()} calls), so the amortized timing cost is roughly
 * {@code 2 * nanoTime / sampleRate}. With {@code latencySampleRate = 0} (the default),
 * no timing is performed at all and the sampling branch short-circuits.
 *
 * <p>Read {@link #snapshot()} off the hot path (e.g. from a metrics exporter) to obtain
 * counts and sampled-latency aggregates. The underlying {@link #getStats()} is still
 * available for the core hit/miss/eviction/memory figures.
 *
 * <pre>{@code
 * OffHeapCache<String, byte[]> cache =
 *     new MeteredOffHeapCache<>(coreCache);          // counting only
 * OffHeapCache<String, byte[]> sampled =
 *     new MeteredOffHeapCache<>(coreCache, 1024);    // + 1-in-1024 latency sampling
 * }</pre>
 *
 * @param <K> key type
 * @param <V> value type
 */
public final class MeteredOffHeapCache<K, V> implements OffHeapCache<K, V> {

    private final OffHeapCache<K, V> delegate;
    private final int latencySampleRate;

    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();
    private final LongAdder puts = new LongAdder();
    private final LongAdder removes = new LongAdder();
    private final LongAdder sampledOps = new LongAdder();
    private final LongAdder sampledNanosSum = new LongAdder();
    private final AtomicLong maxLatencyNanos = new AtomicLong();

    /** Wrap {@code delegate} with counting only (no latency timing). */
    public MeteredOffHeapCache(OffHeapCache<K, V> delegate) {
        this(delegate, 0);
    }

    /**
     * Wrap {@code delegate} with counting and optional latency sampling.
     *
     * @param delegate          the cache to instrument
     * @param latencySampleRate sample 1 in every {@code latencySampleRate} operations for
     *                          latency; {@code 0} disables latency timing entirely
     */
    public MeteredOffHeapCache(OffHeapCache<K, V> delegate, int latencySampleRate) {
        if (latencySampleRate < 0) {
            throw new IllegalArgumentException("latencySampleRate must be >= 0");
        }
        this.delegate = delegate;
        this.latencySampleRate = latencySampleRate;
    }

    /** Aggregated counters and sampled-latency figures for this decorator. */
    public CacheMetricsSnapshot snapshot() {
        return new CacheMetricsSnapshot(
                hits.sum(), misses.sum(), puts.sum(), removes.sum(),
                sampledOps.sum(), sampledNanosSum.sum(), maxLatencyNanos.get());
    }

    /** The wrapped cache. */
    public OffHeapCache<K, V> delegate() {
        return delegate;
    }

    // ── instrumented hot-path operations ─────────────────────────────────────

    private boolean shouldSample() {
        return latencySampleRate > 0 && ThreadLocalRandom.current().nextInt(latencySampleRate) == 0;
    }

    private void recordLatency(long startNanos) {
        long d = System.nanoTime() - startNanos;
        sampledOps.increment();
        sampledNanosSum.add(d);
        maxLatencyNanos.accumulateAndGet(d, Math::max);
    }

    @Override
    public V get(K key) {
        boolean sample = shouldSample();
        long t0 = sample ? System.nanoTime() : 0L;
        V v = delegate.get(key);
        if (sample) {
            recordLatency(t0);
        }
        if (v != null) {
            hits.increment();
        } else {
            misses.increment();
        }
        return v;
    }

    @Override
    public void put(K key, V value) {
        boolean sample = shouldSample();
        long t0 = sample ? System.nanoTime() : 0L;
        delegate.put(key, value);
        if (sample) {
            recordLatency(t0);
        }
        puts.increment();
    }

    @Override
    public void put(K key, V value, Duration ttl) {
        boolean sample = shouldSample();
        long t0 = sample ? System.nanoTime() : 0L;
        delegate.put(key, value, ttl);
        if (sample) {
            recordLatency(t0);
        }
        puts.increment();
    }

    @Override
    public void put(K key, V value, short priority) {
        boolean sample = shouldSample();
        long t0 = sample ? System.nanoTime() : 0L;
        delegate.put(key, value, priority);
        if (sample) {
            recordLatency(t0);
        }
        puts.increment();
    }

    @Override
    public void put(K key, V value, Duration ttl, short priority) {
        boolean sample = shouldSample();
        long t0 = sample ? System.nanoTime() : 0L;
        delegate.put(key, value, ttl, priority);
        if (sample) {
            recordLatency(t0);
        }
        puts.increment();
    }

    @Override
    public boolean remove(K key) {
        boolean sample = shouldSample();
        long t0 = sample ? System.nanoTime() : 0L;
        boolean removed = delegate.remove(key);
        if (sample) {
            recordLatency(t0);
        }
        removes.increment();
        return removed;
    }

    @Override
    public boolean putIfAbsent(K key, V value) {
        boolean inserted = delegate.putIfAbsent(key, value);
        if (inserted) {
            puts.increment();
        }
        return inserted;
    }

    @Override
    public boolean putIfAbsent(K key, V value, Duration ttl) {
        boolean inserted = delegate.putIfAbsent(key, value, ttl);
        if (inserted) {
            puts.increment();
        }
        return inserted;
    }

    // ── delegated operations (not separately metered in Tier 1) ──────────────

    @Override
    public V computeIfAbsent(K key, Function<K, V> loader) {
        return delegate.computeIfAbsent(key, loader);
    }

    @Override
    public V computeIfAbsent(K key, Function<K, V> loader, Duration ttl) {
        return delegate.computeIfAbsent(key, loader, ttl);
    }

    @Override
    public void putAll(Map<K, V> entries) {
        delegate.putAll(entries);
    }

    @Override
    public void putAll(Map<K, V> entries, Duration ttl) {
        delegate.putAll(entries, ttl);
    }

    @Override
    public Map<K, V> getAll(Collection<K> keys) {
        return delegate.getAll(keys);
    }

    @Override
    public CompletableFuture<Void> putAsync(K key, V value) {
        return delegate.putAsync(key, value);
    }

    @Override
    public CompletableFuture<V> getAsync(K key) {
        return delegate.getAsync(key);
    }

    @Override
    public boolean contains(K key) {
        return delegate.contains(key);
    }

    @Override
    public int size() {
        return delegate.size();
    }

    @Override
    public <T> T getZeroCopy(K key, Function<MemorySegment, T> processor) {
        return delegate.getZeroCopy(key, processor);
    }

    @Override
    public CacheValueView getView(K key) {
        return delegate.getView(key);
    }

    @Override
    public void clear() {
        delegate.clear();
    }

    @Override
    public CacheStats getStats() {
        return delegate.getStats();
    }

    @Override
    public String getCacheName() {
        return delegate.getCacheName();
    }

    @Override
    public void close() {
        delegate.close();
    }
}
