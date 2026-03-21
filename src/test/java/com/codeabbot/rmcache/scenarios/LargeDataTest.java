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
import com.codeabbot.rmcache.serializer.ValueSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.lang.foreign.MemorySegment;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

public class LargeDataTest {

    private OffHeapCache<String, UserProfile> cache;

    @AfterEach
    public void tearDown() {
        if (cache != null) {
            cache.close();
        }
    }

    public static class UserProfile {
        final int id;
        final String name;
        final byte[] payload;

        public UserProfile(int id, String name, byte[] payload) {
            this.id = id;
            this.name = name;
            this.payload = payload;
        }
    }

    public static class UserProfileSerializer implements ValueSerializer<UserProfile> {
        @Override
        public byte[] serialize(UserProfile value) {
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                DataOutputStream dos = new DataOutputStream(baos);
                dos.writeInt(value.id);
                dos.writeUTF(value.name);
                dos.writeInt(value.payload.length);
                dos.write(value.payload);
                return baos.toByteArray();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public UserProfile deserialize(byte[] bytes) {
            try {
                ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
                DataInputStream dis = new DataInputStream(bais);
                int id = dis.readInt();
                String name = dis.readUTF();
                int pSize = dis.readInt();
                byte[] payload = new byte[pSize];
                dis.readFully(payload);
                return new UserProfile(id, name, payload);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Test
    public void testLargeObject500KB() {
        // Needs sufficient memory
        cache = new CacheBuilder<String, UserProfile>()
                .keySerializer(BuiltInSerializers.STRING_KEY)
                .valueSerializer(new UserProfileSerializer())
                .offHeapMemory(64 * 1024 * 1024)
                .maxEntries(100)
                .build();

        byte[] payload = new byte[500 * 1024]; // 500KB
        new Random().nextBytes(payload);

        UserProfile user = new UserProfile(1, "Big User", payload);

        cache.put("User-1", user);

        UserProfile retrieved = cache.get("User-1");
        assertNotNull(retrieved);
        assertEquals(user.id, retrieved.id);
        assertEquals(user.name, retrieved.name);
        assertArrayEquals(user.payload, retrieved.payload);
    }
}
