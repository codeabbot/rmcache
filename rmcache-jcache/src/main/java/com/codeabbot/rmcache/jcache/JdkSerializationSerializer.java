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

import com.codeabbot.rmcache.serializer.KeySerializer;
import com.codeabbot.rmcache.serializer.ValueSerializer;

import javax.cache.CacheException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

/**
 * Fallback JDK-serialization serializer used for arbitrary {@link java.io.Serializable}
 * key/value types that have no dedicated built-in serializer. Implements both
 * {@link KeySerializer} and {@link ValueSerializer} so a single instance can back either side.
 *
 * <p>JCache store-by-value requires keys and values to be {@code Serializable}. For keys, the
 * cache matches by comparing the stored serialized bytes (via {@link KeySerializer}'s default
 * {@code matches}); this requires serialization to be <em>deterministic</em> for equal keys —
 * true for {@code String}, the boxed numerics, and well-behaved value objects (the normal
 * JCache key types). For the common {@code String}/{@code Integer}/{@code Long}/{@code byte[]}
 * types, {@link Serializers} dispatches to RMCache's canonical built-ins instead of this class.
 */
final class JdkSerializationSerializer<T> implements KeySerializer<T>, ValueSerializer<T> {

    @Override
    public byte[] serialize(T obj) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(obj);
            oos.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new CacheException("RMCache JCache: failed to serialize " + obj, e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public T deserialize(byte[] bytes) {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
             ObjectInputStream ois = new ObjectInputStream(bis)) {
            return (T) ois.readObject();
        } catch (IOException | ClassNotFoundException e) {
            throw new CacheException("RMCache JCache: failed to deserialize value", e);
        }
    }

    /** Uses the key's own {@code hashCode()} — equal keys hash equally by contract. */
    @Override
    public int hashCode(T key) {
        return key == null ? 0 : key.hashCode();
    }
}
