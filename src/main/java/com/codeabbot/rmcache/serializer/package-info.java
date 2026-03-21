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
/**
 * Serialization interfaces and built-in serializers for RMCache.
 *
 * <p>
 * Key types:
 * <ul>
 * <li>{@link com.codeabbot.rmcache.serializer.KeySerializer} —
 * serialize/deserialize keys, compute hashCode</li>
 * <li>{@link com.codeabbot.rmcache.serializer.ValueSerializer} —
 * serialize/deserialize values</li>
 * <li>{@link com.codeabbot.rmcache.serializer.BuiltInSerializers} — pre-built
 * serializers for String and byte[]</li>
 * </ul>
 *
 * @author Rabindra Meher
 */
package com.codeabbot.rmcache.serializer;
