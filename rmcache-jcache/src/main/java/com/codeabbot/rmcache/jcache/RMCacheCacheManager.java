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

import javax.cache.Cache;
import javax.cache.CacheException;
import javax.cache.CacheManager;
import javax.cache.configuration.Configuration;
import javax.cache.spi.CachingProvider;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * JSR-107 {@link CacheManager} for RMCache. Owns a set of named {@link RMCacheCache} instances,
 * each backed by its own off-heap {@link com.codeabbot.rmcache.OffHeapCache}.
 */
public class RMCacheCacheManager implements CacheManager {

    private final RMCacheCachingProvider provider;
    private final URI uri;
    private final ClassLoader classLoader;
    private final Properties properties;
    private final ConcurrentMap<String, RMCacheCache<?, ?>> caches = new ConcurrentHashMap<>();
    private volatile boolean closed = false;

    RMCacheCacheManager(RMCacheCachingProvider provider, URI uri, ClassLoader classLoader, Properties properties) {
        this.provider = provider;
        this.uri = uri;
        this.classLoader = classLoader;
        this.properties = properties;
    }

    @Override
    public CachingProvider getCachingProvider() {
        return provider;
    }

    @Override
    public URI getURI() {
        return uri;
    }

    @Override
    public ClassLoader getClassLoader() {
        return classLoader;
    }

    @Override
    public Properties getProperties() {
        return properties;
    }

    @Override
    public <K, V, C extends Configuration<K, V>> Cache<K, V> createCache(String cacheName, C configuration) {
        ensureOpen();
        Objects.requireNonNull(cacheName, "cacheName");
        Objects.requireNonNull(configuration, "configuration");
        synchronized (caches) {
            if (caches.containsKey(cacheName)) {
                throw new CacheException("A cache named '" + cacheName + "' already exists.");
            }
            RMCacheCache<K, V> cache = new RMCacheCache<>(this, cacheName, configuration);
            caches.put(cacheName, cache);
            return cache;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <K, V> Cache<K, V> getCache(String cacheName, Class<K> keyType, Class<V> valueType) {
        ensureOpen();
        Objects.requireNonNull(cacheName, "cacheName");
        Objects.requireNonNull(keyType, "keyType");
        Objects.requireNonNull(valueType, "valueType");
        RMCacheCache<?, ?> cache = caches.get(cacheName);
        if (cache == null) {
            return null;
        }
        Configuration<?, ?> cfg = cache.getConfiguration(Configuration.class);
        if (!cfg.getKeyType().equals(keyType)) {
            throw new ClassCastException("Cache '" + cacheName + "' key type is " + cfg.getKeyType().getName()
                    + ", not " + keyType.getName());
        }
        if (!cfg.getValueType().equals(valueType)) {
            throw new ClassCastException("Cache '" + cacheName + "' value type is " + cfg.getValueType().getName()
                    + ", not " + valueType.getName());
        }
        return (Cache<K, V>) cache;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <K, V> Cache<K, V> getCache(String cacheName) {
        ensureOpen();
        Objects.requireNonNull(cacheName, "cacheName");
        RMCacheCache<?, ?> cache = caches.get(cacheName);
        if (cache == null) {
            return null;
        }
        Configuration<?, ?> cfg = cache.getConfiguration(Configuration.class);
        if (cfg.getKeyType() != Object.class || cfg.getValueType() != Object.class) {
            throw new IllegalArgumentException("Cache '" + cacheName + "' was created with runtime types <"
                    + cfg.getKeyType().getName() + ", " + cfg.getValueType().getName()
                    + ">; use getCache(name, keyType, valueType) instead.");
        }
        return (Cache<K, V>) cache;
    }

    @Override
    public Iterable<String> getCacheNames() {
        ensureOpen();
        return List.copyOf(new ArrayList<>(caches.keySet()));
    }

    @Override
    public void destroyCache(String cacheName) {
        ensureOpen();
        Objects.requireNonNull(cacheName, "cacheName");
        RMCacheCache<?, ?> cache = caches.remove(cacheName);
        if (cache != null) {
            cache.close();
        }
    }

    @Override
    public void enableManagement(String cacheName, boolean enabled) {
        ensureOpen();
        RMCacheCache<?, ?> cache = caches.get(cacheName);
        if (cache != null) {
            cache.setManagementEnabled(enabled);
        }
    }

    @Override
    public void enableStatistics(String cacheName, boolean enabled) {
        ensureOpen();
        RMCacheCache<?, ?> cache = caches.get(cacheName);
        if (cache != null) {
            cache.setStatisticsEnabled(enabled);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (RMCacheCache<?, ?> cache : new ArrayList<>(caches.values())) {
            try {
                cache.close();
            } catch (RuntimeException ignored) {
                // closing one cache must not prevent the others from closing
            }
        }
        caches.clear();
        provider.releaseCacheManager(uri, classLoader);
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public <T> T unwrap(Class<T> clazz) {
        if (clazz.isAssignableFrom(getClass())) {
            return clazz.cast(this);
        }
        throw new IllegalArgumentException("Cannot unwrap RMCacheCacheManager to " + clazz);
    }

    /** Called by {@link RMCacheCache#close()} so a self-closing cache drops out of the registry. */
    void removeCache(String cacheName) {
        caches.remove(cacheName);
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("CacheManager " + uri + " is closed");
        }
    }
}
