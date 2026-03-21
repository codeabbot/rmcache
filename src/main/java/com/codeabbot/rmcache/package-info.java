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
 * RMCache — High-Performance, Billion-Scale Off-Heap Cache for Java 25+ (LTS).
 *
 * <p>
 * This is the primary public API package. Key entry points:
 * <ul>
 * <li>{@link com.codeabbot.rmcache.CacheBuilder} — fluent builder for
 * configuring and creating cache instances</li>
 * <li>{@link com.codeabbot.rmcache.OffHeapCache} — the main cache interface
 * (get, put, remove, getView)</li>
 * <li>{@link com.codeabbot.rmcache.CacheValueView} — zero-copy view into native
 * memory for a cached value</li>
 * </ul>
 *
 * <p>
 * All data (keys, values, index structures) is stored off-heap using the
 * Java Foreign Function &amp; Memory (FFM) API. The Java heap is used only for
 * control structures (locks, counters, thread-local buffers).
 *
 * @author Rabindra Meher
 */
package com.codeabbot.rmcache;
