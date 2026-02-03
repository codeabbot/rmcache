package com.codeabbot.rmcache.serializer;

import java.nio.charset.StandardCharsets;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;

/**
 * Built-in serializers for common types.
 *
 * @author Rabindra Meher
 */
public final class BuiltInSerializers {

    private BuiltInSerializers() {
    }

    public static ValueSerializer<String> string() {
        return STRING_VALUE;
    }

    public static ValueSerializer<byte[]> byteArray() {
        return ByteArrayValueSerializer.INSTANCE;
    }

    public static final class StringKeySerializer implements KeySerializer<String> {
        private final java.nio.charset.Charset charset;
        private final boolean latin1FastPath;

        public StringKeySerializer(java.nio.charset.Charset charset, boolean latin1FastPath) {
            this.charset = charset;
            this.latin1FastPath = latin1FastPath;
        }

        public boolean isLatin1FastPath() {
            return latin1FastPath;
        }

        @Override
        public byte[] serialize(String key) {
            return key.getBytes(charset);
        }

        @Override
        public String deserialize(byte[] bytes) {
            return new String(bytes, charset);
        }

        @Override
        public int hashCode(String key) {
            return key.hashCode();
        }

        @Override
        public boolean matches(String key, MemorySegment segment, long offset, int length) {
            if (latin1FastPath) {
                if (length != key.length()) {
                    return false;
                }
                for (int i = 0; i < length; i++) {
                    if (segment.get(ValueLayout.JAVA_BYTE, offset + i) != (byte) key.charAt(i)) {
                        return false;
                    }
                }
                return true;
            }
            byte[] bytes = serialize(key);
            if (bytes.length != length)
                return false;
            return segment.asSlice(offset, (long) length).mismatch(MemorySegment.ofArray(bytes)) == -1L;
        }
    }

    public static final StringKeySerializer STRING_KEY_UTF8 =
            new StringKeySerializer(StandardCharsets.UTF_8, false);
    public static final StringKeySerializer STRING_KEY_LATIN1 =
            new StringKeySerializer(StandardCharsets.ISO_8859_1, true);

    public static final KeySerializer<String> STRING_KEY = STRING_KEY_UTF8;

