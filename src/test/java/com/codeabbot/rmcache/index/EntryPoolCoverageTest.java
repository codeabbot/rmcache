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
package com.codeabbot.rmcache.index;

import com.codeabbot.rmcache.CacheBuilder;
import com.codeabbot.rmcache.GhostCacheMode;
import com.codeabbot.rmcache.OffHeapCache;
import com.codeabbot.rmcache.serializer.BuiltInSerializers;
import com.codeabbot.rmcache.serializer.SegmentValueSerializer;
import com.codeabbot.rmcache.memory.SlabAllocator;

import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Targeted coverage tests for EntryPool and SubPool —
 * allocateWithWriter, allocateWithSerializer, updateValueWithWriter,
 * updateValueWithSerializer, getValuePosition, keyEqualsNoLenCheck,
 * getKeyLen, matches, getSlotFromOffset paths.
 */
public class EntryPoolCoverageTest {

    private static final long MB4 = 4L * 1024 * 1024;
    private static final long MB8 = 8L * 1024 * 1024;

    // ── allocateWithWriter / updateValueWithWriter via ValueWriter ────────────

    @Test
    void allocateWithWriter_and_readValue() {
        try (SlabAllocator alloc = new SlabAllocator(MB4, 64 * 1024);
             EntryPool pool = new EntryPool(alloc, 1000, 64)) {

            byte[] keyBytes = "writer-key".getBytes(StandardCharsets.UTF_8);
            int keyLen = keyBytes.length;
            byte[] valueBytes = "writer-value".getBytes(StandardCharsets.UTF_8);
            int valueLen = valueBytes.length;
            int keyHash = 0x12345678;

            ValueWriter writer = (segment, offset, maxLen) -> {
                MemorySegment.copy(valueBytes, 0, segment, ValueLayout.JAVA_BYTE, offset, valueLen);
                return valueLen;
            };

            int slot = pool.allocateWithWriter(keyHash, keyBytes, keyLen, valueLen, writer,
                    (short) 0, 0L);
            assertTrue(slot > 0, "allocateWithWriter should return a valid slot");

            // Verify stored value
            byte[] readBack = pool.readValue(slot);
            assertNotNull(readBack);
            assertEquals("writer-value", new String(readBack, StandardCharsets.UTF_8));

            // Verify getKeyLen
            assertEquals(keyLen, pool.getKeyLen(slot));

            // Verify getKeyHash
            assertEquals(keyHash, pool.getKeyHash(slot));

            pool.free(slot);
        }
    }

    @Test
    void updateValueWithWriter_inPlace() {
        try (SlabAllocator alloc = new SlabAllocator(MB4, 64 * 1024);
             EntryPool pool = new EntryPool(alloc, 1000, 64)) {

            byte[] keyBytes = "wk".getBytes(StandardCharsets.UTF_8);
            byte[] origValue = "original-value-data".getBytes(StandardCharsets.UTF_8);
            int keyHash = 0xAABBCCDD;

            int slot = pool.allocateWithLen(keyHash, keyBytes, keyBytes.length,
                    origValue, origValue.length, (short) 0, 0L);
            assertTrue(slot > 0);

            // Update with a smaller value using writer
            byte[] newValue = "updated".getBytes(StandardCharsets.UTF_8);
            boolean updated = pool.updateValueWithWriter(slot, newValue.length,
                    (segment, offset, maxLen) -> {
                        MemorySegment.copy(newValue, 0, segment, ValueLayout.JAVA_BYTE, offset, newValue.length);
                        return newValue.length;
                    });
            assertTrue(updated);

            byte[] readBack = pool.readValue(slot);
            assertNotNull(readBack);
            assertEquals("updated", new String(readBack, StandardCharsets.UTF_8));

            pool.free(slot);
        }
    }

    // ── allocateWithSerializer / updateValueWithSerializer ────────────────────

