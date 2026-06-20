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
package com.codeabbot.rmcache.micrometer;

import com.codeabbot.rmcache.OffHeapCache;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.cache.CacheMeterBinder;

/**
 * Binds RMCache statistics to a Micrometer {@link MeterRegistry}.
 *
 * <p><b>Zero hot-path cost.</b> This binder is entirely pull-based: every meter reads
 * from {@link OffHeapCache#getStats()} at scrape time (when your monitoring backend
 * collects), never on the cache's {@code get}/{@code put} path. Adding it cannot regress
 * cache latency.
 *
 * <p>Standard Micrometer cache names are emitted via {@link CacheMeterBinder}
 * ({@code cache.gets} tagged {@code result=hit|miss}, {@code cache.size},
 * {@code cache.evictions}), plus RMCache extras: {@code cache.memory.used},
 * {@code cache.memory.max}, {@code cache.puts.rejected}, and
 * {@code cache.evictions.cause} tagged by cause.
 *
 * <p>{@code cache.puts} and {@code cache.removes} come straight from {@code getStats()};
 * all counts are accurate.
 *
 * <pre>{@code
 * RMCacheMicrometerMetrics.monitor(registry, cache, "users");
 * }</pre>
 */
public class RMCacheMicrometerMetrics extends CacheMeterBinder<OffHeapCache<?, ?>> {

    private final OffHeapCache<?, ?> cache;
    private final Tags cacheTags;

    public RMCacheMicrometerMetrics(OffHeapCache<?, ?> cache, String cacheName, Iterable<Tag> tags) {
        super(cache, cacheName, tags);
        this.cache = cache;
        this.cacheTags = Tags.of(tags).and("cache", cacheName);
    }

    /**
     * Bind RMCache metrics for {@code cache} to {@code registry} and return the cache,
     * so it reads fluently at construction.
     *
     * @param registry  the Micrometer registry
     * @param cache     the cache to monitor
     * @param cacheName logical cache name (becomes the {@code cache} tag)
     * @param tags      optional additional tags as alternating key/value strings
     * @return the same {@code cache}
     */
    public static <C extends OffHeapCache<?, ?>> C monitor(
            MeterRegistry registry, C cache, String cacheName, String... tags) {
        new RMCacheMicrometerMetrics(cache, cacheName, Tags.of(tags)).bindTo(registry);
        return cache;
    }

    @Override
    protected Long size() {
        return (long) cache.getStats().size();
    }

    @Override
    protected long hitCount() {
        return cache.getStats().hits();
    }

    @Override
    protected Long missCount() {
        return cache.getStats().misses();
    }

    @Override
    protected Long evictionCount() {
        return cache.getStats().evictions();
    }

    @Override
    protected long putCount() {
        return cache.getStats().puts();
    }

    @Override
    protected void bindImplementationSpecificMetrics(MeterRegistry registry) {
        FunctionCounter.builder("cache.removes", cache, c -> c.getStats().removes())
                .tags(cacheTags)
                .description("Cache remove operations")
                .register(registry);

        FunctionCounter.builder("cache.puts.rejected", cache, c -> c.getStats().rejectedPuts())
                .tags(cacheTags)
                .description("Cache put attempts rejected under memory pressure")
                .register(registry);

        Gauge.builder("cache.memory.used", cache, c -> c.getStats().memoryUsedBytes())
                .tags(cacheTags)
                .description("Off-heap memory currently used by the cache")
                .baseUnit("bytes")
                .register(registry);

        Gauge.builder("cache.memory.max", cache, c -> c.getStats().memoryTotalBytes())
                .tags(cacheTags)
                .description("Total off-heap memory budget for the cache")
                .baseUnit("bytes")
                .register(registry);

        FunctionCounter.builder("cache.evictions.cause", cache, c -> c.getStats().evictionsBySize())
                .tags(cacheTags).tag("cause", "size")
                .description("Cache evictions broken down by cause")
                .register(registry);
        FunctionCounter.builder("cache.evictions.cause", cache, c -> c.getStats().evictionsByTtl())
                .tags(cacheTags).tag("cause", "ttl")
                .description("Cache evictions broken down by cause")
                .register(registry);
        FunctionCounter.builder("cache.evictions.cause", cache, c -> c.getStats().evictionsByExplicit())
                .tags(cacheTags).tag("cause", "explicit")
                .description("Cache evictions broken down by cause")
                .register(registry);
    }
}
