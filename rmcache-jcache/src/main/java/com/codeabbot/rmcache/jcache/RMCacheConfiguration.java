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

import com.codeabbot.rmcache.GhostCacheMode;

import javax.cache.configuration.Factory;
import javax.cache.configuration.MutableConfiguration;
import javax.cache.expiry.ExpiryPolicy;

/**
 * RMCache-specific {@link javax.cache.configuration.Configuration} that adds the off-heap sizing
 * knobs JSR-107 has no vocabulary for. Use this in place of {@code MutableConfiguration} when you
 * want to control how much native memory the backing cache reserves:
 *
 * <pre>{@code
 * Cache<String, Order> orders = cacheManager.createCache("orders",
 *     new RMCacheConfiguration<String, Order>()
 *         .setTypes(String.class, Order.class)
 *         .setOffHeapMemoryBytes(4L << 30)   // 4 GB off-heap
 *         .setMaxEntries(20_000_000));
 * }</pre>
 *
 * <p>When a plain {@code MutableConfiguration} is passed to {@code createCache} instead, the
 * defaults here apply ({@value #DEFAULT_OFF_HEAP_BYTES} bytes off-heap, {@value #DEFAULT_MAX_ENTRIES}
 * max entries).
 */
public class RMCacheConfiguration<K, V> extends MutableConfiguration<K, V> {

    private static final long serialVersionUID = 1L;

    /**
     * Default off-heap reservation when unset (64 MB). Deliberately modest so a bare
     * {@code createCache} does not silently commit gigabytes — size up via
     * {@link #setOffHeapMemoryBytes(long)} for large caches. Must satisfy RMCache's
     * floor of {@code maxEntries × (avgKey + avgValue + 24)} bytes.
     */
    public static final long DEFAULT_OFF_HEAP_BYTES = 64L * 1024 * 1024;
    /** Default maximum entry count when unset. */
    public static final int DEFAULT_MAX_ENTRIES = 100_000;

    private long offHeapMemoryBytes = DEFAULT_OFF_HEAP_BYTES;
    private int maxEntries = DEFAULT_MAX_ENTRIES;
    private GhostCacheMode ghostCacheMode = GhostCacheMode.DISABLED;

    public RMCacheConfiguration() {
        super();
    }

    public long getOffHeapMemoryBytes() {
        return offHeapMemoryBytes;
    }

    public RMCacheConfiguration<K, V> setOffHeapMemoryBytes(long bytes) {
        if (bytes <= 0) {
            throw new IllegalArgumentException("offHeapMemoryBytes must be > 0");
        }
        this.offHeapMemoryBytes = bytes;
        return this;
    }

    public int getMaxEntries() {
        return maxEntries;
    }

    public RMCacheConfiguration<K, V> setMaxEntries(int maxEntries) {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be > 0");
        }
        this.maxEntries = maxEntries;
        return this;
    }

    public GhostCacheMode getGhostCacheMode() {
        return ghostCacheMode;
    }

    public RMCacheConfiguration<K, V> setGhostCacheMode(GhostCacheMode mode) {
        if (mode == null) {
            throw new IllegalArgumentException("ghostCacheMode must not be null");
        }
        this.ghostCacheMode = mode;
        return this;
    }

    // Covariant overrides so the inherited setters keep the RMCacheConfiguration type, letting
    // RMCache-specific setters chain after them (e.g. setTypes(...).setOffHeapMemoryBytes(...)).

    @Override
    public RMCacheConfiguration<K, V> setTypes(Class<K> keyType, Class<V> valueType) {
        super.setTypes(keyType, valueType);
        return this;
    }

    @Override
    public RMCacheConfiguration<K, V> setStoreByValue(boolean isStoreByValue) {
        super.setStoreByValue(isStoreByValue);
        return this;
    }

    @Override
    public RMCacheConfiguration<K, V> setExpiryPolicyFactory(Factory<? extends ExpiryPolicy> factory) {
        super.setExpiryPolicyFactory(factory);
        return this;
    }

    @Override
    public RMCacheConfiguration<K, V> setStatisticsEnabled(boolean enabled) {
        super.setStatisticsEnabled(enabled);
        return this;
    }

    @Override
    public RMCacheConfiguration<K, V> setManagementEnabled(boolean enabled) {
        super.setManagementEnabled(enabled);
        return this;
    }
}
