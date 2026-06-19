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

import com.codeabbot.rmcache.OffHeapCache;

import javax.cache.management.CacheStatisticsMXBean;

/**
 * JSR-107 {@link CacheStatisticsMXBean} backed live by RMCache's own
 * {@link OffHeapCache#getStats() core statistics}. Counters are reported relative to the last
 * {@link #clear()} via a baseline offset, so {@code clear()} behaves per spec even though the
 * underlying core counters are monotonic.
 *
 * <p>Average get/put/remove times are not measured in Phase 1 and report {@code 0}.
 */
public class RMCacheStatisticsMXBean implements CacheStatisticsMXBean {

    private final OffHeapCache<?, ?> core;
    private volatile long baseHits;
    private volatile long baseMisses;
    private volatile long basePuts;
    private volatile long baseRemoves;
    private volatile long baseEvictions;

    RMCacheStatisticsMXBean(OffHeapCache<?, ?> core) {
        this.core = core;
    }

    @Override
    public void clear() {
        OffHeapCache.CacheStats s = core.getStats();
        baseHits = s.hits();
        baseMisses = s.misses();
        basePuts = s.puts();
        baseRemoves = s.removes();
        baseEvictions = s.evictions();
    }

    @Override
    public long getCacheHits() {
        return core.getStats().hits() - baseHits;
    }

    @Override
    public long getCacheMisses() {
        return core.getStats().misses() - baseMisses;
    }

    @Override
    public long getCacheGets() {
        return getCacheHits() + getCacheMisses();
    }

    @Override
    public long getCachePuts() {
        return core.getStats().puts() - basePuts;
    }

    @Override
    public long getCacheRemovals() {
        return core.getStats().removes() - baseRemoves;
    }

    @Override
    public long getCacheEvictions() {
        return core.getStats().evictions() - baseEvictions;
    }

    @Override
    public float getCacheHitPercentage() {
        long gets = getCacheGets();
        return gets == 0 ? 0f : (float) getCacheHits() / gets * 100f;
    }

    @Override
    public float getCacheMissPercentage() {
        long gets = getCacheGets();
        return gets == 0 ? 0f : (float) getCacheMisses() / gets * 100f;
    }

    @Override
    public float getAverageGetTime() {
        return 0f;
    }

    @Override
    public float getAveragePutTime() {
        return 0f;
    }

    @Override
    public float getAverageRemoveTime() {
        return 0f;
    }
}
