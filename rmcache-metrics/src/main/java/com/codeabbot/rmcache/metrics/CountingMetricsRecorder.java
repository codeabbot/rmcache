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

import com.codeabbot.rmcache.MetricsRecorder;

import java.util.concurrent.atomic.LongAdder;

/**
 * Tier 2 in-core {@link MetricsRecorder} that counts puts and removes with contention-free
 * {@link LongAdder}s. Plug it in via {@code CacheBuilder.metricsRecorder(...)}; it is
 * leak-free (no thread-local state) and adds a single increment per put/remove.
 *
 * <p>Combine its {@link #puts()} / {@link #removes()} with {@code cache.getStats()} (hits,
 * misses, evictions, size, memory) for the full metric set.
 */
public final class CountingMetricsRecorder implements MetricsRecorder {

    private final LongAdder puts = new LongAdder();
    private final LongAdder removes = new LongAdder();

    @Override
    public void onPut() {
        puts.increment();
    }

    @Override
    public void onRemove() {
        removes.increment();
    }

    /** Total successful puts (inserts + updates) recorded. */
    public long puts() {
        return puts.sum();
    }

    /** Total successful removes recorded. */
    public long removes() {
        return removes.sum();
    }
}
