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
package com.codeabbot.rmcache.eviction;

import java.util.function.Supplier;

/**
 * Eviction listener - Called before eviction.
 */
@FunctionalInterface
public interface EvictionListener<K, V> {
    /**
     * Called before eviction.
     * 
     * @param key   The key being evicted
     * @param value Lazy value (loaded from off-heap only if accessed)
     * @param cause Reason for eviction
     */
    void onEviction(K key, Supplier<V> value, EvictionCause cause);
}
