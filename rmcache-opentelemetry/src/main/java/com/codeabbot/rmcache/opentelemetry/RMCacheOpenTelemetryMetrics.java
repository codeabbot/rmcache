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
package com.codeabbot.rmcache.opentelemetry;

import com.codeabbot.rmcache.OffHeapCache;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.Meter;

import java.util.ArrayList;
import java.util.List;

/**
 * Registers RMCache statistics as OpenTelemetry <em>asynchronous</em> (observable)
 * instruments on a {@link Meter}.
 *
 * <p><b>Zero hot-path cost.</b> Every instrument is observable: its callback reads
 * {@link OffHeapCache#getStats()} only when the OpenTelemetry SDK collects (the export
 * interval), never on the cache's {@code get}/{@code put} path. Registering them cannot
 * regress cache latency.
 *
 * <p>Emits {@code cache.gets} (attribute {@code result=hit|miss}), {@code cache.puts},
 * {@code cache.removes}, {@code cache.evictions} (attribute {@code cause=size|ttl|explicit}),
 * {@code cache.size}, {@code cache.memory.used}, and {@code cache.memory.max}, each tagged
 * with the {@code cache} name attribute.
 *
 * <pre>{@code
 * AutoCloseable handle = RMCacheOpenTelemetryMetrics.register(meter, cache, "users");
 * // ... to stop observing (e.g. on shutdown):
 * handle.close();
 * }</pre>
 */
public final class RMCacheOpenTelemetryMetrics {

    private static final AttributeKey<String> CACHE = AttributeKey.stringKey("cache");
    private static final AttributeKey<String> RESULT = AttributeKey.stringKey("result");
    private static final AttributeKey<String> CAUSE = AttributeKey.stringKey("cause");

    private RMCacheOpenTelemetryMetrics() {
    }

    /**
     * Register observable cache instruments on {@code meter}.
     *
     * @param meter     the OpenTelemetry meter
     * @param cache     the cache to observe
     * @param cacheName logical name, attached as the {@code cache} attribute
     * @return a handle that unregisters every instrument when closed
     */
    public static AutoCloseable register(Meter meter, OffHeapCache<?, ?> cache, String cacheName) {
        Attributes base = Attributes.of(CACHE, cacheName);
        List<AutoCloseable> instruments = new ArrayList<>();

        instruments.add(meter.counterBuilder("cache.gets")
                .setDescription("Cache get operations by result")
                .buildWithCallback(m -> {
                    OffHeapCache.CacheStats s = cache.getStats();
                    m.record(s.hits(), base.toBuilder().put(RESULT, "hit").build());
                    m.record(s.misses(), base.toBuilder().put(RESULT, "miss").build());
                }));

        instruments.add(meter.counterBuilder("cache.puts")
                .setDescription("Cache put operations")
                .buildWithCallback(m -> m.record(cache.getStats().puts(), base)));

        instruments.add(meter.counterBuilder("cache.removes")
                .setDescription("Cache remove operations")
                .buildWithCallback(m -> m.record(cache.getStats().removes(), base)));

        instruments.add(meter.counterBuilder("cache.evictions")
                .setDescription("Cache evictions by cause")
                .buildWithCallback(m -> {
                    OffHeapCache.CacheStats s = cache.getStats();
                    m.record(s.evictionsBySize(), base.toBuilder().put(CAUSE, "size").build());
                    m.record(s.evictionsByTtl(), base.toBuilder().put(CAUSE, "ttl").build());
                    m.record(s.evictionsByExplicit(), base.toBuilder().put(CAUSE, "explicit").build());
                }));

        instruments.add(meter.gaugeBuilder("cache.size").ofLongs()
                .setDescription("Number of entries currently in the cache")
                .buildWithCallback(m -> m.record(cache.getStats().size(), base)));

        instruments.add(meter.gaugeBuilder("cache.memory.used").ofLongs()
                .setUnit("By")
                .setDescription("Off-heap memory currently used by the cache")
                .buildWithCallback(m -> m.record(cache.getStats().memoryUsedBytes(), base)));

        instruments.add(meter.gaugeBuilder("cache.memory.max").ofLongs()
                .setUnit("By")
                .setDescription("Total off-heap memory budget for the cache")
                .buildWithCallback(m -> m.record(cache.getStats().memoryTotalBytes(), base)));

        return () -> {
            for (AutoCloseable c : instruments) {
                c.close();
            }
        };
    }
}
