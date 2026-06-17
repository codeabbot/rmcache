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
package com.codeabbot.rmcache;

import com.codeabbot.rmcache.eviction.*;
import com.codeabbot.rmcache.index.EntryPool;
import com.codeabbot.rmcache.index.GhostCache;
import com.codeabbot.rmcache.index.OffHeapGhostCache;
import com.codeabbot.rmcache.index.OffHeapHashTable;
import com.codeabbot.rmcache.memory.SlabAllocator;
import com.codeabbot.rmcache.serializer.*;
import com.codeabbot.rmcache.util.MemoryEstimator;

import java.time.Duration;
import java.util.concurrent.Executor;

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
    private GhostCacheMode ghostCacheMode = GhostCacheMode.AUTO;
    private StringEncoding stringKeyEncoding = StringEncoding.UTF8;
    private Integer hashTableInitialCapacity = null;
    private Double hashTableLoadFactor = null;
    private Long indexMemoryBudgetBytes = null;
    private Double indexMemoryBudgetPercent = null;
    private boolean zeroHeapProfile = false;

    private int slabSize = 64 * 1024;
    private KeySerializer<K> keySerializer = null;
    private ValueSerializer<V> valueSerializer = null;
    private EvictionPolicy evictionPolicy = null;
    private EvictionListener<K, V> evictionListener = null;
    private EvictionFilter<K> evictionFilter = null;
    private boolean zeroMemory = false;

    private Executor asyncExecutor = null;

    private String cacheName = "rmcache";

    private MetricsRecorder metricsRecorder = MetricsRecorder.NOOP;

    private boolean backgroundEviction = true;
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

    public CacheBuilder<K, V> indexMemoryBudgetBytes(long bytes) {
        if (bytes <= 0)
            throw new IllegalArgumentException("indexMemoryBudgetBytes must be > 0");
        this.indexMemoryBudgetBytes = bytes;
        return this;
    }

    public CacheBuilder<K, V> indexMemoryBudgetPercent(double percent) {
        if (percent <= 0.0 || percent > 1.0)
            throw new IllegalArgumentException("indexMemoryBudgetPercent must be in (0,1]");
        this.indexMemoryBudgetPercent = percent;
        return this;
    }

    /**
     * Configure the cache for minimal Java heap impact.
     *
     * <p>This preset makes two changes:
     * <ol>
     *   <li>Forces {@link GhostCacheMode#OFF_HEAP} — the ghost cache L1 is stored in native
     *       memory instead of a Java {@code Entry[]} array, eliminating heap pressure from
     *       that structure.</li>
     *   <li>Enables background eviction — eviction runs on a dedicated daemon thread,
     *       keeping the hot path free of synchronous eviction work.</li>
     * </ol>
     *
     * <h4>What this eliminates from the heap</h4>
     * <ul>
     *   <li>Ghost cache {@code Entry[]} objects (~40 bytes/entry × ghostCacheSize)</li>
     *   <li>Synchronous eviction latency spikes on the calling thread</li>
     * </ul>
     *
     * <h4>What this does NOT eliminate from the heap</h4>
     * <ul>
     *   <li><b>Key serialization byte[]</b> — created transiently during {@code put}/{@code get}
     *       to serialize the key. Released promptly (no pooling, no long-term retention).</li>
     *   <li><b>Value copy byte[]</b> — returned to the caller by {@code get()}. Required by
     *       the JVM type system; use {@link OffHeapCache#getZeroCopy} or
     *       {@link OffHeapCache#getView} to avoid this allocation.</li>
     *   <li><b>Control-plane objects</b> — locks, counters, executors (~1.5 MB fixed overhead).
     *       These are small and static; they do not grow with entry count.</li>
     *   <li><b>Per-thread key buffers</b> — {@code ThreadLocalKeyBuffer} (4–256 KB per thread)
     *       for Latin-1 key encoding. Call {@link OffHeapCache#cleanupThreadLocals()} on
     *       thread retirement to reclaim these.</li>
     * </ul>
     *
     * <p><b>Steady-state heap footprint</b> (after JIT warmup, no pending GC): approximately
     * {@code ~1.5 MB (fixed) + ~260 KB × activeThreads}. All key/value data, LRU structures,
     * hash table arrays, and frequency sketch live entirely in native memory.
     */
    public CacheBuilder<K, V> zeroHeapProfile() {
        this.zeroHeapProfile = true;
        this.ghostCacheMode = GhostCacheMode.AUTO;
        this.backgroundEviction = true;
        return this;
    }

    public CacheBuilder<K, V> hashTableInitialCapacity(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("hashTableInitialCapacity must be > 0");
        }
        this.hashTableInitialCapacity = capacity;
        return this;
    }

    public CacheBuilder<K, V> hashTableLoadFactor(double factor) {
        if (factor <= 0.0 || factor >= 1.0) {
            throw new IllegalArgumentException("hashTableLoadFactor must be between 0 and 1");
        }
        this.hashTableLoadFactor = factor;
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
        if (bytes < 4096)
            throw new IllegalArgumentException("slabSize must be >= 4096, got " + bytes);
        if ((bytes & (bytes - 1)) != 0)
            throw new IllegalArgumentException("slabSize must be power of 2, got " + bytes);
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

    /**
     * Typed shortcut: declare that this cache stores {@code String} values and use
     * the built-in UTF-8 String serializer. Equivalent to
     * {@code .valueSerializer(BuiltInSerializers.string())} but keeps the
     * {@code <K, V>} type parameter honest in the call site.
     */
    @SuppressWarnings("unchecked")
    public CacheBuilder<K, V> forStringValues() {
        this.valueSerializer = (ValueSerializer<V>) BuiltInSerializers.STRING_VALUE;
        return this;
    }

    /**
     * Typed shortcut: declare that this cache stores {@code byte[]} values and use
     * the built-in byte-array serializer. Equivalent to
     * {@code .valueSerializer(BuiltInSerializers.byteArray())}.
     */
    @SuppressWarnings("unchecked")
    public CacheBuilder<K, V> forByteArrayValues() {
        this.valueSerializer = (ValueSerializer<V>) BuiltInSerializers.byteArray();
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

    /**
     * Plug in a {@link MetricsRecorder} to count puts/removes inside the cache.
     * Defaults to {@link MetricsRecorder#NOOP} (zero cost — the hooks inline away).
     * See the {@code rmcache-metrics} module for a ready-made counting recorder.
     */
    public CacheBuilder<K, V> metricsRecorder(MetricsRecorder recorder) {
        this.metricsRecorder = (recorder == null) ? MetricsRecorder.NOOP : recorder;
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

    public CacheBuilder<K, V> withCacheName(String name) {
        this.cacheName = name;
        return this;
    }

    /**
     * Set a custom Executor for {@code putAsync}/{@code getAsync} operations.
     * If not set, the common ForkJoinPool is used (unbounded submission queue).
     *
     * @param executor the executor to use for async cache operations
     */
    public CacheBuilder<K, V> asyncExecutor(Executor executor) {
        this.asyncExecutor = executor;
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

        GhostCacheMode effectiveGhostMode = (ghostCacheMode != null) ? ghostCacheMode : GhostCacheMode.AUTO;
        if (effectiveGhostMode == GhostCacheMode.AUTO) {
            effectiveGhostMode = zeroHeapProfile ? GhostCacheMode.OFF_HEAP : GhostCacheMode.HEAP;
        }

        double loadFactor = (hashTableLoadFactor != null) ? hashTableLoadFactor : MemoryEstimator.DEFAULT_LOAD_FACTOR;
        int initialCapacity;

        Long indexBudget = indexMemoryBudgetBytes;
        if (indexBudget == null && indexMemoryBudgetPercent != null) {
            indexBudget = (long) (finalMemory * indexMemoryBudgetPercent);
        }
        if (indexBudget != null) {
            MemoryEstimator.IndexSizing sizing = MemoryEstimator.deriveIndexSizing(
                    maxEntries, stripes, partitions, indexBudget, ghostSize, effectiveGhostMode, loadFactor);
            loadFactor = sizing.loadFactor();
            initialCapacity = sizing.hashTableCapacityPerStripe();
        } else {
            initialCapacity = (hashTableInitialCapacity != null)
                    ? hashTableInitialCapacity
                    : autoHashTableInitialCapacity(maxEntries, stripes, loadFactor);
        }

        KeySerializer<K> kSer = (keySerializer != null) ? keySerializer
                : (KeySerializer<K>) (stringKeyEncoding == StringEncoding.LATIN1
                        ? BuiltInSerializers.STRING_KEY_LATIN1
                        : BuiltInSerializers.STRING_KEY_UTF8);
        // AUDIT-A1: when no explicit valueSerializer is set, default to STRING_VALUE
        // for the common <String, String> ergonomic case BUT emit a one-time WARN
        // to push users toward an explicit serializer. The previous silent default
        // could mask a <String, byte[]> mistake (byte[] would hit a ClassCastException
        // on the first put from STRING_VALUE's bridge method — not silent corruption,
        // but a cryptic error). Typed shortcuts .forStringValues() /
        // .forByteArrayValues() are the preferred opt-in.
        ValueSerializer<V> vSer;
        if (valueSerializer != null) {
            vSer = valueSerializer;
        } else {
            org.slf4j.LoggerFactory.getLogger(CacheBuilder.class).warn(
                    "No valueSerializer configured; defaulting to STRING_VALUE. "
                            + "Prefer an explicit .valueSerializer(...) or one of the "
                            + "typed shortcuts .forStringValues() / .forByteArrayValues().");
            vSer = (ValueSerializer<V>) BuiltInSerializers.STRING_VALUE;
        }

        SlabAllocator allocator = null;
        EntryPool entryPool = null;
        OffHeapHashTable hashTable = null;
        EvictionPolicy policy = null;
        OffHeapGhostCache offHeapGhostCache = null;
        try {
            allocator = new SlabAllocator(finalMemory, slabSize, zeroMemory);
            entryPool = new EntryPool(allocator, maxEntries, partitions);

            // S6 defense-in-depth: OffHeapCompactLRU packs segment flags into
            // the top 2 bits of the 32-bit next pointer, leaving only 30 bits
            // for slot IDs. Fail fast if slotCapacity exceeds this limit.
            if (entryPool.slotCapacity() > 0x3FFFFFFF) {
                throw new IllegalArgumentException(
                        "slotCapacity " + entryPool.slotCapacity()
                                + " exceeds OffHeapCompactLRU 30-bit limit (0x3FFFFFFF)."
                                + " Reduce maxEntries or increase entryPoolPartitions.");
            }

            hashTable = new OffHeapHashTable(entryPool, finalMemory, stripes, prefetch,
                    initialCapacity, loadFactor);
            policy = (evictionPolicy != null) ? evictionPolicy
                    : new LRUPolicy(maxEntries, entryPool.slotCapacity(), 0.01f, 0.80f);
            policy.setEntryPool(entryPool);

            GhostCache<K, V> ghostCache = null;
            if (ghostSize > 0 && effectiveGhostMode != GhostCacheMode.DISABLED) {
                if (effectiveGhostMode == GhostCacheMode.HEAP) {
                    ghostCache = new GhostCache<>(ghostSize);
                } else {
                    offHeapGhostCache = new OffHeapGhostCache(ghostSize);
                }
            }

            OffHeapCacheImpl<K, V> cache = new OffHeapCacheImpl<>(
                    kSer, vSer, allocator, entryPool, hashTable, policy,
                    evictionListener, evictionFilter, ghostCache, offHeapGhostCache,
                    asyncExecutor,
                    backgroundEviction, backgroundEvictionIntervalMs, evictionHighWatermark, evictionLowWatermark,
                    cacheName, metricsRecorder);
            return cache;
        } catch (Throwable t) {
            // Clean up partially allocated native resources
            if (offHeapGhostCache != null)
                try {
                    offHeapGhostCache.close();
                } catch (Exception ignored) {
                }
            if (policy != null)
                try {
                    policy.close();
                } catch (Exception ignored) {
                }
            if (hashTable != null)
                try {
                    hashTable.close();
                } catch (Exception ignored) {
                }
            if (entryPool != null)
                try {
                    entryPool.close();
                } catch (Exception ignored) {
                }
            if (allocator != null)
                try {
                    allocator.close();
                } catch (Exception ignored) {
                }
            throw t;
        }
    }

    public MemoryEstimator.MemoryEstimate estimateMemory() {
        int stripes = (hashTableStripes != null) ? hashTableStripes : autoStripes(maxEntries);
        int partitions = (entryPoolPartitions != null) ? entryPoolPartitions : autoPartitions(maxEntries);
        int ghostSize = (ghostCacheCapacity != null) ? ghostCacheCapacity : autoGhostSize(maxEntries);

        GhostCacheMode effectiveGhostMode = (ghostCacheMode != null) ? ghostCacheMode : GhostCacheMode.AUTO;
        if (effectiveGhostMode == GhostCacheMode.AUTO) {
            effectiveGhostMode = zeroHeapProfile ? GhostCacheMode.OFF_HEAP : GhostCacheMode.HEAP;
        }

        double loadFactor = (hashTableLoadFactor != null) ? hashTableLoadFactor : MemoryEstimator.DEFAULT_LOAD_FACTOR;
        Long indexBudget = indexMemoryBudgetBytes;
        if (indexBudget == null && indexMemoryBudgetPercent != null) {
            long mem = (memorySizeBytes != null) ? memorySizeBytes
                    : (long) ((long) maxEntries * (averageKeySize + averageValueSize + 40) * 1.3);
            indexBudget = (long) (mem * indexMemoryBudgetPercent);
        }
        if (indexBudget != null) {
            MemoryEstimator.IndexSizing sizing = MemoryEstimator.deriveIndexSizing(
                    maxEntries, stripes, partitions, indexBudget, ghostSize, effectiveGhostMode, loadFactor);
            loadFactor = sizing.loadFactor();
        }

        return MemoryEstimator.estimate(
                maxEntries,
                averageKeySize,
                averageValueSize,
                stripes,
                partitions,
                loadFactor,
                ghostSize,
                effectiveGhostMode,
                MemoryEstimator.DEFAULT_ALLOCATOR_OVERHEAD_RATIO);
    }

    private int autoStripes(int entries) {
        if (entries >= 10_000_000)
            return 16384; // Increased from 8192 for better write scalability
        if (entries >= 1_000_000)
            return 4096; // Increased from 1024 for better lock distribution
        if (entries >= 100_000)
            return 1024; // New tier for better 100k-1M performance
        return 256; // Increased from 64 for better baseline concurrency
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

    private int autoHashTableInitialCapacity(int entries, int stripes, double loadFactor) {
        int perStripeEntries = Math.max(2, (int) Math.ceil(entries / (double) stripes));
        int targetCapacity = (int) Math.ceil(perStripeEntries / loadFactor);
        return Math.max(16, targetCapacity);
    }
}
