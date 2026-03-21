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
 * Eviction policies for RMCache.
 *
 * <p>
 * Provides pluggable eviction strategies:
 * <ul>
 * <li>{@link com.codeabbot.rmcache.eviction.LRUPolicy} — W-TinyLFU (3-segment
 * SLRU + frequency sketch)</li>
 * <li>{@link com.codeabbot.rmcache.eviction.TTLPolicy} — time-to-live
 * expiration via off-heap timing wheel</li>
 * <li>{@link com.codeabbot.rmcache.eviction.CompositePolicy} — combines
 * multiple policies (e.g., LRU + TTL)</li>
 * <li>{@link com.codeabbot.rmcache.eviction.NoEvictionPolicy} — disables
 * eviction entirely</li>
 * </ul>
 *
 * <p>
 * All eviction metadata (linked-list pointers, frequency counters, expiration
 * timestamps) is stored off-heap.
 *
 * @author Rabindra Meher
 */
package com.codeabbot.rmcache.eviction;
