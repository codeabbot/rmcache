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

import javax.cache.CacheManager;
import javax.cache.configuration.OptionalFeature;
import javax.cache.spi.CachingProvider;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.WeakHashMap;

/**
 * JSR-107 {@link CachingProvider} for RMCache. Registered via {@code META-INF/services} so
 * {@code Caching.getCachingProvider()} discovers it automatically.
 *
 * <p>RMCache always stores keys and values serialized off-heap, so it is a <b>store-by-value</b>
 * provider; {@link #isSupported(OptionalFeature) isSupported(STORE_BY_REFERENCE)} is {@code false}.
 */
public class RMCacheCachingProvider implements CachingProvider {

    private final Map<ClassLoader, Map<URI, RMCacheCacheManager>> cacheManagersByClassLoader = new WeakHashMap<>();

    public RMCacheCachingProvider() {
    }

    @Override
    public synchronized CacheManager getCacheManager(URI uri, ClassLoader classLoader, Properties properties) {
        URI managerUri = uri == null ? getDefaultURI() : uri;
        ClassLoader managerClassLoader = classLoader == null ? getDefaultClassLoader() : classLoader;
        Properties managerProperties = properties == null ? new Properties() : properties;

        Map<URI, RMCacheCacheManager> byUri =
                cacheManagersByClassLoader.computeIfAbsent(managerClassLoader, k -> new HashMap<>());
        RMCacheCacheManager manager = byUri.get(managerUri);
        if (manager == null || manager.isClosed()) {
            manager = new RMCacheCacheManager(this, managerUri, managerClassLoader, managerProperties);
            byUri.put(managerUri, manager);
        }
        return manager;
    }

    @Override
    public ClassLoader getDefaultClassLoader() {
        return getClass().getClassLoader();
    }

    @Override
    public URI getDefaultURI() {
        return URI.create("rmcache://default");
    }

    @Override
    public Properties getDefaultProperties() {
        return new Properties();
    }

    @Override
    public CacheManager getCacheManager(URI uri, ClassLoader classLoader) {
        return getCacheManager(uri, classLoader, getDefaultProperties());
    }

    @Override
    public CacheManager getCacheManager() {
        return getCacheManager(getDefaultURI(), getDefaultClassLoader(), getDefaultProperties());
    }

    @Override
    public synchronized void close() {
        for (Map<URI, RMCacheCacheManager> byUri : cacheManagersByClassLoader.values()) {
            for (RMCacheCacheManager manager : byUri.values()) {
                manager.close();
            }
        }
        cacheManagersByClassLoader.clear();
    }

    @Override
    public synchronized void close(ClassLoader classLoader) {
        ClassLoader cl = classLoader == null ? getDefaultClassLoader() : classLoader;
        Map<URI, RMCacheCacheManager> byUri = cacheManagersByClassLoader.remove(cl);
        if (byUri != null) {
            for (RMCacheCacheManager manager : byUri.values()) {
                manager.close();
            }
        }
    }

    @Override
    public synchronized void close(URI uri, ClassLoader classLoader) {
        ClassLoader cl = classLoader == null ? getDefaultClassLoader() : classLoader;
        Map<URI, RMCacheCacheManager> byUri = cacheManagersByClassLoader.get(cl);
        if (byUri != null) {
            RMCacheCacheManager manager = byUri.remove(uri == null ? getDefaultURI() : uri);
            if (manager != null) {
                manager.close();
            }
            if (byUri.isEmpty()) {
                cacheManagersByClassLoader.remove(cl);
            }
        }
    }

    @Override
    public boolean isSupported(OptionalFeature optionalFeature) {
        // The only OptionalFeature is STORE_BY_REFERENCE; RMCache is store-by-value only.
        return false;
    }

    /** Called by a {@link RMCacheCacheManager} when it closes, to drop it from the registry. */
    synchronized void releaseCacheManager(URI uri, ClassLoader classLoader) {
        Map<URI, RMCacheCacheManager> byUri = cacheManagersByClassLoader.get(classLoader);
        if (byUri != null) {
            byUri.remove(uri);
            if (byUri.isEmpty()) {
                cacheManagersByClassLoader.remove(classLoader);
            }
        }
    }
}