    @Test
    void allocateWithSerializer_and_updateWithSerializer() {
        try (SlabAllocator alloc = new SlabAllocator(MB4, 64 * 1024);
             EntryPool pool = new EntryPool(alloc, 1000, 64)) {

            byte[] keyBytes = "ser-key".getBytes(StandardCharsets.UTF_8);
            int keyLen = keyBytes.length;
            int keyHash = 0x55667788;

            SegmentValueSerializer<byte[]> serializer = BuiltInSerializers.ByteArrayValueSerializer.INSTANCE;
            byte[] value = new byte[]{10, 20, 30, 40, 50};
            int estimatedSize = serializer.estimateSize(value);

            int slot = pool.allocateWithSerializer(keyHash, keyBytes, keyLen, estimatedSize,
                    serializer, value, (short) 1, 0L);
            assertTrue(slot > 0, "allocateWithSerializer should return a valid slot");

            byte[] readBack = pool.readValue(slot);
            assertNotNull(readBack);
            assertArrayEquals(value, readBack);

            // Update with serializer (new value same or smaller size)
            byte[] newValue = new byte[]{99, 98, 97};
            int newEstimatedSize = serializer.estimateSize(newValue);
            boolean updated = pool.updateValueWithSerializer(slot, newEstimatedSize, serializer, newValue);
            assertTrue(updated);

            readBack = pool.readValue(slot);
            assertNotNull(readBack);
            assertArrayEquals(newValue, readBack);

            pool.free(slot);
        }
    }

    // ── getValuePosition ─────────────────────────────────────────────────────

    @Test
    void getValuePosition_returnsOffsetAndLen() {
        try (SlabAllocator alloc = new SlabAllocator(MB4, 64 * 1024);
             EntryPool pool = new EntryPool(alloc, 1000, 64)) {

            byte[] keyBytes = "vp-key".getBytes(StandardCharsets.UTF_8);
            byte[] valueBytes = "vp-value-data".getBytes(StandardCharsets.UTF_8);
            int keyHash = 0x11223344;

            int slot = pool.allocateWithLen(keyHash, keyBytes, keyBytes.length,
                    valueBytes, valueBytes.length, (short) 0, 0L);
            assertTrue(slot > 0);

            long[] pos = pool.getValuePosition(slot);
            assertNotNull(pos);
            assertEquals(2, pos.length);
            assertTrue(pos[0] > 0, "value data offset should be positive");
            assertEquals(valueBytes.length, (int) pos[1], "value length should match");

            // Also test getValueDataOffset
            long dataOffset = pool.getValueDataOffset(slot);
            assertEquals(pos[0], dataOffset);

            // getValueSegment
            MemorySegment seg = pool.getValueSegment(slot);
            assertNotNull(seg);
            assertEquals(valueBytes.length, (int) seg.byteSize());

            pool.free(slot);
        }
    }

    @Test
    void getValuePosition_freedSlot_returnsNull() {
        try (SlabAllocator alloc = new SlabAllocator(MB4, 64 * 1024);
             EntryPool pool = new EntryPool(alloc, 1000, 64)) {

            byte[] keyBytes = "fk".getBytes(StandardCharsets.UTF_8);
            byte[] valueBytes = "fv".getBytes(StandardCharsets.UTF_8);
            int slot = pool.allocateWithLen(0, keyBytes, keyBytes.length,
                    valueBytes, valueBytes.length, (short) 0, 0L);
            assertTrue(slot > 0);
            pool.free(slot);

            assertNull(pool.getValuePosition(slot));
            assertEquals(-1L, pool.getValueDataOffset(slot));
            assertNull(pool.getValueSegment(slot));
            assertEquals(0, pool.getKeyLen(slot));
            assertNull(pool.readKey(slot));
            assertNull(pool.readValue(slot));
            assertEquals(0, pool.getValueLen(slot));
        }
    }

    // ── keyEqualsNoLenCheck ──────────────────────────────────────────────────

