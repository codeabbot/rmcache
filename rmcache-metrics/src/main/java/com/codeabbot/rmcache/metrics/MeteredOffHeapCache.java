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
 * A latency-sampling decorator over any {@link OffHeapCache}.
 *
 * <p><b>Tier 1 — opt-in.</b> Operation counts (hits, misses, puts, removes, evictions,
 * size, memory) are already tracked by the core and exposed via
 * {@link OffHeapCache#getStats()}. This decorator adds the one thing stats cannot give you:
 * <b>per-call latency</b>, measured on a 1-in-{@code latencySampleRate} sample (two
 * {@code System.nanoTime()} calls), so the amortized timing cost is roughly
 * {@code 2 * nanoTime / sampleRate}. With {@code latencySampleRate = 0} (the default) no
 * timing is done and the decorator is a thin pass-through.
 *
 * <p>Read {@link #snapshot()} off the hot path for sampled-latency aggregates; use the
 * delegated {@link #getStats()} for the core counts.
 *
 * <pre>{@code
 * OffHeapCache<String, byte[]> sampled =
 *     new MeteredOffHeapCache<>(coreCache, 1024); // 1-in-1024 latency sampling
 * }</pre>
 *
 * @param <K> key type
 * @param <V> value type
 */
public final class MeteredOffHeapCache<K, V> implements OffHeapCache<K, V> {

    private final OffHeapCache<K, V> delegate;
    private final int latencySampleRate;

    private final LongAdder sampledOps = new LongAdder();
    private final LongAdder sampledNanosSum = new LongAdder();
    private final AtomicLong maxLatencyNanos = new AtomicLong();

    /** Wrap {@code delegate} as a thin pass-through (no latency timing). */
    public MeteredOffHeapCache(OffHeapCache<K, V> delegate) {
        this(delegate, 0);
    }

    /**
     * Wrap {@code delegate} with optional latency sampling.
     *
     * @param delegate          the cache to instrument
     * @param latencySampleRate sample 1 in every {@code latencySampleRate} operations;
     *                          {@code 0} disables timing entirely
     */
    public MeteredOffHeapCache(OffHeapCache<K, V> delegate, int latencySampleRate) {
        if (latencySampleRate < 0) {
            throw new IllegalArgumentException("latencySampleRate must be >= 0");
        }
        this.delegate = delegate;
        this.latencySampleRate = latencySampleRate;
    }

    /** Sampled-latency figures for this decorator. Counts live in {@link #getStats()}. */
    public CacheMetricsSnapshot snapshot() {
        return new CacheMetricsSnapshot(sampledOps.sum(), sampledNanosSum.sum(), maxLatencyNanos.get());
    }

    /** The wrapped cache. */
    public OffHeapCache<K, V> delegate() {
        return delegate;
    }

    private boolean shouldSample() {
        return latencySampleRate > 0 && ThreadLocalRandom.current().nextInt(latencySampleRate) == 0;
    }

    private void recordLatency(long startNanos) {
        long d = System.nanoTime() - startNanos;
        sampledOps.increment();
        sampledNanosSum.add(d);
        maxLatencyNanos.accumulateAndGet(d, Math::max);
    }

    // ── latency-sampled hot-path operations ──────────────────────────────────

    @Override
    public V get(K key) {
        if (!shouldSample()) {
            return delegate.get(key);
        }
        long t0 = System.nanoTime();
        V v = delegate.get(key);
        recordLatency(t0);
        return v;
    }

    @Override
    public void put(K key, V value) {
        if (!shouldSample()) {
            delegate.put(key, value);
            return;
        }
        long t0 = System.nanoTime();
        delegate.put(key, value);
        recordLatency(t0);
    }

    @Override
    public void put(K key, V value, Duration ttl) {
        if (!shouldSample()) {
            delegate.put(key, value, ttl);
            return;
        }
        long t0 = System.nanoTime();
        delegate.put(key, value, ttl);
        recordLatency(t0);
    }

    @Override
    public void put(K key, V value, short priority) {
        if (!shouldSample()) {
            delegate.put(key, value, priority);
            return;
        }
        long t0 = System.nanoTime();
        delegate.put(key, value, priority);
        recordLatency(t0);
    }

    @Override
    public void put(K key, V value, Duration ttl, short priority) {
        if (!shouldSample()) {
            delegate.put(key, value, ttl, priority);
            return;
        }
        long t0 = System.nanoTime();
        delegate.put(key, value, ttl, priority);
        recordLatency(t0);
    }

    @Override
    public boolean remove(K key) {
        if (!shouldSample()) {
            return delegate.remove(key);
        }
        long t0 = System.nanoTime();
        boolean removed = delegate.remove(key);
        recordLatency(t0);
        return removed;
    }

    // ── delegated operations ─────────────────────────────────────────────────

    @Override
    public boolean putIfAbsent(K key, V value) {
        return delegate.putIfAbsent(key, value);
    }

    @Override
    public boolean putIfAbsent(K key, V value, Duration ttl) {
        return delegate.putIfAbsent(key, value, ttl);
    }

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
