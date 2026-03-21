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
 * Index structures for RMCache.
 *
 * <p>
 * Contains the hash table, entry pool, and ghost cache:
 * <ul>
 * <li>{@link com.codeabbot.rmcache.index.OffHeapHashTable} — Robin Hood hash
 * table with StampedLock optimistic reads</li>
 * <li>{@link com.codeabbot.rmcache.index.EntryPool} — partitioned slot
 * allocator storing key/value metadata off-heap</li>
 * <li>{@link com.codeabbot.rmcache.index.OffHeapGhostCache} — direct-mapped L1
 * shortcut for hot keys</li>
 * </ul>
 *
 * @author Rabindra Meher
 */
package com.codeabbot.rmcache.index;