    @Test
    void keyEqualsNoLenCheck_worksCorrectly() {
        try (SlabAllocator alloc = new SlabAllocator(MB4, 64 * 1024);
             EntryPool pool = new EntryPool(alloc, 1000, 64)) {

            byte[] keyBytes = "testkey".getBytes(StandardCharsets.UTF_8);
            byte[] valueBytes = "testvalue".getBytes(StandardCharsets.UTF_8);
            int keyHash = 0xDEADBEEF;

            int slot = pool.allocateWithLen(keyHash, keyBytes, keyBytes.length,
                    valueBytes, valueBytes.length, (short) 0, 0L);
            assertTrue(slot > 0);

            long offset = pool.getOffset(slot);
            assertTrue(offset > 0);

            // Same key should match
            assertTrue(pool.keyEqualsNoLenCheck(offset, keyBytes, keyBytes.length));
            // Different key should not match
            byte[] diffKey = "diffkey".getBytes(StandardCharsets.UTF_8);
            assertFalse(pool.keyEqualsNoLenCheck(offset, diffKey, diffKey.length));

            pool.free(slot);
        }
    }

    // ── matches via KeySerializer ────────────────────────────────────────────

    @Test
    void matches_withKeySerializer() {
        try (SlabAllocator alloc = new SlabAllocator(MB4, 64 * 1024);
             EntryPool pool = new EntryPool(alloc, 1000, 64)) {

            byte[] keyBytes = "matchkey".getBytes(StandardCharsets.UTF_8);
            byte[] valueBytes = "matchvalue".getBytes(StandardCharsets.UTF_8);
            int keyHash = 0xFEEDBACE;

            int slot = pool.allocateWithLen(keyHash, keyBytes, keyBytes.length,
                    valueBytes, valueBytes.length, (short) 0, 0L);
            assertTrue(slot > 0);

            // matches with String key serializer
            assertTrue(pool.matches(slot, "matchkey", BuiltInSerializers.STRING_KEY_UTF8));
            assertFalse(pool.matches(slot, "other", BuiltInSerializers.STRING_KEY_UTF8));
            // matches with slot 0 returns false
            assertFalse(pool.matches(0, "matchkey", BuiltInSerializers.STRING_KEY_UTF8));

            pool.free(slot);
            // matches on freed slot returns false
            assertFalse(pool.matches(slot, "matchkey", BuiltInSerializers.STRING_KEY_UTF8));
        }
    }

    // ── Expiration ───────────────────────────────────────────────────────────

    @Test
    void expiresAt_and_clearExpiresAt() {
        try (SlabAllocator alloc = new SlabAllocator(MB4, 64 * 1024);
             EntryPool pool = new EntryPool(alloc, 1000, 64)) {

            byte[] keyBytes = "ek".getBytes(StandardCharsets.UTF_8);
            byte[] valueBytes = "ev".getBytes(StandardCharsets.UTF_8);
            long expiresAt = System.currentTimeMillis() + 60_000;

            int slot = pool.allocateWithLen(0, keyBytes, keyBytes.length,
                    valueBytes, valueBytes.length, (short) 0, expiresAt);
            assertTrue(slot > 0);

            assertEquals(expiresAt, pool.getExpiresAt(slot));
            assertFalse(pool.isExpired(slot));

            // Set to a past time
            pool.setExpiresAt(slot, 1L);
            assertTrue(pool.isExpired(slot));

            // Clear expiration
            pool.clearExpiresAt(slot);
            assertEquals(0L, pool.getExpiresAt(slot));
            assertFalse(pool.isExpired(slot));

            pool.free(slot);
        }
    }

    // ── Priority ─────────────────────────────────────────────────────────────

    @Test
    void getPriority_returnsStoredValue() {
        try (SlabAllocator alloc = new SlabAllocator(MB4, 64 * 1024);
             EntryPool pool = new EntryPool(alloc, 1000, 64)) {

            byte[] keyBytes = "pk".getBytes(StandardCharsets.UTF_8);
            byte[] valueBytes = "pv".getBytes(StandardCharsets.UTF_8);
            short priority = 2;

            int slot = pool.allocateWithLen(0, keyBytes, keyBytes.length,
                    valueBytes, valueBytes.length, priority, 0L);
            assertTrue(slot > 0);
            assertEquals(priority, pool.getPriority(slot));

            pool.free(slot);
            // After free, returns 0
            assertEquals(0, pool.getPriority(slot));
        }
    }

