package com.codeabbot.rmcache;

import com.codeabbot.rmcache.eviction.*;
import com.codeabbot.rmcache.index.EntryPool;
import com.codeabbot.rmcache.index.GhostCache;
import com.codeabbot.rmcache.index.OffHeapGhostCache;
import com.codeabbot.rmcache.index.OffHeapHashTable;
import com.codeabbot.rmcache.memory.SlabAllocator;
import com.codeabbot.rmcache.serializer.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;

/**
 * Builder for creating OffHeapCache instances.
 *
 * @author Rabindra Meher
 */
public class CacheBuilder<K, V> {

    private int maxEntries = 1_000_000;
    private int averageValueSize = 256;
    private int averageKeySize = 32;

    private Long memorySizeBytes = null;
    private Integer hashTableStripes = null;
    private Integer entryPoolPartitions = null;
    private Integer ghostCacheCapacity = null;
    private Boolean enablePrefetch = null;
    private GhostCacheMode ghostCacheMode = GhostCacheMode.HEAP;
    private StringEncoding stringKeyEncoding = StringEncoding.UTF8;

    private int slabSize = 64 * 1024;
    private KeySerializer<K> keySerializer = null;
    private ValueSerializer<V> valueSerializer = null;
    private EvictionPolicy evictionPolicy = null;
    private EvictionListener<K, V> evictionListener = null;
    private EvictionFilter<K> evictionFilter = null;
    private boolean zeroMemory = false;
    private MeterRegistry meterRegistry = null;
    private String cacheName = "rmcache";

    private boolean backgroundEviction = false;
    private long backgroundEvictionIntervalMs = 10;
    private double evictionHighWatermark = 0.95d;
    private double evictionLowWatermark = 0.90d;

    public CacheBuilder<K, V> maxEntries(int count) {
        if (count <= 0)
            throw new IllegalArgumentException("maxEntries must be > 0");
        this.maxEntries = count;
        return this;
    }

    public CacheBuilder<K, V> averageValueSize(int bytes) {
        if (bytes <= 0)
            throw new IllegalArgumentException("averageValueSize must be > 0");
        this.averageValueSize = bytes;
        return this;
    }

    public CacheBuilder<K, V> averageKeySize(int bytes) {
        if (bytes <= 0)
            throw new IllegalArgumentException("averageKeySize must be > 0");
        this.averageKeySize = bytes;
        return this;
    }

    public CacheBuilder<K, V> offHeapMemory(long bytes) {
        if (bytes <= 0)
            throw new IllegalArgumentException("offHeapMemory must be > 0");
        this.memorySizeBytes = bytes;
        return this;
    }

    public CacheBuilder<K, V> hashTableStripes(int count) {
        if (count <= 0 || (count & (count - 1)) != 0) {
            throw new IllegalArgumentException("hashTableStripes must be power of 2");
        }
        this.hashTableStripes = count;
        return this;
    }

    public CacheBuilder<K, V> entryPoolPartitions(int count) {
        if (count <= 0 || (count & (count - 1)) != 0) {
            throw new IllegalArgumentException("entryPoolPartitions must be power of 2");
        }
        this.entryPoolPartitions = count;
        return this;
    }

    public CacheBuilder<K, V> prefetch(boolean enabled) {
        this.enablePrefetch = enabled;
        return this;
    }

    public CacheBuilder<K, V> ghostCacheSize(int capacity) {
        if (capacity < 0)
            throw new IllegalArgumentException("ghostCacheSize must be >= 0");
        this.ghostCacheCapacity = capacity;
        return this;
    }

    public CacheBuilder<K, V> ghostCacheMode(GhostCacheMode mode) {
        if (mode == null)
            throw new IllegalArgumentException("ghostCacheMode must not be null");
        this.ghostCacheMode = mode;
        return this;
    }

    public CacheBuilder<K, V> slabSize(int bytes) {
        this.slabSize = bytes;
        return this;
    }

    public CacheBuilder<K, V> stringKeyEncoding(StringEncoding encoding) {
        if (encoding == null)
            throw new IllegalArgumentException("stringKeyEncoding must not be null");
        this.stringKeyEncoding = encoding;
        return this;
    }

    public CacheBuilder<K, V> keySerializer(KeySerializer<K> serializer) {
        this.keySerializer = serializer;
        return this;
    }

    public CacheBuilder<K, V> valueSerializer(ValueSerializer<V> serializer) {
        this.valueSerializer = serializer;
        return this;
    }

    public CacheBuilder<K, V> eviction(EvictionPolicy policy) {
        this.evictionPolicy = policy;
        return this;
    }

    public CacheBuilder<K, V> evictionListener(EvictionListener<K, V> listener) {
        this.evictionListener = listener;
        return this;
    }

    public CacheBuilder<K, V> evictionFilter(EvictionFilter<K> filter) {
        this.evictionFilter = filter;
        return this;
    }

