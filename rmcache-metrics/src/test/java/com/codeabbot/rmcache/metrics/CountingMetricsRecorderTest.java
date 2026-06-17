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

class CountingMetricsRecorderTest {

    private OffHeapCache<String, byte[]> cache;
    private CountingMetricsRecorder recorder;

    @BeforeEach
    void setUp() {
        recorder = new CountingMetricsRecorder();
        cache = new CacheBuilder<String, byte[]>()
                .offHeapMemory(Units.megabytes(64))
                .maxEntries(1000)
                .valueSerializer(BuiltInSerializers.byteArray())
                .metricsRecorder(recorder)
                .build();
    }

    @AfterEach
    void tearDown() {
        cache.close();
    }

    @Test
    void countsPutsAndRemoves() {
        cache.put("a", new byte[]{1});
        cache.put("b", new byte[]{2});
        cache.put("a", new byte[]{3}); // update is still a put
        assertThat(recorder.puts()).isEqualTo(3);

        assertThat(cache.remove("a")).isTrue();
        assertThat(cache.remove("missing")).isFalse(); // not present -> not counted
        assertThat(recorder.removes()).isEqualTo(1);
    }

    @Test
    void putIfAbsentCountsOnlyOnInsert() {
        cache.putIfAbsent("a", new byte[]{1}); // inserts
        cache.putIfAbsent("a", new byte[]{2}); // already present -> not counted
        assertThat(recorder.puts()).isEqualTo(1);
    }
}
