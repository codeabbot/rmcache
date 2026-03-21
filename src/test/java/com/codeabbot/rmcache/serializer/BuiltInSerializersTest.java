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
package com.codeabbot.rmcache.serializer;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for all built-in serializers in the serializer package.
 */
public class BuiltInSerializersTest {

    // -----------------------------------------------------------------------
    // STRING_KEY (UTF-8)
    // -----------------------------------------------------------------------

    @Nested
    class StringKeyUtf8Tests {

        private final KeySerializer<String> ser = BuiltInSerializers.STRING_KEY;

        @Test
        void serializeProducesUtf8Bytes() {
            byte[] bytes = ser.serialize("hello");
            assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), bytes);
        }

        @Test
        void deserializeRecoversOriginalString() {
            byte[] bytes = "hello".getBytes(StandardCharsets.UTF_8);
            assertEquals("hello", ser.deserialize(bytes));
        }

        @Test
        void roundtrip() {
            String original = "round-trip-test";
            assertEquals(original, ser.deserialize(ser.serialize(original)));
        }

        @Test
        void hashCodeMatchesStringHashCode() {
            String key = "testKey";
            assertEquals(key.hashCode(), ser.hashCode(key));
        }

        @Test
        void emptyString() {
            byte[] bytes = ser.serialize("");
            assertEquals(0, bytes.length);
            assertEquals("", ser.deserialize(bytes));
        }

        @Test
        void unicodeMultiByteCharacters() {
            // Snowman U+2603 is 3 bytes in UTF-8
            String unicode = "\u2603";
            byte[] bytes = ser.serialize(unicode);
            assertEquals(3, bytes.length);
            assertEquals(unicode, ser.deserialize(bytes));
        }

        @Test
        void unicodeCjk() {
            String cjk = "\u4e16\u754c"; // "world" in Chinese
            byte[] bytes = ser.serialize(cjk);
            assertEquals(cjk, ser.deserialize(bytes));
        }

        @Test
        void unicodeEmoji() {
            // Supplementary plane character (4 bytes in UTF-8)
            String emoji = "\uD83D\uDE00"; // grinning face
            byte[] bytes = ser.serialize(emoji);
            assertEquals(4, bytes.length);
            assertEquals(emoji, ser.deserialize(bytes));
        }

        @Test
        void stringKeyIsUtf8Instance() {
            assertSame(BuiltInSerializers.STRING_KEY_UTF8, BuiltInSerializers.STRING_KEY);
        }

        @Test
        void stringKeyUtf8IsNotLatin1FastPath() {
            assertFalse(BuiltInSerializers.STRING_KEY_UTF8.isLatin1FastPath());
        }

        @Test
        void matchesFastWithMatchingContent() {
            FastKeySerializer<String> fast = BuiltInSerializers.STRING_KEY_UTF8;
            String key = "hello";
            byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment seg = arena.allocate(keyBytes.length);
                MemorySegment.copy(keyBytes, 0, seg, ValueLayout.JAVA_BYTE, 0, keyBytes.length);
                assertTrue(fast.matchesFast(key, seg, 0, keyBytes.length));
            }
        }

        @Test
        void matchesFastWithMismatchedContent() {
            FastKeySerializer<String> fast = BuiltInSerializers.STRING_KEY_UTF8;
            byte[] otherBytes = "world".getBytes(StandardCharsets.UTF_8);

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment seg = arena.allocate(otherBytes.length);
                MemorySegment.copy(otherBytes, 0, seg, ValueLayout.JAVA_BYTE, 0, otherBytes.length);
                assertFalse(fast.matchesFast("hello", seg, 0, otherBytes.length));
            }
        }

        @Test
        void matchesFastWithDifferentLength() {
            FastKeySerializer<String> fast = BuiltInSerializers.STRING_KEY_UTF8;
            byte[] keyBytes = "hi".getBytes(StandardCharsets.UTF_8);

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment seg = arena.allocate(keyBytes.length);
                MemorySegment.copy(keyBytes, 0, seg, ValueLayout.JAVA_BYTE, 0, keyBytes.length);
                assertFalse(fast.matchesFast("hello", seg, 0, keyBytes.length));
            }
        }

        @Test
        void matchesFastUnicodeRoundtrip() {
            FastKeySerializer<String> fast = BuiltInSerializers.STRING_KEY_UTF8;
            String key = "\u2603\u4e16\u754c";
            byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment seg = arena.allocate(keyBytes.length);
                MemorySegment.copy(keyBytes, 0, seg, ValueLayout.JAVA_BYTE, 0, keyBytes.length);
                assertTrue(fast.matchesFast(key, seg, 0, keyBytes.length));
            }
        }

        @Test
        void matchesFastWithOffset() {
            FastKeySerializer<String> fast = BuiltInSerializers.STRING_KEY_UTF8;
            String key = "test";
            byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);

            try (Arena arena = Arena.ofConfined()) {
                // Allocate extra space; place key bytes at offset 8
                MemorySegment seg = arena.allocate(8 + keyBytes.length);
                MemorySegment.copy(keyBytes, 0, seg, ValueLayout.JAVA_BYTE, 8, keyBytes.length);
                assertTrue(fast.matchesFast(key, seg, 8, keyBytes.length));
                // Offset 0 should not match (garbage/zeros there)
                assertFalse(fast.matchesFast(key, seg, 0, keyBytes.length));
            }
        }
    }

    // -----------------------------------------------------------------------
    // STRING_KEY_LATIN1
    // -----------------------------------------------------------------------

    @Nested
    class StringKeyLatin1Tests {

        private final BuiltInSerializers.StringKeySerializer ser = BuiltInSerializers.STRING_KEY_LATIN1;

        @Test
        void serializeProducesLatin1Bytes() {
            byte[] bytes = ser.serialize("hello");
            assertArrayEquals("hello".getBytes(StandardCharsets.ISO_8859_1), bytes);
        }

        @Test
        void deserializeRecoversOriginalString() {
            byte[] bytes = "hello".getBytes(StandardCharsets.ISO_8859_1);
            assertEquals("hello", ser.deserialize(bytes));
        }

        @Test
        void roundtrip() {
            String original = "caf\u00E9"; // Latin1 char
            assertEquals(original, ser.deserialize(ser.serialize(original)));
        }

        @Test
        void isLatin1FastPath() {
            assertTrue(ser.isLatin1FastPath());
        }

        @Test
        void hashCodeMatchesStringHashCode() {
            String key = "latin1-key";
            assertEquals(key.hashCode(), ser.hashCode(key));
        }

        @Test
        void emptyString() {
            byte[] bytes = ser.serialize("");
            assertEquals(0, bytes.length);
            assertEquals("", ser.deserialize(bytes));
        }

        @Test
        void matchesFastLatin1Path() {
            String key = "abc";
            byte[] keyBytes = key.getBytes(StandardCharsets.ISO_8859_1);

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment seg = arena.allocate(keyBytes.length);
                MemorySegment.copy(keyBytes, 0, seg, ValueLayout.JAVA_BYTE, 0, keyBytes.length);
                assertTrue(ser.matchesFast(key, seg, 0, keyBytes.length));
            }
        }

        @Test
        void matchesFastLatin1LengthMismatch() {
            String key = "abcd";
            byte[] twoBytes = "ab".getBytes(StandardCharsets.ISO_8859_1);

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment seg = arena.allocate(twoBytes.length);
                MemorySegment.copy(twoBytes, 0, seg, ValueLayout.JAVA_BYTE, 0, twoBytes.length);
                // Latin1 fast path checks length == key.length()
                assertFalse(ser.matchesFast(key, seg, 0, twoBytes.length));
            }
        }

        @Test
        void matchesFastLatin1ContentMismatch() {
            String key = "abc";
            byte[] other = "xyz".getBytes(StandardCharsets.ISO_8859_1);

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment seg = arena.allocate(other.length);
                MemorySegment.copy(other, 0, seg, ValueLayout.JAVA_BYTE, 0, other.length);
                assertFalse(ser.matchesFast(key, seg, 0, other.length));
            }
        }

        @Test
        void matchesFastLatin1WithOffset() {
            String key = "ok";
            byte[] keyBytes = key.getBytes(StandardCharsets.ISO_8859_1);

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment seg = arena.allocate(16 + keyBytes.length);
                MemorySegment.copy(keyBytes, 0, seg, ValueLayout.JAVA_BYTE, 16, keyBytes.length);
                assertTrue(ser.matchesFast(key, seg, 16, keyBytes.length));
            }
        }
    }

    // -----------------------------------------------------------------------
    // STRING_VALUE
    // -----------------------------------------------------------------------

    @Nested
    class StringValueTests {

        private final ValueSerializer<String> ser = BuiltInSerializers.STRING_VALUE;

        @Test
        void serializeProducesUtf8Bytes() {
            assertArrayEquals("value".getBytes(StandardCharsets.UTF_8), ser.serialize("value"));
        }

        @Test
        void deserialize() {
            assertEquals("value", ser.deserialize("value".getBytes(StandardCharsets.UTF_8)));
        }

        @Test
        void roundtrip() {
            String original = "hello world!";
            assertEquals(original, ser.deserialize(ser.serialize(original)));
        }

        @Test
        void emptyString() {
            byte[] bytes = ser.serialize("");
            assertEquals(0, bytes.length);
            assertEquals("", ser.deserialize(bytes));
        }

        @Test
        void unicode() {
            String s = "\u2603 snowman \u4e16\u754c";
            assertEquals(s, ser.deserialize(ser.serialize(s)));
        }

        @Test
        void factoryMethodReturnsSameInstance() {
            assertSame(BuiltInSerializers.STRING_VALUE, BuiltInSerializers.string());
        }
    }

    // -----------------------------------------------------------------------
    // INT_KEY
    // -----------------------------------------------------------------------

    @Nested
    class IntKeyTests {

        private final FastKeySerializer<Integer> ser = BuiltInSerializers.INT_KEY;

        @Test
        void serializePositive() {
            byte[] bytes = ser.serialize(0x01020304);
            assertArrayEquals(new byte[]{0x01, 0x02, 0x03, 0x04}, bytes);
        }

        @Test
        void deserializePositive() {
            assertEquals(0x01020304, ser.deserialize(new byte[]{0x01, 0x02, 0x03, 0x04}));
        }

        @Test
        void roundtrip() {
            assertEquals(42, ser.deserialize(ser.serialize(42)));
        }

        @Test
        void hashCodeIsIdentity() {
            assertEquals(42, ser.hashCode(42));
            assertEquals(-1, ser.hashCode(-1));
            assertEquals(0, ser.hashCode(0));
        }

        @Test
        void zero() {
            byte[] bytes = ser.serialize(0);
            assertArrayEquals(new byte[]{0, 0, 0, 0}, bytes);
            assertEquals(0, ser.deserialize(bytes));
        }

        @Test
        void negativeValue() {
            int neg = -1;
            byte[] bytes = ser.serialize(neg);
            // -1 = 0xFFFFFFFF in big-endian
            assertArrayEquals(new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF}, bytes);
            assertEquals(neg, ser.deserialize(bytes));
        }

        @Test
        void integerMinValue() {
            int min = Integer.MIN_VALUE;
            byte[] bytes = ser.serialize(min);
            assertEquals(min, ser.deserialize(bytes));
        }

        @Test
        void integerMaxValue() {
            int max = Integer.MAX_VALUE;
            byte[] bytes = ser.serialize(max);
            assertEquals(max, ser.deserialize(bytes));
        }

        @Test
        void deserializeThrowsOnShortArray() {
            assertThrows(IllegalArgumentException.class, () -> ser.deserialize(new byte[]{1, 2}));
        }

        @Test
        void deserializeThrowsOnEmptyArray() {
            assertThrows(IllegalArgumentException.class, () -> ser.deserialize(new byte[0]));
        }

        @Test
        void matchesFastMatching() {
            int key = 0xDEADBEEF;
            byte[] keyBytes = ser.serialize(key);

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment seg = arena.allocate(4);
                MemorySegment.copy(keyBytes, 0, seg, ValueLayout.JAVA_BYTE, 0, 4);
                assertTrue(ser.matchesFast(key, seg, 0, 4));
            }
        }

        @Test
        void matchesFastMismatch() {
            byte[] otherBytes = ser.serialize(999);

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment seg = arena.allocate(4);
                MemorySegment.copy(otherBytes, 0, seg, ValueLayout.JAVA_BYTE, 0, 4);
                assertFalse(ser.matchesFast(123, seg, 0, 4));
            }
        }

        @Test
        void matchesFastWrongLength() {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment seg = arena.allocate(8);
                assertFalse(ser.matchesFast(42, seg, 0, 3));
                assertFalse(ser.matchesFast(42, seg, 0, 5));
                assertFalse(ser.matchesFast(42, seg, 0, 0));
            }
        }

        @Test
        void matchesFastWithOffset() {
            int key = 42;
            byte[] keyBytes = ser.serialize(key);

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment seg = arena.allocate(16 + 4);
                MemorySegment.copy(keyBytes, 0, seg, ValueLayout.JAVA_BYTE, 16, 4);
                assertTrue(ser.matchesFast(key, seg, 16, 4));
            }
        }

        @Test
        void matchesFastMinMax() {
            for (int key : new int[]{Integer.MIN_VALUE, Integer.MAX_VALUE, 0, -1}) {
                byte[] keyBytes = ser.serialize(key);
                try (Arena arena = Arena.ofConfined()) {
                    MemorySegment seg = arena.allocate(4);
                    MemorySegment.copy(keyBytes, 0, seg, ValueLayout.JAVA_BYTE, 0, 4);
                    assertTrue(ser.matchesFast(key, seg, 0, 4),
                            "matchesFast should return true for key=" + key);
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // INT_VALUE
    // -----------------------------------------------------------------------

    @Nested
    class IntValueTests {

        private final ValueSerializer<Integer> ser = BuiltInSerializers.INT_VALUE;

        @Test
        void serializeBigEndian() {
            byte[] bytes = ser.serialize(0x01020304);
            assertArrayEquals(new byte[]{0x01, 0x02, 0x03, 0x04}, bytes);
        }

        @Test
        void deserialize() {
            assertEquals(0x01020304, ser.deserialize(new byte[]{0x01, 0x02, 0x03, 0x04}));
        }

        @Test
        void roundtrip() {
            for (int v : new int[]{0, 1, -1, Integer.MIN_VALUE, Integer.MAX_VALUE, 123456789}) {
                assertEquals(v, ser.deserialize(ser.serialize(v)),
                        "Roundtrip failed for " + v);
            }
        }

        @Test
        void deserializeThrowsOnShortArray() {
            assertThrows(IllegalArgumentException.class, () -> ser.deserialize(new byte[]{1}));
        }
    }

    // -----------------------------------------------------------------------
    // LONG_KEY
    // -----------------------------------------------------------------------

    @Nested
    class LongKeyTests {

        private final FastKeySerializer<Long> ser = BuiltInSerializers.LONG_KEY;

        @Test
        void serializeBigEndian() {
            byte[] bytes = ser.serialize(0x0102030405060708L);
            assertArrayEquals(new byte[]{0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08}, bytes);
        }

        @Test
        void deserialize() {
            assertEquals(0x0102030405060708L,
                    ser.deserialize(new byte[]{0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08}));
        }

        @Test
        void roundtrip() {
            long original = 9876543210L;
            assertEquals(original, ser.deserialize(ser.serialize(original)));
        }

        @Test
        void hashCodeUsesXorFold() {
            long key = 0x00000001_00000002L;
            // Expected: (int)(key ^ (key >>> 32)) = 1 ^ 2 = 3
            assertEquals(3, ser.hashCode(key));
        }

        @Test
        void hashCodeZero() {
            assertEquals(0, ser.hashCode(0L));
        }

        @Test
        void zero() {
            byte[] bytes = ser.serialize(0L);
            assertArrayEquals(new byte[8], bytes);
            assertEquals(0L, ser.deserialize(bytes));
        }

        @Test
        void longMinValue() {
            long min = Long.MIN_VALUE;
            byte[] bytes = ser.serialize(min);
            assertEquals(min, ser.deserialize(bytes));
        }

        @Test
        void longMaxValue() {
            long max = Long.MAX_VALUE;
            byte[] bytes = ser.serialize(max);
            assertEquals(max, ser.deserialize(bytes));
        }

        @Test
        void negativeOne() {
            long neg = -1L;
            byte[] bytes = ser.serialize(neg);
            byte[] expected = new byte[8];
            Arrays.fill(expected, (byte) 0xFF);
            assertArrayEquals(expected, bytes);
            assertEquals(neg, ser.deserialize(bytes));
        }

        @Test
        void deserializeThrowsOnShortArray() {
            assertThrows(IllegalArgumentException.class, () -> ser.deserialize(new byte[]{1, 2, 3, 4}));
        }

        @Test
        void deserializeThrowsOnEmptyArray() {
            assertThrows(IllegalArgumentException.class, () -> ser.deserialize(new byte[0]));
        }

        @Test
        void matchesFastMatching() {
            long key = 0xCAFEBABEDEADBEEFL;
            byte[] keyBytes = ser.serialize(key);

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment seg = arena.allocate(8);
                MemorySegment.copy(keyBytes, 0, seg, ValueLayout.JAVA_BYTE, 0, 8);
                assertTrue(ser.matchesFast(key, seg, 0, 8));
            }
        }

        @Test
        void matchesFastMismatch() {
            byte[] otherBytes = ser.serialize(999L);

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment seg = arena.allocate(8);
                MemorySegment.copy(otherBytes, 0, seg, ValueLayout.JAVA_BYTE, 0, 8);
                assertFalse(ser.matchesFast(123L, seg, 0, 8));
            }
        }

        @Test
        void matchesFastWrongLength() {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment seg = arena.allocate(16);
                assertFalse(ser.matchesFast(42L, seg, 0, 4));
                assertFalse(ser.matchesFast(42L, seg, 0, 7));
                assertFalse(ser.matchesFast(42L, seg, 0, 9));
                assertFalse(ser.matchesFast(42L, seg, 0, 0));
            }
        }

        @Test
        void matchesFastWithOffset() {
            long key = Long.MAX_VALUE;
            byte[] keyBytes = ser.serialize(key);

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment seg = arena.allocate(32 + 8);
                MemorySegment.copy(keyBytes, 0, seg, ValueLayout.JAVA_BYTE, 32, 8);
                assertTrue(ser.matchesFast(key, seg, 32, 8));
            }
        }

        @Test
        void matchesFastMinMax() {
            for (long key : new long[]{Long.MIN_VALUE, Long.MAX_VALUE, 0L, -1L}) {
                byte[] keyBytes = ser.serialize(key);
                try (Arena arena = Arena.ofConfined()) {
                    MemorySegment seg = arena.allocate(8);
                    MemorySegment.copy(keyBytes, 0, seg, ValueLayout.JAVA_BYTE, 0, 8);
                    assertTrue(ser.matchesFast(key, seg, 0, 8),
                            "matchesFast should return true for key=" + key);
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // LONG_VALUE
    // -----------------------------------------------------------------------

    @Nested
    class LongValueTests {

        private final ValueSerializer<Long> ser = BuiltInSerializers.LONG_VALUE;

        @Test
        void serializeBigEndian() {
            byte[] bytes = ser.serialize(0x0102030405060708L);
            assertArrayEquals(new byte[]{0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08}, bytes);
        }

        @Test
        void deserialize() {
            assertEquals(0x0102030405060708L,
                    ser.deserialize(new byte[]{0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08}));
        }

        @Test
        void roundtrip() {
            for (long v : new long[]{0L, 1L, -1L, Long.MIN_VALUE, Long.MAX_VALUE, 9876543210L}) {
                assertEquals(v, ser.deserialize(ser.serialize(v)),
                        "Roundtrip failed for " + v);
            }
        }

        @Test
        void deserializeThrowsOnShortArray() {
            assertThrows(IllegalArgumentException.class, () -> ser.deserialize(new byte[]{1, 2, 3}));
        }
    }

    // -----------------------------------------------------------------------
    // BYTE_ARRAY_KEY
    // -----------------------------------------------------------------------

    @Nested
    class ByteArrayKeyTests {

        private final FastKeySerializer<byte[]> ser = BuiltInSerializers.BYTE_ARRAY_KEY;

        @Test
        void serializeReturnsSameReference() {
            byte[] key = {1, 2, 3};
            assertSame(key, ser.serialize(key));
        }

        @Test
        void deserializeReturnsSameReference() {
            byte[] bytes = {4, 5, 6};
            assertSame(bytes, ser.deserialize(bytes));
        }

        @Test
        void roundtrip() {
            byte[] original = {10, 20, 30, 40};
            assertSame(original, ser.deserialize(ser.serialize(original)));
        }

        @Test
        void hashCodeMatchesArraysHashCode() {
            byte[] key = {1, 2, 3, 4, 5};
            assertEquals(Arrays.hashCode(key), ser.hashCode(key));
        }

        @Test
        void hashCodeEmptyArray() {
            assertEquals(Arrays.hashCode(new byte[0]), ser.hashCode(new byte[0]));
        }

        @Test
        void emptyArray() {
            byte[] empty = new byte[0];
            assertSame(empty, ser.serialize(empty));
            assertSame(empty, ser.deserialize(empty));
        }

        @Test
        void matchesFastMatching() {
            byte[] key = {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE};

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment seg = arena.allocate(key.length);
                MemorySegment.copy(key, 0, seg, ValueLayout.JAVA_BYTE, 0, key.length);
                assertTrue(ser.matchesFast(key, seg, 0, key.length));
            }
        }

        @Test
        void matchesFastMismatch() {
            byte[] key = {1, 2, 3};
            byte[] other = {1, 2, 4};

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment seg = arena.allocate(other.length);
                MemorySegment.copy(other, 0, seg, ValueLayout.JAVA_BYTE, 0, other.length);
                assertFalse(ser.matchesFast(key, seg, 0, other.length));
            }
        }

        @Test
        void matchesFastLengthMismatch() {
            byte[] key = {1, 2, 3};

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment seg = arena.allocate(8);
                assertFalse(ser.matchesFast(key, seg, 0, 2));
                assertFalse(ser.matchesFast(key, seg, 0, 4));
            }
        }

        @Test
        void matchesFastEmptyKey() {
            byte[] key = new byte[0];

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment seg = arena.allocate(8);
                assertTrue(ser.matchesFast(key, seg, 0, 0));
            }
        }

        @Test
        void matchesFastWithOffset() {
            byte[] key = {0x10, 0x20, 0x30};

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment seg = arena.allocate(24 + key.length);
                MemorySegment.copy(key, 0, seg, ValueLayout.JAVA_BYTE, 24, key.length);
                assertTrue(ser.matchesFast(key, seg, 24, key.length));
                // Wrong offset
                assertFalse(ser.matchesFast(key, seg, 0, key.length));
            }
        }
    }

    // -----------------------------------------------------------------------
    // BYTE_ARRAY_VALUE (ByteArrayValueSerializer — implements SegmentValueSerializer)
    // -----------------------------------------------------------------------

    @Nested
    class ByteArrayValueTests {

        private final BuiltInSerializers.ByteArrayValueSerializer ser =
                BuiltInSerializers.ByteArrayValueSerializer.INSTANCE;

        @Test
        void serializeReturnsSameReference() {
            byte[] value = {1, 2, 3};
            assertSame(value, ser.serialize(value));
        }

        @Test
        void deserializeReturnsSameReference() {
            byte[] bytes = {4, 5, 6};
            assertSame(bytes, ser.deserialize(bytes));
        }

        @Test
        void roundtrip() {
            byte[] original = {7, 8, 9};
            assertSame(original, ser.deserialize(ser.serialize(original)));
        }

        @Test
        void estimateSize() {
            assertEquals(5, ser.estimateSize(new byte[5]));
            assertEquals(0, ser.estimateSize(new byte[0]));
            assertEquals(1024, ser.estimateSize(new byte[1024]));
        }

        @Test
        void serializeToByteArray() {
            byte[] value = {10, 20, 30};
            byte[] dest = new byte[8];
            int written = ser.serializeTo(value, dest, 2);
            assertEquals(3, written);
            assertEquals(10, dest[2]);
            assertEquals(20, dest[3]);
            assertEquals(30, dest[4]);
        }

        @Test
        void serializeToMemorySegment() {
            byte[] value = {(byte) 0xAA, (byte) 0xBB, (byte) 0xCC};

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment seg = arena.allocate(16);
                int written = ser.serializeTo(value, seg, 4, 10);
                assertEquals(3, written);
                assertEquals((byte) 0xAA, seg.get(ValueLayout.JAVA_BYTE, 4));
                assertEquals((byte) 0xBB, seg.get(ValueLayout.JAVA_BYTE, 5));
                assertEquals((byte) 0xCC, seg.get(ValueLayout.JAVA_BYTE, 6));
            }
        }

        @Test
        void serializeToMemorySegmentTruncates() {
            byte[] value = {1, 2, 3, 4, 5};

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment seg = arena.allocate(16);
                int written = ser.serializeTo(value, seg, 0, 3);
                // Should write at most maxLen bytes
                assertEquals(3, written);
            }
        }

        @Test
        void deserializeFrom() {
            byte[] src = {0, 0, 10, 20, 30, 0, 0};
            byte[] result = ser.deserializeFrom(src, 2, 3);
            assertArrayEquals(new byte[]{10, 20, 30}, result);
        }

        @Test
        void deserializeFromEntireArray() {
            byte[] src = {1, 2, 3};
            byte[] result = ser.deserializeFrom(src, 0, 3);
            assertArrayEquals(src, result);
            assertNotSame(src, result); // copyOfRange always creates a new array
        }

        @Test
        void factoryMethodReturnsSameInstance() {
            assertSame(BuiltInSerializers.ByteArrayValueSerializer.INSTANCE,
                    BuiltInSerializers.byteArray());
        }

        @Test
        void isSegmentValueSerializer() {
            assertInstanceOf(SegmentValueSerializer.class, ser);
        }

        @Test
        void isStreamingSerializer() {
            assertInstanceOf(StreamingSerializer.class, ser);
        }
    }

    // -----------------------------------------------------------------------
    // StringEncoding
    // -----------------------------------------------------------------------

    @Nested
    class StringEncodingTests {

        @Test
        void utf8Exists() {
            assertNotNull(StringEncoding.UTF8);
            assertEquals("UTF8", StringEncoding.UTF8.name());
        }

        @Test
        void latin1Exists() {
            assertNotNull(StringEncoding.LATIN1);
            assertEquals("LATIN1", StringEncoding.LATIN1.name());
        }

        @Test
        void exactlyTwoValues() {
            assertEquals(2, StringEncoding.values().length);
        }

        @Test
        void valueOfRoundtrip() {
            assertEquals(StringEncoding.UTF8, StringEncoding.valueOf("UTF8"));
            assertEquals(StringEncoding.LATIN1, StringEncoding.valueOf("LATIN1"));
        }
    }

    // -----------------------------------------------------------------------
    // Factory methods
    // -----------------------------------------------------------------------

    @Nested
    class FactoryMethodTests {

        @Test
        void stringReturnsStringValue() {
            ValueSerializer<String> s = BuiltInSerializers.string();
            assertSame(BuiltInSerializers.STRING_VALUE, s);
        }

        @Test
        void byteArrayReturnsByteArrayValueSerializer() {
            ValueSerializer<byte[]> s = BuiltInSerializers.byteArray();
            assertSame(BuiltInSerializers.ByteArrayValueSerializer.INSTANCE, s);
        }

        @Test
        void stringKeyFieldIsUtf8() {
            assertSame(BuiltInSerializers.STRING_KEY_UTF8, BuiltInSerializers.STRING_KEY);
        }

        @Test
        void stringKeyLatin1IsDifferentFromUtf8() {
            assertNotSame(BuiltInSerializers.STRING_KEY_UTF8, BuiltInSerializers.STRING_KEY_LATIN1);
        }
    }

    // -----------------------------------------------------------------------
    // Interface hierarchy checks
    // -----------------------------------------------------------------------

    @Nested
    class InterfaceHierarchyTests {

        @Test
        void stringKeyImplementsFastKeySerializer() {
            assertInstanceOf(FastKeySerializer.class, BuiltInSerializers.STRING_KEY);
        }

        @Test
        void stringKeyLatin1ImplementsFastKeySerializer() {
            assertInstanceOf(FastKeySerializer.class, BuiltInSerializers.STRING_KEY_LATIN1);
        }

        @Test
        void intKeyImplementsFastKeySerializer() {
            assertInstanceOf(FastKeySerializer.class, BuiltInSerializers.INT_KEY);
        }

        @Test
        void longKeyImplementsFastKeySerializer() {
            assertInstanceOf(FastKeySerializer.class, BuiltInSerializers.LONG_KEY);
        }

        @Test
        void byteArrayKeyImplementsFastKeySerializer() {
            assertInstanceOf(FastKeySerializer.class, BuiltInSerializers.BYTE_ARRAY_KEY);
        }

        @Test
        void stringValueImplementsValueSerializer() {
            assertInstanceOf(ValueSerializer.class, BuiltInSerializers.STRING_VALUE);
        }

        @Test
        void intValueImplementsValueSerializer() {
            assertInstanceOf(ValueSerializer.class, BuiltInSerializers.INT_VALUE);
        }

        @Test
        void longValueImplementsValueSerializer() {
            assertInstanceOf(ValueSerializer.class, BuiltInSerializers.LONG_VALUE);
        }

        @Test
        void byteArrayValueImplementsSegmentValueSerializer() {
            assertInstanceOf(SegmentValueSerializer.class, BuiltInSerializers.byteArray());
        }
    }

    // -----------------------------------------------------------------------
    // KeySerializer default methods (matches, hashCode on segment)
    // -----------------------------------------------------------------------

    @Nested
    class KeySerializerDefaultMethodTests {

        @Test
        void matchesDefaultDelegatesToMatchesFastForFastKeySerializer() {
            // For FastKeySerializer, matches() delegates to matchesFast()
            FastKeySerializer<Integer> ser = BuiltInSerializers.INT_KEY;
            byte[] keyBytes = ser.serialize(42);

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment seg = arena.allocate(keyBytes.length);
                MemorySegment.copy(keyBytes, 0, seg, ValueLayout.JAVA_BYTE, 0, keyBytes.length);

                // matches() should give same result as matchesFast()
                assertTrue(ser.matches(42, seg, 0, keyBytes.length));
                assertFalse(ser.matches(99, seg, 0, keyBytes.length));
            }
        }

        @Test
        void matchesDefaultOnLongKey() {
            FastKeySerializer<Long> ser = BuiltInSerializers.LONG_KEY;
            long key = 12345678901234L;
            byte[] keyBytes = ser.serialize(key);

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment seg = arena.allocate(keyBytes.length);
                MemorySegment.copy(keyBytes, 0, seg, ValueLayout.JAVA_BYTE, 0, keyBytes.length);
                assertTrue(ser.matches(key, seg, 0, keyBytes.length));
            }
        }
    }
}