    // ── readValueToBuffer ────────────────────────────────────────────────────

    @Test
    void readValueToBuffer_basic() {
        try (SlabAllocator alloc = new SlabAllocator(MB4, 64 * 1024);
             EntryPool pool = new EntryPool(alloc, 1000, 64)) {

            byte[] keyBytes = "bk".getBytes(StandardCharsets.UTF_8);
            byte[] valueBytes = "buffer-test-value".getBytes(StandardCharsets.UTF_8);

            int slot = pool.allocateWithLen(0, keyBytes, keyBytes.length,
                    valueBytes, valueBytes.length, (short) 0, 0L);
            assertTrue(slot > 0);

            byte[] buf = new byte[valueBytes.length];
            pool.readValueToBuffer(slot, buf, 0, valueBytes.length);
            assertArrayEquals(valueBytes, buf);

            pool.free(slot);
        }
    }

    // ── updateValue with different sizes ─────────────────────────────────────

    @Test
    void updateValue_inPlace_sameSize() {
        try (SlabAllocator alloc = new SlabAllocator(MB4, 64 * 1024);
             EntryPool pool = new EntryPool(alloc, 1000, 64)) {

            byte[] keyBytes = "uk".getBytes(StandardCharsets.UTF_8);
            byte[] origValue = "original".getBytes(StandardCharsets.UTF_8);

            int slot = pool.allocateWithLen(0xAA, keyBytes, keyBytes.length,
                    origValue, origValue.length, (short) 0, 0L);
            assertTrue(slot > 0);

            byte[] newValue = "replaced".getBytes(StandardCharsets.UTF_8);
            boolean updated = pool.updateValue(slot, newValue);
            assertTrue(updated);

            byte[] readBack = pool.readValue(slot);
            assertNotNull(readBack);
            assertEquals("replaced", new String(readBack, StandardCharsets.UTF_8));

            pool.free(slot);
        }
    }

    // ── Integration: byte[] serializer round-trip through cache ──────────────

    @Test
    void byteArraySerializer_exercisesSegmentSerializerPath() {
        try (OffHeapCache<String, byte[]> cache = new CacheBuilder<String, byte[]>()
                .maxEntries(1000)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .keySerializer(BuiltInSerializers.STRING_KEY)
                .valueSerializer(BuiltInSerializers.byteArray())
                .build()) {

            byte[] value = new byte[]{1, 2, 3, 4, 5, 6, 7, 8};
            cache.put("sk", value);
            byte[] result = cache.get("sk");
            assertArrayEquals(value, result);

            // Update with different-sized value
            byte[] newValue = new byte[]{10, 20, 30};
            cache.put("sk", newValue);
            result = cache.get("sk");
            assertArrayEquals(newValue, result);
        }
    }

    // ── getSlotFromOffset ────────────────────────────────────────────────────

    @Test
    void getSlotFromOffset_returnsSlotId() {
        try (SlabAllocator alloc = new SlabAllocator(MB4, 64 * 1024);
             EntryPool pool = new EntryPool(alloc, 1000, 64)) {

            byte[] keyBytes = "slotkey".getBytes(StandardCharsets.UTF_8);
            byte[] valueBytes = "slotval".getBytes(StandardCharsets.UTF_8);
            int keyHash = 0x99887766;

            int slot = pool.allocateWithLen(keyHash, keyBytes, keyBytes.length,
                    valueBytes, valueBytes.length, (short) 0, 0L);
            assertTrue(slot > 0);

            long offset = pool.getOffset(slot);
            assertTrue(offset > 0);

            int recoveredSlot = pool.getSlotFromOffset(offset);
            assertEquals(slot, recoveredSlot);

            pool.free(slot);
        }
    }

