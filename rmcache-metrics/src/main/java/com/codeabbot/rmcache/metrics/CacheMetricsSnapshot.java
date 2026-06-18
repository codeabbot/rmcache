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

/**
 * Immutable point-in-time snapshot of {@link MeteredOffHeapCache}'s sampled-latency figures.
 *
 * <p>Operation counts (hits, misses, puts, removes, evictions, size, memory) are <b>not</b>
 * here — they come directly from {@code cache.getStats()}. This decorator's only job is
 * per-call latency, measured on a 1-in-N sample, so {@link #sampledOps()} is the sample
 * count (not the total op count) and {@link #meanSampledLatencyNanos()} is the mean over
 * those samples.
 *
 * @param sampledOps       number of operations whose latency was sampled
 * @param sampledNanosSum  sum of sampled latencies, in nanoseconds
 * @param maxLatencyNanos  maximum observed sampled latency, in nanoseconds
 */
public record CacheMetricsSnapshot(
        long sampledOps,
        long sampledNanosSum,
        long maxLatencyNanos) {

    /** Mean sampled latency in nanoseconds; 0 when nothing was sampled. */
    public double meanSampledLatencyNanos() {
        return (sampledOps == 0) ? 0.0 : (double) sampledNanosSum / sampledOps;
    }
}
