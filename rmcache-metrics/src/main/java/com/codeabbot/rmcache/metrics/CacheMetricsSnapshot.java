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
 * Immutable point-in-time snapshot of {@link MeteredOffHeapCache} counters.
 *
 * <p>Counts that the core {@code CacheStats} does not provide — {@code puts},
 * {@code removes}, and per-call latency — are tracked by the decorator. Latency is
 * <em>sampled</em> (1-in-N operations), so {@link #sampledOps()} is the sample count, not
 * the total op count; {@link #meanSampledLatencyNanos()} is the mean over those samples.
 *
 * @param hits             gets that returned a value
 * @param misses           gets that returned {@code null}
 * @param puts             successful put / putIfAbsent operations
 * @param removes          remove operations
 * @param sampledOps       number of operations whose latency was sampled
 * @param sampledNanosSum  sum of sampled latencies, in nanoseconds
 * @param maxLatencyNanos  maximum observed sampled latency, in nanoseconds
 */
public record CacheMetricsSnapshot(
        long hits,
        long misses,
        long puts,
        long removes,
        long sampledOps,
        long sampledNanosSum,
        long maxLatencyNanos) {

    /** Total gets ({@code hits + misses}). */
    public long gets() {
        return hits + misses;
    }

    /** Hit ratio in [0,1]; 0 when there have been no gets. */
    public double hitRate() {
        long total = hits + misses;
        return (total == 0) ? 0.0 : (double) hits / total;
    }

    /** Mean sampled latency in nanoseconds; 0 when nothing was sampled. */
    public double meanSampledLatencyNanos() {
        return (sampledOps == 0) ? 0.0 : (double) sampledNanosSum / sampledOps;
    }
}