    public static final ValueSerializer<String> STRING_VALUE = new ValueSerializer<>() {
        @Override
        public byte[] serialize(String value) {
            return value.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public String deserialize(byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    };

    public static final KeySerializer<Integer> INT_KEY = new KeySerializer<>() {
        @Override
        public byte[] serialize(Integer key) {
            int v = key;
            return new byte[] {
                    (byte) (v >>> 24),
                    (byte) (v >>> 16),
                    (byte) (v >>> 8),
                    (byte) v
            };
        }

        @Override
        public Integer deserialize(byte[] bytes) {
            if (bytes.length < 4)
                throw new IllegalArgumentException("Invalid int byte array length: " + bytes.length);
            return ((bytes[0] & 0xFF) << 24)
                    | ((bytes[1] & 0xFF) << 16)
                    | ((bytes[2] & 0xFF) << 8)
                    | (bytes[3] & 0xFF);
        }

        @Override
        public int hashCode(Integer key) {
            return key;
        }
    };

    public static final ValueSerializer<Integer> INT_VALUE = new ValueSerializer<>() {
        @Override
        public byte[] serialize(Integer value) {
            int v = value;
            return new byte[] {
                    (byte) (v >>> 24),
                    (byte) (v >>> 16),
                    (byte) (v >>> 8),
                    (byte) v
            };
        }

        @Override
        public Integer deserialize(byte[] bytes) {
            if (bytes.length < 4)
                throw new IllegalArgumentException("Invalid int byte array length: " + bytes.length);
            return ((bytes[0] & 0xFF) << 24)
                    | ((bytes[1] & 0xFF) << 16)
                    | ((bytes[2] & 0xFF) << 8)
                    | (bytes[3] & 0xFF);
        }
    };

    public static final KeySerializer<Long> LONG_KEY = new KeySerializer<>() {
        @Override
        public byte[] serialize(Long key) {
            long v = key;
            return new byte[] {
                    (byte) (v >>> 56),
                    (byte) (v >>> 48),
                    (byte) (v >>> 40),
                    (byte) (v >>> 32),
                    (byte) (v >>> 24),
                    (byte) (v >>> 16),
                    (byte) (v >>> 8),
                    (byte) v
            };
        }

        @Override
        public Long deserialize(byte[] bytes) {
            if (bytes.length < 8)
                throw new IllegalArgumentException("Invalid long byte array length: " + bytes.length);
            return ((long) (bytes[0] & 0xFF) << 56)
                    | ((long) (bytes[1] & 0xFF) << 48)
                    | ((long) (bytes[2] & 0xFF) << 40)
                    | ((long) (bytes[3] & 0xFF) << 32)
                    | ((long) (bytes[4] & 0xFF) << 24)
                    | ((long) (bytes[5] & 0xFF) << 16)
                    | ((long) (bytes[6] & 0xFF) << 8)
                    | ((long) (bytes[7] & 0xFF));
        }

        @Override
        public int hashCode(Long key) {
            return (int) (key ^ (key >>> 32));
        }
    };

    public static final ValueSerializer<Long> LONG_VALUE = new ValueSerializer<>() {
        @Override
        public byte[] serialize(Long value) {
            long v = value;
            return new byte[] {
                    (byte) (v >>> 56),
                    (byte) (v >>> 48),
                    (byte) (v >>> 40),
                    (byte) (v >>> 32),
                    (byte) (v >>> 24),
                    (byte) (v >>> 16),
                    (byte) (v >>> 8),
                    (byte) v
            };
        }

        @Override
        public Long deserialize(byte[] bytes) {
            if (bytes.length < 8)
                throw new IllegalArgumentException("Invalid long byte array length: " + bytes.length);
            return ((long) (bytes[0] & 0xFF) << 56)
                    | ((long) (bytes[1] & 0xFF) << 48)
                    | ((long) (bytes[2] & 0xFF) << 40)
                    | ((long) (bytes[3] & 0xFF) << 32)
                    | ((long) (bytes[4] & 0xFF) << 24)
                    | ((long) (bytes[5] & 0xFF) << 16)
                    | ((long) (bytes[6] & 0xFF) << 8)
                    | ((long) (bytes[7] & 0xFF));
        }
    };

    public static final KeySerializer<byte[]> BYTE_ARRAY_KEY = new KeySerializer<>() {
        @Override
        public byte[] serialize(byte[] key) {
            return key;
        }

        @Override
        public byte[] deserialize(byte[] bytes) {
            return bytes;
        }

        @Override
        public int hashCode(byte[] key) {
            return Arrays.hashCode(key);
        }
    };

    public static final class ByteArrayValueSerializer implements SegmentValueSerializer<byte[]> {
        public static final ByteArrayValueSerializer INSTANCE = new ByteArrayValueSerializer();

        private ByteArrayValueSerializer() {
        }

        @Override
        public byte[] serialize(byte[] value) {
            return value;
        }

        @Override
        public byte[] deserialize(byte[] bytes) {
            return bytes;
        }

        @Override
        public int estimateSize(byte[] value) {
            return value.length;
        }

        @Override
        public int serializeTo(byte[] value, byte[] dest, int offset) {
            System.arraycopy(value, 0, dest, offset, value.length);
            return value.length;
        }

        @Override
        public int serializeTo(byte[] value, MemorySegment dest, long offset, int maxLen) {
            int len = Math.min(value.length, maxLen);
            MemorySegment.copy(value, 0, dest, ValueLayout.JAVA_BYTE, offset, len);
            return len;
        }

        @Override
        public byte[] deserializeFrom(byte[] src, int offset, int length) {
            return Arrays.copyOfRange(src, offset, offset + length);
        }
    }
}
