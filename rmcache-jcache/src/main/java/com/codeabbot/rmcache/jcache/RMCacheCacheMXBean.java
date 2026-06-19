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

import javax.cache.configuration.CompleteConfiguration;
import javax.cache.management.CacheMXBean;

/**
 * JSR-107 {@link CacheMXBean} exposing an {@link RMCacheCache}'s configuration over JMX.
 */
public class RMCacheCacheMXBean implements CacheMXBean {

    private final RMCacheCache<?, ?> cache;

    RMCacheCacheMXBean(RMCacheCache<?, ?> cache) {
        this.cache = cache;
    }

    private CompleteConfiguration<?, ?> config() {
        return cache.getConfiguration(CompleteConfiguration.class);
    }

    @Override
    public String getKeyType() {
        return config().getKeyType().getName();
    }

    @Override
    public String getValueType() {
        return config().getValueType().getName();
    }

    @Override
    public boolean isReadThrough() {
        return config().isReadThrough();
    }

    @Override
    public boolean isWriteThrough() {
        return config().isWriteThrough();
    }

    @Override
    public boolean isStoreByValue() {
        // RMCache always serializes off-heap.
        return true;
    }

    @Override
    public boolean isStatisticsEnabled() {
        return cache.isStatisticsEnabled();
    }

    @Override
    public boolean isManagementEnabled() {
        return cache.isManagementEnabled();
    }
}