    public CacheBuilder<K, V> zeroMemoryOnStartup() {
        this.zeroMemory = true;
        return this;
    }

    public CacheBuilder<K, V> withMeterRegistry(MeterRegistry registry) {
        this.meterRegistry = registry;
        return this;
    }

    public CacheBuilder<K, V> withCacheName(String name) {
        this.cacheName = name;
        return this;
    }

    public CacheBuilder<K, V> backgroundEviction(boolean enabled) {
        this.backgroundEviction = enabled;
        return this;
    }

    public CacheBuilder<K, V> backgroundEvictionInterval(Duration interval) {
        if (interval == null)
            throw new IllegalArgumentException("backgroundEvictionInterval must not be null");
        if (interval.toMillis() <= 0)
            throw new IllegalArgumentException("backgroundEvictionInterval must be > 0");
        this.backgroundEvictionIntervalMs = interval.toMillis();
        return this;
    }

    public CacheBuilder<K, V> evictionMemoryWatermarks(double high, double low) {
        if (high <= 0 || high > 1.0 || low < 0 || low >= 1.0) {
            throw new IllegalArgumentException("watermarks must be in (0,1]");
        }
        if (low >= high) {
            throw new IllegalArgumentException("low watermark must be < high watermark");
        }
        this.evictionHighWatermark = high;
        this.evictionLowWatermark = low;
        return this;
    }

    @SuppressWarnings("unchecked")
    public OffHeapCache<K, V> build() {
        int entryOverhead = 40;
        int bytesPerEntry = averageKeySize + averageValueSize + entryOverhead;
        long calculatedMemory = (long) ((long) maxEntries * bytesPerEntry * 1.3);
        long finalMemory = (memorySizeBytes != null) ? memorySizeBytes : calculatedMemory;

        long minMemory = (long) maxEntries * (averageKeySize + averageValueSize + 24);
        if (finalMemory < minMemory) {
            throw new IllegalArgumentException(
                    "offHeapMemory (" + finalMemory + ") is too small for " + maxEntries + " entries.");
        }

        int stripes = (hashTableStripes != null) ? hashTableStripes : autoStripes(maxEntries);
        int partitions = (entryPoolPartitions != null) ? entryPoolPartitions : autoPartitions(maxEntries);
        int ghostSize = (ghostCacheCapacity != null) ? ghostCacheCapacity : autoGhostSize(maxEntries);
        boolean prefetch = (enablePrefetch != null) ? enablePrefetch : false;

        KeySerializer<K> kSer = (keySerializer != null) ? keySerializer
                : (KeySerializer<K>) (stringKeyEncoding == StringEncoding.LATIN1
                        ? BuiltInSerializers.STRING_KEY_LATIN1
                        : BuiltInSerializers.STRING_KEY_UTF8);
        ValueSerializer<V> vSer = (valueSerializer != null) ? valueSerializer
                : (ValueSerializer<V>) BuiltInSerializers.STRING_VALUE;

        SlabAllocator allocator = new SlabAllocator(finalMemory, slabSize);
        EntryPool entryPool = new EntryPool(allocator, maxEntries, partitions);
        OffHeapHashTable hashTable = new OffHeapHashTable(entryPool, finalMemory, stripes, prefetch);
        EvictionPolicy policy = (evictionPolicy != null) ? evictionPolicy
                : new LRUPolicy(maxEntries, entryPool.slotCapacity(), 0.01f, 0.80f);
        policy.setEntryPool(entryPool);

        GhostCache<K, V> ghostCache = null;
        OffHeapGhostCache offHeapGhostCache = null;
        if (ghostSize > 0 && ghostCacheMode != GhostCacheMode.DISABLED) {
            if (ghostCacheMode == GhostCacheMode.HEAP) {
                ghostCache = new GhostCache<>(ghostSize);
            } else {
                offHeapGhostCache = new OffHeapGhostCache(ghostSize);
            }
        }

        OffHeapCacheImpl<K, V> cache = new OffHeapCacheImpl<>(
                kSer, vSer, allocator, entryPool, hashTable, policy,
                evictionListener, evictionFilter, ghostCache, offHeapGhostCache, meterRegistry, cacheName,
                backgroundEviction, backgroundEvictionIntervalMs, evictionHighWatermark, evictionLowWatermark);
        return cache;
    }

    private int autoStripes(int entries) {
        if (entries >= 10_000_000)
            return 8192; // Reduced from 65536 to save resources
        if (entries >= 1_000_000)
            return 1024;
        return 64;
    }

    private int autoPartitions(int entries) {
        if (entries <= 100_000)
            return 64;
        if (entries <= 10_000_000)
            return 128;
        return 256;
    }

    private int autoGhostSize(int entries) {
        int suggested = entries / 1000;
        return Math.max(64, Math.min(suggested, 32768));
    }
}
