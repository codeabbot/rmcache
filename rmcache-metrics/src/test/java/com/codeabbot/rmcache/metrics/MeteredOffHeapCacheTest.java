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

import com.codeabbot.rmcache.CacheBuilder;
import com.codeabbot.rmcache.OffHeapCache;
import com.codeabbot.rmcache.Units;
import com.codeabbot.rmcache.serializer.BuiltInSerializers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MeteredOffHeapCacheTest {

    private OffHeapCache<String, byte[]> core;

    @BeforeEach
    void setUp() {
        core = new CacheBuilder<String, byte[]>()
                .offHeapMemory(Units.megabytes(64))
                .maxEntries(1000)
                .valueSerializer(BuiltInSerializers.byteArray())
                .build();
    }

    @AfterEach
    void tearDown() {
        core.close();
    }

    @Test
    void countsHitsMissesPutsRemoves() {
        MeteredOffHeapCache<String, byte[]> cache = new MeteredOffHeapCache<>(core);

        cache.put("a", new byte[]{1});
        cache.put("b", new byte[]{2});
        assertThat(cache.get("a")).isNotNull();   // hit
        assertThat(cache.get("zzz")).isNull();    // miss
        assertThat(cache.remove("a")).isTrue();

        CacheMetricsSnapshot s = cache.snapshot();
        assertThat(s.puts()).isEqualTo(2);
        assertThat(s.hits()).isEqualTo(1);
        assertThat(s.misses()).isEqualTo(1);
        assertThat(s.gets()).isEqualTo(2);
        assertThat(s.removes()).isEqualTo(1);
        assertThat(s.hitRate()).isEqualTo(0.5);
    }

    @Test
    void countingOnlyDoesNotSampleLatency() {
        MeteredOffHeapCache<String, byte[]> cache = new MeteredOffHeapCache<>(core); // rate 0
        cache.put("a", new byte[]{1});
        cache.get("a");
        assertThat(cache.snapshot().sampledOps()).isZero();
    }

    @Test
    void samplingRecordsLatency() {
        MeteredOffHeapCache<String, byte[]> cache = new MeteredOffHeapCache<>(core, 1); // every op
        for (int i = 0; i < 50; i++) {
            cache.put("k" + i, new byte[]{(byte) i});
            cache.get("k" + i);
        }
        CacheMetricsSnapshot s = cache.snapshot();
        assertThat(s.sampledOps()).isGreaterThan(0);
        assertThat(s.meanSampledLatencyNanos()).isGreaterThan(0.0);
        assertThat(s.maxLatencyNanos()).isGreaterThan(0L);
    }

    @Test
    void putIfAbsentCountsOnlyOnInsert() {
        MeteredOffHeapCache<String, byte[]> cache = new MeteredOffHeapCache<>(core);
        assertThat(cache.putIfAbsent("a", new byte[]{1})).isTrue();
        assertThat(cache.putIfAbsent("a", new byte[]{2})).isFalse(); // already present
        assertThat(cache.snapshot().puts()).isEqualTo(1);
    }

    @Test
    void delegatesValueCorrectly() {
        MeteredOffHeapCache<String, byte[]> cache = new MeteredOffHeapCache<>(core);
        cache.put("a", new byte[]{1, 2, 3});
        assertThat(cache.get("a")).isEqualTo(new byte[]{1, 2, 3});
        assertThat(cache.size()).isEqualTo(1);
        assertThat(cache.contains("a")).isTrue();
    }
}