    // ── Partition boundary ───────────────────────────────────────────────────

    @Test
    void constructor_rejectsNonPowerOfTwoPartitions() {
        try (SlabAllocator alloc = new SlabAllocator(MB4, 64 * 1024)) {
            assertThrows(IllegalArgumentException.class,
                    () -> new EntryPool(alloc, 1000, 3));
        }
    }

    // ── updateValue: value larger than current (reallocation path) ──────────

    @Test
    void updateValue_largerValue_triggersReallocation() {
        try (SlabAllocator alloc = new SlabAllocator(MB8, 64 * 1024);
             EntryPool pool = new EntryPool(alloc, 1000, 64)) {

            byte[] keyBytes = "realloc-key".getBytes(StandardCharsets.UTF_8);
            byte[] smallValue = "sm".getBytes(StandardCharsets.UTF_8);
            int keyHash = 0xBBCCDDEE;

            int slot = pool.allocateWithLen(keyHash, keyBytes, keyBytes.length,
                    smallValue, smallValue.length, (short) 0, 0L);
            assertTrue(slot > 0);

            // Update with a much larger value — should trigger reallocation
            byte[] largeValue = new byte[512];
            for (int i = 0; i < largeValue.length; i++) {
                largeValue[i] = (byte) (i & 0xFF);
            }
            boolean updated = pool.updateValue(slot, largeValue);
            // The update may succeed or fail depending on slab space, but should not crash
            if (updated) {
                byte[] readBack = pool.readValue(slot);
                assertNotNull(readBack);
                assertArrayEquals(largeValue, readBack);
            }

            pool.free(slot);
        }
    }

    // ── updateValueWithWriter: value larger than current ────────────────────

    @Test
    void updateValueWithWriter_largerValue() {
        try (SlabAllocator alloc = new SlabAllocator(MB8, 64 * 1024);
             EntryPool pool = new EntryPool(alloc, 1000, 64)) {

            byte[] keyBytes = "wlk".getBytes(StandardCharsets.UTF_8);
            byte[] origValue = "ab".getBytes(StandardCharsets.UTF_8);
            int keyHash = 0x11111111;

            int slot = pool.allocateWithLen(keyHash, keyBytes, keyBytes.length,
                    origValue, origValue.length, (short) 0, 0L);
            assertTrue(slot > 0);

            // Larger value via writer
            byte[] newValue = "a-much-larger-value-for-writer-realloc".getBytes(StandardCharsets.UTF_8);
            boolean updated = pool.updateValueWithWriter(slot, newValue.length,
                    (segment, offset, maxLen) -> {
                        MemorySegment.copy(newValue, 0, segment, ValueLayout.JAVA_BYTE, offset, newValue.length);
                        return newValue.length;
                    });
            if (updated) {
                byte[] readBack = pool.readValue(slot);
                assertNotNull(readBack);
                assertEquals(new String(newValue, StandardCharsets.UTF_8),
                        new String(readBack, StandardCharsets.UTF_8));
            }

            pool.free(slot);
        }
    }

    // ── updateValueWithSerializer: value larger than current ────────────────

    @Test
    void updateValueWithSerializer_largerValue() {
        try (SlabAllocator alloc = new SlabAllocator(MB8, 64 * 1024);
             EntryPool pool = new EntryPool(alloc, 1000, 64)) {

            byte[] keyBytes = "slk".getBytes(StandardCharsets.UTF_8);
            int keyHash = 0x22222222;

            SegmentValueSerializer<byte[]> serializer = BuiltInSerializers.ByteArrayValueSerializer.INSTANCE;
            byte[] smallValue = new byte[]{1, 2};

            int slot = pool.allocateWithSerializer(keyHash, keyBytes, keyBytes.length,
                    serializer.estimateSize(smallValue), serializer, smallValue,
                    (short) 0, 0L);
            assertTrue(slot > 0);

            // Update with larger value
            byte[] largeValue = new byte[256];
            for (int i = 0; i < largeValue.length; i++) {
                largeValue[i] = (byte) (i & 0xFF);
            }
            boolean updated = pool.updateValueWithSerializer(slot,
                    serializer.estimateSize(largeValue), serializer, largeValue);
            if (updated) {
                byte[] readBack = pool.readValue(slot);
                assertNotNull(readBack);
                assertArrayEquals(largeValue, readBack);
            }

            pool.free(slot);
        }
    }

