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

import com.codeabbot.rmcache.CacheBuilder;
import com.codeabbot.rmcache.OffHeapCache;
import com.codeabbot.rmcache.Units;
import com.codeabbot.rmcache.serializer.BuiltInSerializers;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RMCacheMicrometerMetricsTest {

    private OffHeapCache<String, byte[]> cache;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        cache = new CacheBuilder<String, byte[]>()
                .offHeapMemory(Units.megabytes(64))
                .maxEntries(1000)
                .valueSerializer(BuiltInSerializers.byteArray())
                .build();
        registry = new SimpleMeterRegistry();
        RMCacheMicrometerMetrics.monitor(registry, cache, "test");
    }

    @AfterEach
    void tearDown() {
        cache.close();
    }

    @Test
    void reportsHitsMissesAndSize() {
        cache.put("a", new byte[]{1, 2, 3});
        assertThat(cache.get("a")).isNotNull();      // hit
        assertThat(cache.get("missing")).isNull();   // miss

        double totalGets = registry.find("cache.gets").functionCounters().stream()
                .mapToDouble(FunctionCounter::count)
                .sum();
        assertThat(totalGets).isEqualTo(2.0); // 1 hit + 1 miss
        assertThat(registry.get("cache.size").gauge().value()).isEqualTo(1.0);
    }

    @Test
    void reportsMemoryAndEvictionCauseMeters() {
        assertThat(registry.get("cache.memory.max").gauge().value()).isGreaterThan(0.0);
        // All three eviction-cause series are registered (0 until evictions occur).
        assertThat(registry.find("cache.evictions.cause").functionCounters()).hasSize(3);
    }
}
