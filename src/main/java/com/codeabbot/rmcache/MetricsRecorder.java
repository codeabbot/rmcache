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
package com.codeabbot.rmcache;

/**
 * In-core hook for recording cache write events (puts and removes).
 *
 * <p>Hits, misses, evictions, size and memory are already tracked internally and exposed
 * via {@link OffHeapCache#getStats()}. This SPI fills the one remaining gap — put and
 * remove counts — for callers who want it recorded inside the cache rather than through a
 * wrapping decorator. The read path ({@code get}) is intentionally not hooked: it stays
 * byte-for-byte unchanged.
 *
 * <p><b>Zero cost when unused.</b> A cache built without a recorder uses {@link #NOOP},
 * whose methods are empty; with the default in place the call sites devirtualize and the
 * JIT inlines them to nothing, so {@code put}/{@code remove} are unchanged. Supply a
 * recorder via {@code CacheBuilder.metricsRecorder(...)} to begin recording.
 *
 * <p>Implementations are invoked on the caller's {@code put}/{@code remove} thread, so
 * they must be thread-safe and must not block — keep them to cheap, contention-free
 * counters (e.g. {@code LongAdder}).
 */
public interface MetricsRecorder {

    /** Invoked after a successful put (insert or update). */
    void onPut();

    /** Invoked after a successful remove. */
    void onRemove();

    /** No-op recorder used by default; its empty methods inline away to zero cost. */
    MetricsRecorder NOOP = new MetricsRecorder() {
        @Override
        public void onPut() {
        }

        @Override
        public void onRemove() {
        }
    };
}
