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

import com.codeabbot.rmcache.serializer.BuiltInSerializers;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class BackgroundEvictionTest {

    @Test
    public void backgroundEvictionReducesSizeToMaxEntries() throws Exception {
        int maxEntries = 100;
        OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(maxEntries)
                .offHeapMemory(32 * 1024 * 1024)
                .ghostCacheSize(0)
                .backgroundEviction(true)
                .backgroundEvictionInterval(Duration.ofMillis(10))
                .keySerializer(BuiltInSerializers.STRING_KEY)
                .valueSerializer(BuiltInSerializers.string())
                .build();

        try {
            for (int i = 0; i < maxEntries * 2; i++) {
                cache.put("k" + i, "v" + i);
            }

            // Wait up to 2 seconds for background eviction to converge
            long deadline = System.nanoTime() + 2_000_000_000L;
            while (cache.size() > maxEntries && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }

            assertTrue(cache.size() <= maxEntries);
        } finally {
            cache.close();
        }
    }
}
