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
package com.codeabbot.rmcache.scenarios;

import com.codeabbot.rmcache.CacheBuilder;
import com.codeabbot.rmcache.OffHeapCache;
import com.codeabbot.rmcache.serializer.BuiltInSerializers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FuzzRobustnessTest {

    private OffHeapCache<String, byte[]> cache;

    @AfterEach
    public void tearDown() {
        if (cache != null) {
            cache.close();
        }
    }

    @Test
    public void testRandomOpsConcurrency() throws InterruptedException {
        cache = new CacheBuilder<String, byte[]>()
                .keySerializer(BuiltInSerializers.STRING_KEY)
                .valueSerializer(BuiltInSerializers.byteArray())
                .offHeapMemory(64 * 1024 * 1024)
                .maxEntries(10_000)
                .backgroundEviction(false) // Synchronous eviction for test
                .build();

        int numThreads = 8;
        int opsPerThread = 5000;
        ExecutorService service = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);
        AtomicInteger errors = new AtomicInteger(0);

        for (int t = 0; t < numThreads; t++) {
            service.submit(() -> {
                try {
                    ThreadLocalRandom rng = ThreadLocalRandom.current();
                    byte[] value = new byte[64];

                    for (int i = 0; i < opsPerThread; i++) {
                        String key = "Key-" + rng.nextInt(20_000); // Some collision/overlap likely
                        int op = rng.nextInt(100);

                        try {
                            if (op < 40) { // 40% Put
                                cache.put(key, value);
                            } else if (op < 80) { // 40% Get
                                cache.get(key);
                            } else if (op < 90) { // 10% Remove
                                cache.remove(key);
                            } else { // 10% Overwrite/Update
                                cache.put(key, value); // Same as put actually
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                            errors.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        service.shutdown();

        assertEquals(0, errors.get(), "Concurrency fuzzing produced errors");
        assertTrue(cache.size() <= 10_000 + 100, "Cache constraint violated"); // Allow small slush
    }
}
