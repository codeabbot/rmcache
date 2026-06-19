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

import com.codeabbot.rmcache.CacheBuilder;
import com.codeabbot.rmcache.OffHeapCache;
import com.codeabbot.rmcache.Units;
import com.codeabbot.rmcache.serializer.BuiltInSerializers;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;

class RMCacheOpenTelemetryMetricsTest {

    private OffHeapCache<String, byte[]> cache;
    private SdkMeterProvider meterProvider;
    private InMemoryMetricReader reader;
    private AutoCloseable handle;

    @BeforeEach
    void setUp() {
        cache = new CacheBuilder<String, byte[]>()
                .offHeapMemory(Units.megabytes(64))
                .maxEntries(1000)
                .valueSerializer(BuiltInSerializers.byteArray())
                .build();
        reader = InMemoryMetricReader.create();
        meterProvider = SdkMeterProvider.builder().registerMetricReader(reader).build();
        Meter meter = meterProvider.get("rmcache-test");
        handle = RMCacheOpenTelemetryMetrics.register(meter, cache, "test");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (handle != null) {
            handle.close();
        }
        if (meterProvider != null) {
            meterProvider.close();
        }
        if (cache != null) {
            cache.close();
        }
    }

    @Test
    void exportsCacheMetrics() {
        cache.put("a", new byte[]{1, 2, 3});
        cache.get("a");        // hit
        cache.get("missing");  // miss

        Collection<MetricData> metrics = reader.collectAllMetrics();
        assertThat(metrics).extracting(MetricData::getName)
                .contains("cache.gets", "cache.size", "cache.memory.max");

        MetricData gets = metrics.stream()
                .filter(m -> m.getName().equals("cache.gets"))
                .findFirst()
                .orElseThrow();
        long totalGets = gets.getLongSumData().getPoints().stream()
                .mapToLong(p -> p.getValue())
                .sum();
        assertThat(totalGets).isEqualTo(2L); // 1 hit + 1 miss
    }
}
