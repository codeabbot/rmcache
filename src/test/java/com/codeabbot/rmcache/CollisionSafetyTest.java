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

import com.codeabbot.rmcache.serializer.KeySerializer;
import com.codeabbot.rmcache.serializer.BuiltInSerializers;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import java.util.List;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static org.junit.jupiter.api.Assertions.*;

public class CollisionSafetyTest {

    // Serializer that forces ALL keys to have the same hash code
    private static final KeySerializer<String> COLLIDING_KEY_SERIALIZER = new KeySerializer<>() {
        @Override
        public byte[] serialize(String key) {
            return key.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public String deserialize(byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }

        @Override
        public int hashCode(String key) {
            return 42; // Constant hash
        }

        @Override
        public boolean matches(String key, MemorySegment segment, long offset, int length) {
            return BuiltInSerializers.STRING_KEY.matches(key, segment, offset, length);
        }
    };

    @Test
    public void testCollisionResolution_ExactKeyMatchRequired() {
        OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(200) // Explicit max entries
                .offHeapMemory(16 * 1024 * 1024)
                .keySerializer(COLLIDING_KEY_SERIALIZER) // FORCE COLLISIONS
                .valueSerializer(BuiltInSerializers.string())
                .build();

        try {
            // All these keys have hash 42
            List<String> keys = new ArrayList<>();
            for (int i = 1; i <= 30; i++)
                keys.add("Key-" + i);

            // Put all
            for (String k : keys) {
                cache.put(k, "Value-" + k);
            }

            // Verify size (should be 100, not 1 if collisions overwrote)
            assertEquals(30, cache.size(), "Cache size should match put count (within probe limit)");

            // Verify retrieval
            for (String k : keys) {
                String valStr = cache.get(k);
                assertEquals("Value-" + k, valStr, "Must retrieve correct value for key " + k);
            }

        } finally {
            cache.close();
        }
    }

    @Test
    public void testConcurrency_RaceCondition_Reproduction() throws InterruptedException {
        // This test attempts to trigger the striped-locking race condition in open
        // addressing
        // We use a small cache and high contention
        OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(100_000) // Explicit 100k limit
                .offHeapMemory(128 * 1024 * 1024)
                .hashTableStripes(256) // Use striped locking
                .keySerializer(BuiltInSerializers.STRING_KEY)
                .valueSerializer(BuiltInSerializers.string())
                .build();

        try {
            int threads = 8;
            int perThread = 10_000;
            ExecutorService executor = Executors.newFixedThreadPool(threads);
            CountDownLatch latch = new CountDownLatch(threads);
            AtomicInteger errors = new AtomicInteger(0);

            for (int t = 0; t < threads; t++) {
                final int tId = t;
                executor.submit(() -> {
                    try {
                        for (int i = 0; i < perThread; i++) {
                            String key = "T" + tId + "-K" + i;
                            cache.put(key, "Val");
                            if (cache.get(key) == null) {
                                errors.incrementAndGet();
                            }
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        errors.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await();
            executor.shutdown();

            // The size should be threads * perThread
            int expectedSize = threads * perThread;
            int actualSize = cache.size();

            // If the race condition exists, we might lose updates or have corruption
            assertEquals(expectedSize, actualSize, "Lost updates due to concurrency race condition");
            assertEquals(0, errors.get(), "Errors during execution");

        } finally {
            cache.close();
        }
    }
}
