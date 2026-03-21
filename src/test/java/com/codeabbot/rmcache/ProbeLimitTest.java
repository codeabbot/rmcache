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
import com.codeabbot.rmcache.serializer.ValueSerializer;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static org.junit.jupiter.api.Assertions.*;

public class ProbeLimitTest {

    // Serializer forcing MASSIVE collisions
    private static final KeySerializer<String> BAD_HASH_SERIALIZER = new KeySerializer<>() {
        @Override
        public byte[] serialize(String key) {
            try {
                return key.getBytes("UTF-8");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public String deserialize(byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }

        @Override
        public int hashCode(String key) {
            return 1; // ALL KEYS HASH TO 1
        }

        @Override
        public boolean matches(String key, MemorySegment segment, long offset, int length) {
            return BuiltInSerializers.STRING_KEY.matches(key, segment, offset, length);
        }
    };

    @Test
    public void testRobustness_MassiveCollisions_ShouldEvictNotCrash() {
        // Use a clean test environment to avoid interference
        // 128 MB
        long memorySize = 128L * 1024 * 1024;
        OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(10_000)
                .offHeapMemory(memorySize)
                .keySerializer(BAD_HASH_SERIALIZER) // FORCE 100% COLLISION
                .valueSerializer(BuiltInSerializers.string())
                .hashTableStripes(1) // Single giant stripe for max capacity
                .entryPoolPartitions(1) // Single partition to handle skewed distribution
                .build();

        try {
            int count = 2000;
            for (int i = 0; i < count; i++) {
                cache.put("K-" + i, "V-" + i);
            }

            int size = cache.size();
            assertTrue(size <= 32, "Size should be capped by probe limit (32)");
            assertTrue(size > 0, "Should have accepted some items");

            // Verify we can still perform normal operations
            cache.put("SafeKey", "SafeVal");
            assertEquals("SafeVal", cache.get("SafeKey"));

        } finally {
            cache.close(); // Critical for NativeMemory
        }
    }
}
