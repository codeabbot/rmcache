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

import com.codeabbot.rmcache.CacheBuilder;
import com.codeabbot.rmcache.GhostCacheMode;
import com.codeabbot.rmcache.OffHeapCache;
import com.codeabbot.rmcache.serializer.BuiltInSerializers;

import javax.cache.Cache;
import javax.cache.CacheManager;
import javax.cache.Caching;
import javax.cache.spi.CachingProvider;

/**
 * Shared backend factory for the JCache comparison benchmarks. Each fork builds exactly <b>one</b>
 * backend — {@code "direct"} (raw {@link OffHeapCache}) or {@code "jcache"} (the JSR-107 wrapper) —
 * so even the 10M scale stays within a single off-heap cache's footprint (≈ the existing 10M
 * RMCache-only benchmarks), instead of two caches per JVM.
 *
 * <p>Both backends use byte-for-byte identical sizing, serializers, eviction and ghost settings;
 * the only difference is the {@code javax.cache.Cache} indirection + striped write lock. The call
 * site is monomorphic per fork, so the JIT inlines {@link Backend} and the abstraction is free.
 */
final class Backends {

    interface Backend extends AutoCloseable {
        byte[] get(String key);

        void put(String key, byte[] value);

        @Override
        void close();
    }

    private Backends() {
    }

    static Backend create(String kind, int entryCount, int valueSize) {
        long offHeapBytes = Math.max(8L * 1024 * 1024, (long) entryCount * 440L);
        byte[] seed = new byte[valueSize];

        if ("direct".equals(kind)) {
            OffHeapCache<String, byte[]> cache = new CacheBuilder<String, byte[]>()
                    .offHeapMemory(offHeapBytes)
                    .maxEntries(entryCount)
                    .keySerializer(BuiltInSerializers.STRING_KEY)
                    .valueSerializer(BuiltInSerializers.byteArray())
                    .ghostCacheMode(GhostCacheMode.DISABLED)
                    .build();
            for (int i = 0; i < entryCount; i++) {
                cache.put("key-" + i, seed);
            }
            return new Backend() {
                @Override public byte[] get(String key) { return cache.get(key); }
                @Override public void put(String key, byte[] value) { cache.put(key, value); }
                @Override public void close() { cache.close(); }
            };
        }

        if ("directNoEvict".equals(kind)) {
            // Clean write path: NoEviction removes all eviction bookkeeping so a profile shows
            // only the hash-table + slab + slot-allocator cost (used for the (C) investigation).
            OffHeapCache<String, byte[]> cache = new CacheBuilder<String, byte[]>()
                    .offHeapMemory(offHeapBytes)
                    .maxEntries(entryCount)
                    .keySerializer(BuiltInSerializers.STRING_KEY)
                    .valueSerializer(BuiltInSerializers.byteArray())
                    .eviction(new com.codeabbot.rmcache.eviction.NoEvictionPolicy())
                    .ghostCacheMode(GhostCacheMode.DISABLED)
                    .build();
            for (int i = 0; i < entryCount; i++) {
                cache.put("key-" + i, seed);
            }
            return new Backend() {
                @Override public byte[] get(String key) { return cache.get(key); }
                @Override public void put(String key, byte[] value) { cache.put(key, value); }
                @Override public void close() { cache.close(); }
            };
        }

        if ("jcache".equals(kind)) {
            CachingProvider provider = Caching.getCachingProvider();
            CacheManager cacheManager = provider.getCacheManager();
            Cache<String, byte[]> cache = cacheManager.createCache("bench",
                    new RMCacheConfiguration<String, byte[]>()
                            .setTypes(String.class, byte[].class)
                            .setOffHeapMemoryBytes(offHeapBytes)
                            .setMaxEntries(entryCount));
            for (int i = 0; i < entryCount; i++) {
                cache.put("key-" + i, seed);
            }
            return new Backend() {
                @Override public byte[] get(String key) { return cache.get(key); }
                @Override public void put(String key, byte[] value) { cache.put(key, value); }
                @Override public void close() { cacheManager.close(); provider.close(); }
            };
        }

        throw new IllegalArgumentException("unknown backend: " + kind);
    }
}
