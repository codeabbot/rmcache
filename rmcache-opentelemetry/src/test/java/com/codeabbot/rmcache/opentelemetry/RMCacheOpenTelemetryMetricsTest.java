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
import com.codeabbot.rmcache.eviction.NoEvictionPolicy;
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

    @Test
    void exportsRejectedPuts() throws Exception {
        try (OffHeapCache<String, byte[]> small = new CacheBuilder<String, byte[]>()
                .maxEntries(500)
                .averageValueSize(256)
                .offHeapMemory(1L << 20)
                .keySerializer(BuiltInSerializers.STRING_KEY)
                .valueSerializer(BuiltInSerializers.byteArray())
                .eviction(new NoEvictionPolicy())
                .build()) {
            InMemoryMetricReader localReader = InMemoryMetricReader.create();
            SdkMeterProvider localProvider = SdkMeterProvider.builder()
                    .registerMetricReader(localReader)
                    .build();
            try {
                AutoCloseable localHandle = RMCacheOpenTelemetryMetrics.register(
                        localProvider.get("rmcache-test-rejected"), small, "small");
                try {
                    byte[] big = new byte[8192];
                    for (int i = 0; i < 500; i++) {
                        small.put("k-" + i, big);
                    }

                    MetricData rejected = localReader.collectAllMetrics().stream()
                            .filter(m -> m.getName().equals("cache.puts.rejected"))
                            .findFirst()
                            .orElseThrow();
                    long rejectedPuts = rejected.getLongSumData().getPoints().stream()
                            .mapToLong(p -> p.getValue())
                            .sum();
                    assertThat(rejectedPuts).isGreaterThan(0L);
                } finally {
                    localHandle.close();
                }
            } finally {
                localProvider.close();
            }
        }
    }
}