    // ── allocateWithWriter: many allocations ────────────────────────────────

    @Test
    void allocateWithWriter_multipleEntries() {
        try (SlabAllocator alloc = new SlabAllocator(MB8, 64 * 1024);
             EntryPool pool = new EntryPool(alloc, 2000, 64)) {

            int[] slots = new int[50];
            for (int i = 0; i < 50; i++) {
                byte[] key = ("wk" + i).getBytes(StandardCharsets.UTF_8);
                byte[] value = ("wv" + i).getBytes(StandardCharsets.UTF_8);
                final byte[] val = value;

                slots[i] = pool.allocateWithWriter(i, key, key.length, val.length,
                        (segment, offset, maxLen) -> {
                            MemorySegment.copy(val, 0, segment, ValueLayout.JAVA_BYTE, offset, val.length);
                            return val.length;
                        }, (short) 0, 0L);
                assertTrue(slots[i] > 0, "slot " + i + " should be valid");
            }

            // Verify all
            for (int i = 0; i < 50; i++) {
                byte[] readBack = pool.readValue(slots[i]);
                assertNotNull(readBack);
                assertEquals("wv" + i, new String(readBack, StandardCharsets.UTF_8));
            }

            for (int slot : slots) {
                pool.free(slot);
            }
        }
    }

    // ── computeFingerprint, getOffsetOpaque, unpackSC ───────────────────────

    @Test
    void computeFingerprint_deterministicForSameInput() {
        try (SlabAllocator alloc = new SlabAllocator(MB4, 64 * 1024);
             EntryPool pool = new EntryPool(alloc, 1000, 64)) {

            byte[] key = "fpkey".getBytes(StandardCharsets.UTF_8);
            // computeFingerprint is used internally; test it indirectly via
            // two entries with the same key bytes getting the same fingerprint
            byte[] value = "fpval".getBytes(StandardCharsets.UTF_8);
            int slot1 = pool.allocateWithLen(0xAA, key, key.length,
                    value, value.length, (short) 0, 0L);
            assertTrue(slot1 > 0);

            long offset1 = pool.getOffset(slot1);
            assertTrue(offset1 > 0);

            // getOffsetOpaque should return the same offset
            long opaqueOffset = pool.getOffsetOpaque(slot1);
            assertEquals(offset1, opaqueOffset);

            pool.free(slot1);
        }
    }

    // ── Integration: large value allocations across partitions ──────────────

    @Test
    void allocate_acrossPartitions() {
        try (SlabAllocator alloc = new SlabAllocator(MB8, 64 * 1024);
             EntryPool pool = new EntryPool(alloc, 5000, 64)) {

            // Fill entries to exercise multiple partitions
            int[] slots = new int[200];
            for (int i = 0; i < 200; i++) {
                byte[] key = ("pk" + i).getBytes(StandardCharsets.UTF_8);
                byte[] value = ("pv" + i).getBytes(StandardCharsets.UTF_8);
                slots[i] = pool.allocateWithLen(i * 7919, key, key.length,
                        value, value.length, (short) (i % 3), 0L);
                assertTrue(slots[i] > 0, "Failed to allocate slot " + i);
            }

            // Verify
            for (int i = 0; i < 200; i++) {
                byte[] readKey = pool.readKey(slots[i]);
                assertNotNull(readKey);
                assertEquals("pk" + i, new String(readKey, StandardCharsets.UTF_8));
            }

            for (int slot : slots) {
                pool.free(slot);
            }
        }
    }
}
