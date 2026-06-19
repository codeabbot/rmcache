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

import javax.cache.Cache;
import javax.cache.CacheManager;
import javax.cache.configuration.CacheEntryListenerConfiguration;
import javax.cache.configuration.CompleteConfiguration;
import javax.cache.configuration.Configuration;
import javax.cache.configuration.MutableConfiguration;
import javax.cache.expiry.ExpiryPolicy;
import javax.cache.integration.CompletionListener;
import javax.cache.processor.EntryProcessor;
import javax.cache.processor.EntryProcessorException;
import javax.cache.processor.EntryProcessorResult;
import javax.cache.processor.MutableEntry;
import javax.management.ObjectName;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * JSR-107 {@link Cache} over an RMCache {@link OffHeapCache} (Phase 1 — pragmatic-correct adapter).
 *
 * <p><b>Store-by-value</b> is inherent: RMCache serializes keys and values into off-heap memory on
 * write and deserializes fresh objects on read, so the cache is naturally isolated from later
 * caller mutations. Store-by-reference is not supported.
 *
 * <p><b>Atomicity.</b> All mutating operations and the conditional read-modify-write operations
 * ({@code getAndPut}, {@code putIfAbsent}, {@code replace}, {@code remove(k,v)}, {@code getAndRemove},
 * {@code getAndReplace}, {@code invoke}) execute under a per-key striped lock, so {@code invoke}'s
 * entry processor runs atomically with respect to other writers of the same key. Plain {@code get}
 * is lock-free.
 *
 * <p><b>Expiry.</b> {@code EternalExpiryPolicy} (default) and {@code ModifiedExpiryPolicy} map
 * exactly to RMCache TTL. {@code CreatedExpiryPolicy}/{@code AccessedExpiryPolicy} apply the
 * creation TTL on insert; their "no change on update" and access-extension semantics are not
 * preserved in Phase 1.
 *
 * <p><b>Phase-1 deferrals</b> (throw {@link UnsupportedOperationException}, targeted for Phase 2):
 * {@link #iterator()}, cache entry listeners, and read-through/write-through {@code CacheLoader}/
 * {@code CacheWriter}. {@link #removeAll()} maps to {@link #clear()} (no per-entry listener events).
 */
public class RMCacheCache<K, V> implements Cache<K, V> {

    private static final int STRIPE_COUNT = 256;
    private static final int STRIPE_MASK = STRIPE_COUNT - 1;

    private final RMCacheCacheManager manager;
    private final String name;
    private final OffHeapCache<K, V> core;
    private final MutableConfiguration<K, V> configuration;
    private final Class<K> keyType;
    private final Class<V> valueType;
    private final ExpiryPolicy expiryPolicy;
    private final ReentrantLock[] stripes;
    private final RMCacheStatisticsMXBean statisticsMXBean;
    private final RMCacheCacheMXBean cacheMXBean;

    private volatile boolean statisticsEnabled;
    private volatile boolean managementEnabled;
    private volatile boolean closed = false;

    @SuppressWarnings("unchecked")
    RMCacheCache(RMCacheCacheManager manager, String name, Configuration<K, V> cfg) {
        this.manager = manager;
        this.name = name;

        // Normalize to a CompleteConfiguration we can query for types / expiry / flags.
        MutableConfiguration<K, V> normalized;
        if (cfg instanceof CompleteConfiguration) {
            normalized = new MutableConfiguration<>((CompleteConfiguration<K, V>) cfg);
        } else {
            normalized = new MutableConfiguration<>();
            normalized.setTypes(cfg.getKeyType(), cfg.getValueType());
            normalized.setStoreByValue(cfg.isStoreByValue());
        }
        this.configuration = normalized;
        this.keyType = normalized.getKeyType();
        this.valueType = normalized.getValueType();
        this.expiryPolicy = normalized.getExpiryPolicyFactory().create();

        long offHeapBytes = RMCacheConfiguration.DEFAULT_OFF_HEAP_BYTES;
        int maxEntries = RMCacheConfiguration.DEFAULT_MAX_ENTRIES;
        GhostCacheMode ghostMode = GhostCacheMode.DISABLED;
        if (cfg instanceof RMCacheConfiguration) {
            RMCacheConfiguration<K, V> rc = (RMCacheConfiguration<K, V>) cfg;
            offHeapBytes = rc.getOffHeapMemoryBytes();
            maxEntries = rc.getMaxEntries();
            ghostMode = rc.getGhostCacheMode();
        }

        this.core = new CacheBuilder<K, V>()
                .offHeapMemory(offHeapBytes)
                .maxEntries(maxEntries)
                .keySerializer(Serializers.keySerializer(keyType))
                .valueSerializer(Serializers.valueSerializer(valueType))
                .ghostCacheMode(ghostMode)
                .withCacheName(name)
                .build();

        this.stripes = new ReentrantLock[STRIPE_COUNT];
        for (int i = 0; i < STRIPE_COUNT; i++) {
            stripes[i] = new ReentrantLock();
        }

        this.statisticsMXBean = new RMCacheStatisticsMXBean(core);
        this.cacheMXBean = new RMCacheCacheMXBean(this);
        if (normalized.isStatisticsEnabled()) {
            setStatisticsEnabled(true);
        }
        if (normalized.isManagementEnabled()) {
            setManagementEnabled(true);
        }
    }

    // ──────────────────────────── reads ────────────────────────────

    @Override
    public V get(K key) {
        ensureOpen();
        requireKey(key);
        return core.get(key);
    }

    @Override
    public Map<K, V> getAll(Set<? extends K> keys) {
        ensureOpen();
        Objects.requireNonNull(keys, "keys");
        List<K> keyList = new ArrayList<>(keys.size());
        for (K key : keys) {
            requireKey(key);
            keyList.add(key);
        }
        return new HashMap<>(core.getAll(keyList));
    }

    @Override
    public boolean containsKey(K key) {
        ensureOpen();
        requireKey(key);
        return core.contains(key);
    }

    // ──────────────────────────── writes ────────────────────────────

    @Override
    public void put(K key, V value) {
        ensureOpen();
        requireKey(key);
        requireValue(value);
        ReentrantLock lock = stripeFor(key);
        lock.lock();
        try {
            putWithExpiry(key, value);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public V getAndPut(K key, V value) {
        ensureOpen();
        requireKey(key);
        requireValue(value);
        ReentrantLock lock = stripeFor(key);
        lock.lock();
        try {
            V previous = core.get(key);
            putWithExpiry(key, value);
            return previous;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> map) {
        ensureOpen();
        Objects.requireNonNull(map, "map");
        for (Map.Entry<? extends K, ? extends V> e : map.entrySet()) {
            requireKey(e.getKey());
            requireValue(e.getValue());
        }
        for (Map.Entry<? extends K, ? extends V> e : map.entrySet()) {
            put(e.getKey(), e.getValue());
        }
    }

    @Override
    public boolean putIfAbsent(K key, V value) {
        ensureOpen();
        requireKey(key);
        requireValue(value);
        ReentrantLock lock = stripeFor(key);
        lock.lock();
        try {
            if (core.contains(key)) {
                return false;
            }
            putWithExpiry(key, value);
            return true;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean remove(K key) {
        ensureOpen();
        requireKey(key);
        ReentrantLock lock = stripeFor(key);
        lock.lock();
        try {
            return core.remove(key);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean remove(K key, V oldValue) {
        ensureOpen();
        requireKey(key);
        requireValue(oldValue);
        ReentrantLock lock = stripeFor(key);
        lock.lock();
        try {
            V current = core.get(key);
            if (current != null && current.equals(oldValue)) {
                core.remove(key);
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public V getAndRemove(K key) {
        ensureOpen();
        requireKey(key);
        ReentrantLock lock = stripeFor(key);
        lock.lock();
        try {
            V previous = core.get(key);
            if (previous != null) {
                core.remove(key);
            }
            return previous;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean replace(K key, V oldValue, V newValue) {
        ensureOpen();
        requireKey(key);
        requireValue(oldValue);
        requireValue(newValue);
        ReentrantLock lock = stripeFor(key);
        lock.lock();
        try {
            V current = core.get(key);
            if (current != null && current.equals(oldValue)) {
                putWithExpiry(key, newValue);
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean replace(K key, V value) {
        ensureOpen();
        requireKey(key);
        requireValue(value);
        ReentrantLock lock = stripeFor(key);
        lock.lock();
        try {
            if (core.contains(key)) {
                putWithExpiry(key, value);
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public V getAndReplace(K key, V value) {
        ensureOpen();
        requireKey(key);
        requireValue(value);
        ReentrantLock lock = stripeFor(key);
        lock.lock();
        try {
            V previous = core.get(key);
            if (previous != null) {
                putWithExpiry(key, value);
            }
            return previous;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void removeAll(Set<? extends K> keys) {
        ensureOpen();
        Objects.requireNonNull(keys, "keys");
        for (K key : keys) {
            requireKey(key);
        }
        for (K key : keys) {
            remove(key);
        }
    }

    @Override
    public void removeAll() {
        ensureOpen();
        // Phase 1: no entry listeners, so this is equivalent to clear().
        core.clear();
    }

    @Override
    public void clear() {
        ensureOpen();
        core.clear();
    }

    // ──────────────────────────── entry processors ────────────────────────────

    @Override
    public <T> T invoke(K key, EntryProcessor<K, V, T> entryProcessor, Object... arguments) {
        ensureOpen();
        requireKey(key);
        Objects.requireNonNull(entryProcessor, "entryProcessor");
        ReentrantLock lock = stripeFor(key);
        lock.lock();
        try {
            MutableEntryImpl<K, V> entry = new MutableEntryImpl<>(key, core.get(key));
            T result;
            try {
                result = entryProcessor.process(entry, arguments);
            } catch (EntryProcessorException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new EntryProcessorException(e);
            }
            if (entry.removed) {
                core.remove(key);
            } else if (entry.updated) {
                putWithExpiry(key, entry.newValue);
            }
            return result;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public <T> Map<K, EntryProcessorResult<T>> invokeAll(Set<? extends K> keys,
                                                         EntryProcessor<K, V, T> entryProcessor,
                                                         Object... arguments) {
        ensureOpen();
        Objects.requireNonNull(keys, "keys");
        Objects.requireNonNull(entryProcessor, "entryProcessor");
        Map<K, EntryProcessorResult<T>> results = new HashMap<>();
        for (K key : keys) {
            requireKey(key);
            try {
                T result = invoke(key, entryProcessor, arguments);
                if (result != null) {
                    final T captured = result;
                    results.put(key, () -> captured);
                }
            } catch (EntryProcessorException e) {
                results.put(key, () -> {
                    throw e;
                });
            }
        }
        return results;
    }

    // ──────────────────────────── lifecycle / config ────────────────────────────

    @Override
    public <C extends Configuration<K, V>> C getConfiguration(Class<C> clazz) {
        if (clazz.isInstance(configuration)) {
            return clazz.cast(configuration);
        }
        throw new IllegalArgumentException("Unsupported configuration type: " + clazz);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public CacheManager getCacheManager() {
        return manager;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            setStatisticsEnabled(false);
            setManagementEnabled(false);
        } finally {
            manager.removeCache(name);
            core.close();
        }
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T unwrap(Class<T> clazz) {
        if (clazz.isAssignableFrom(getClass())) {
            return (T) this;
        }
        if (clazz.isAssignableFrom(core.getClass())) {
            return (T) core;
        }
        throw new IllegalArgumentException("Cannot unwrap RMCacheCache to " + clazz);
    }

    // ──────────────────────────── Phase-2 deferrals ────────────────────────────

    @Override
    public void loadAll(Set<? extends K> keys, boolean replaceExistingValues, CompletionListener completionListener) {
        ensureOpen();
        // Phase 1: no CacheLoader / read-through. With no loader, JCache semantics are a no-op.
        if (completionListener != null) {
            completionListener.onCompletion();
        }
    }

    @Override
    public void registerCacheEntryListener(CacheEntryListenerConfiguration<K, V> cacheEntryListenerConfiguration) {
        throw new UnsupportedOperationException(
                "RMCache JCache (Phase 1) does not yet support cache entry listeners");
    }

    @Override
    public void deregisterCacheEntryListener(CacheEntryListenerConfiguration<K, V> cacheEntryListenerConfiguration) {
        throw new UnsupportedOperationException(
                "RMCache JCache (Phase 1) does not yet support cache entry listeners");
    }

    @Override
    public Iterator<Cache.Entry<K, V>> iterator() {
        ensureOpen();
        throw new UnsupportedOperationException(
                "RMCache JCache (Phase 1) does not support iteration; the off-heap core exposes no entry iterator");
    }

    // ──────────────────────────── statistics / management ────────────────────────────

    void setStatisticsEnabled(boolean enabled) {
        ObjectName objectName = JmxRegistration.objectName(
                manager.getURI().toString(), name, JmxRegistration.Type.STATISTICS);
        if (enabled) {
            JmxRegistration.register(objectName, statisticsMXBean);
        } else {
            JmxRegistration.unregister(objectName);
        }
        this.statisticsEnabled = enabled;
        this.configuration.setStatisticsEnabled(enabled);
    }

    void setManagementEnabled(boolean enabled) {
        ObjectName objectName = JmxRegistration.objectName(
                manager.getURI().toString(), name, JmxRegistration.Type.CONFIGURATION);
        if (enabled) {
            JmxRegistration.register(objectName, cacheMXBean);
        } else {
            JmxRegistration.unregister(objectName);
        }
        this.managementEnabled = enabled;
        this.configuration.setManagementEnabled(enabled);
    }

    boolean isStatisticsEnabled() {
        return statisticsEnabled;
    }

    boolean isManagementEnabled() {
        return managementEnabled;
    }

    // ──────────────────────────── helpers ────────────────────────────

    private void putWithExpiry(K key, V value) {
        Duration creation = toDuration(expiryPolicy.getExpiryForCreation());
        Duration update = toDuration(expiryPolicy.getExpiryForUpdate());
        if (Objects.equals(creation, update)) {
            applyPut(key, value, creation);
        } else {
            applyPut(key, value, core.contains(key) ? update : creation);
        }
    }

    private void applyPut(K key, V value, Duration ttl) {
        if (ttl == null) {
            core.put(key, value);
        } else if (ttl.isZero() || ttl.isNegative()) {
            core.remove(key); // zero/expired duration ⇒ entry must not be retained
        } else {
            core.put(key, value, ttl);
        }
    }

    /** Convert a JCache expiry duration to an RMCache TTL ({@code null} ⇒ eternal / no TTL). */
    private static Duration toDuration(javax.cache.expiry.Duration d) {
        if (d == null || d.isEternal()) {
            return null;
        }
        if (d.isZero()) {
            return Duration.ZERO;
        }
        return Duration.ofMillis(d.getTimeUnit().toMillis(d.getDurationAmount()));
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Cache '" + name + "' is closed");
        }
    }

    private static void requireKey(Object key) {
        if (key == null) {
            throw new NullPointerException("key must not be null");
        }
    }

    private static void requireValue(Object value) {
        if (value == null) {
            throw new NullPointerException("value must not be null");
        }
    }

    private ReentrantLock stripeFor(Object key) {
        int h = key.hashCode();
        h ^= (h >>> 16);
        return stripes[h & STRIPE_MASK];
    }

    /** JCache {@link MutableEntry} used by {@link #invoke}; mutations are applied under the stripe lock. */
    static final class MutableEntryImpl<K, V> implements MutableEntry<K, V> {
        private final K key;
        private final V initialValue;
        private final boolean existedInitially;
        boolean updated;
        boolean removed;
        V newValue;

        MutableEntryImpl(K key, V initialValue) {
            this.key = key;
            this.initialValue = initialValue;
            this.existedInitially = initialValue != null;
        }

        @Override
        public K getKey() {
            return key;
        }

        @Override
        public V getValue() {
            if (removed) {
                return null;
            }
            return updated ? newValue : initialValue;
        }

        @Override
        public boolean exists() {
            if (removed) {
                return false;
            }
            return updated || existedInitially;
        }

        @Override
        public void remove() {
            this.removed = true;
            this.updated = false;
            this.newValue = null;
        }

        @Override
        public void setValue(V value) {
            if (value == null) {
                throw new NullPointerException("value must not be null");
            }
            this.updated = true;
            this.removed = false;
            this.newValue = value;
        }

        @Override
        public <T> T unwrap(Class<T> clazz) {
            throw new IllegalArgumentException("Cannot unwrap MutableEntry to " + clazz);
        }
    }
}
