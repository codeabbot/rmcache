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
package com.codeabbot.rmcache.jcache;

import com.codeabbot.rmcache.serializer.BuiltInSerializers;
import com.codeabbot.rmcache.serializer.KeySerializer;
import com.codeabbot.rmcache.serializer.ValueSerializer;

/**
 * Picks the optimal RMCache serializer for a JCache key/value type: the canonical, allocation-lean
 * built-ins for {@code String}/{@code Integer}/{@code Long}/{@code byte[]}, and a JDK-serialization
 * fallback ({@link JdkSerializationSerializer}) for everything else (incl. the {@code Object}
 * default when {@code Configuration} declares no concrete types).
 */
final class Serializers {

    private Serializers() {
    }

    @SuppressWarnings("unchecked")
    static <K> KeySerializer<K> keySerializer(Class<K> type) {
        if (type == String.class) {
            return (KeySerializer<K>) BuiltInSerializers.STRING_KEY;
        }
        if (type == Integer.class) {
            return (KeySerializer<K>) BuiltInSerializers.INT_KEY;
        }
        if (type == Long.class) {
            return (KeySerializer<K>) BuiltInSerializers.LONG_KEY;
        }
        if (type == byte[].class) {
            return (KeySerializer<K>) BuiltInSerializers.BYTE_ARRAY_KEY;
        }
        return new JdkSerializationSerializer<>();
    }

    @SuppressWarnings("unchecked")
    static <V> ValueSerializer<V> valueSerializer(Class<V> type) {
        if (type == String.class) {
            return (ValueSerializer<V>) BuiltInSerializers.STRING_VALUE;
        }
        if (type == Integer.class) {
            return (ValueSerializer<V>) BuiltInSerializers.INT_VALUE;
        }
        if (type == Long.class) {
            return (ValueSerializer<V>) BuiltInSerializers.LONG_VALUE;
        }
        if (type == byte[].class) {
            return (ValueSerializer<V>) BuiltInSerializers.byteArray();
        }
        return new JdkSerializationSerializer<>();
    }
}
