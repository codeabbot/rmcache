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
import com.codeabbot.rmcache.serializer.StringEncoding;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class StringKeyEncodingTest {

    @Test
    public void latin1EncodingSupportsLatin1Keys() {
        OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(100)
                .offHeapMemory(8 * 1024 * 1024)
                .ghostCacheSize(0)
                .stringKeyEncoding(StringEncoding.LATIN1)
                .keySerializer(BuiltInSerializers.STRING_KEY_LATIN1)
                .valueSerializer(BuiltInSerializers.string())
                .build();

        try {
            String key = "cafe" + (char) 0x00E9; // e-acute (U+00E9) is Latin-1
            cache.put(key, "v1");
            assertEquals("v1", cache.get(key));
        } finally {
            cache.close();
        }
    }

    @Test
    public void utf8EncodingSupportsUnicodeKeys() {
        OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(100)
                .offHeapMemory(8 * 1024 * 1024)
                .ghostCacheSize(0)
                .stringKeyEncoding(StringEncoding.UTF8)
                .keySerializer(BuiltInSerializers.STRING_KEY_UTF8)
                .valueSerializer(BuiltInSerializers.string())
                .build();

        try {
            String key = "snowman-" + (char) 0x2603; // unicode snowman
            cache.put(key, "v2");
            assertEquals("v2", cache.get(key));
        } finally {
            cache.close();
        }
    }

    @Test
    public void latin1EncodingRejectsNonLatin1KeysWithoutCollision() {
        // Build the special keys programmatically so the source stays pure ASCII.
        String nonLatin1 = String.valueOf((char) 0x0100); // U+0100, NOT representable in Latin-1
        String nulKey = String.valueOf((char) 0x0000);    // U+0000, a valid Latin-1 byte (0x00)

        OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(100)
                .offHeapMemory(8 * 1024 * 1024)
                .ghostCacheSize(0)
                .stringKeyEncoding(StringEncoding.LATIN1)
                .keySerializer(BuiltInSerializers.STRING_KEY_LATIN1)
                .valueSerializer(BuiltInSerializers.string())
                .build();

        try {
            // U+0100 truncates to byte 0x00 under the old code, silently colliding
            // distinct keys. It must now be rejected rather than corrupting data.
            assertThrows(IllegalArgumentException.class, () -> cache.put(nonLatin1, "boom"));

            // And a non-Latin-1 lookup must never spuriously match a stored key that
            // shares its truncated byte: U+0100 -> 0x00, the same byte as U+0000.
            cache.put(nulKey, "nul");
            assertEquals("nul", cache.get(nulKey));
            assertNull(cache.get(nonLatin1),
                    "non-Latin1 lookup must not collide with the NUL-byte key");
        } finally {
            cache.close();
        }
    }
}
