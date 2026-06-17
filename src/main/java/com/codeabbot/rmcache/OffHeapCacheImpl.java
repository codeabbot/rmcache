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

import com.codeabbot.rmcache.eviction.EvictionCause;
import com.codeabbot.rmcache.eviction.EvictionFilter;
import com.codeabbot.rmcache.eviction.EvictionListener;
import com.codeabbot.rmcache.eviction.EvictionPolicy;
import com.codeabbot.rmcache.eviction.EntryMetadata;
import com.codeabbot.rmcache.index.EntryPool;
import com.codeabbot.rmcache.index.GhostCache;
import com.codeabbot.rmcache.index.OffHeapGhostCache;
import com.codeabbot.rmcache.index.OffHeapHashTable;

import com.codeabbot.rmcache.memory.SlabAllocator;
import com.codeabbot.rmcache.serializer.KeySerializer;
import com.codeabbot.rmcache.serializer.SegmentValueSerializer;
import com.codeabbot.rmcache.serializer.StreamingSerializer;
import com.codeabbot.rmcache.serializer.BuiltInSerializers.StringKeySerializer;
import com.codeabbot.rmcache.serializer.BuiltInSerializers.ByteArrayValueSerializer;
import com.codeabbot.rmcache.serializer.ValueSerializer;
import com.codeabbot.rmcache.util.CoarseClock;
import com.codeabbot.rmcache.util.ThreadLocalKeyBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

import java.util.Objects;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.lang.foreign.MemorySegment;

/**
 * High-performance Cache Implementation.
 * Ported from Kotlin implementation.
 *
 * @author Rabindra Meher
 */
public class OffHeapCacheImpl<K, V> implements OffHeapCache<K, V> {

    private static final Logger logger = LoggerFactory.getLogger(OffHeapCacheImpl.class);

    /**
     * High-performance Cache Context to unify ThreadLocals.
     * Shaves off redundant ThreadLocal.get() calls.
     *
     * <p><b>Thread-local leak note:</b> Each thread that calls get/put holds
     * a 4KB-256KB buffer until the thread dies. In container/app-server
     * environments with long-lived thread pools (Tomcat, Netty), call
     * {@link OffHeapCache#cleanupThreadLocals()} on thread retirement or
     * app undeploy to release these buffers.
     */
    static class CacheContext {
        private static final int INITIAL_BUFFER_SIZE = 4 * 1024; // 4KB initial, grows lazily
        private static final int MAX_BUFFER_SIZE = 256 * 1024; // 256KB max
        byte[] valueBuffer = new byte[INITIAL_BUFFER_SIZE];

        byte[] ensureCapacity(int needed) {
            if (needed > valueBuffer.length) {
                int newSize = Math.min(Math.max(valueBuffer.length * 2, needed), MAX_BUFFER_SIZE);
                if (needed > MAX_BUFFER_SIZE) {
                    return new byte[needed]; // One-off allocation for oversized values
                }
                valueBuffer = new byte[newSize];
            }
            return valueBuffer;
        }
    }

    private final KeySerializer<K> keySerializer;
    private final ValueSerializer<V> valueSerializer;
    private final SlabAllocator allocator;
    private final EntryPool entryPool;
    private final OffHeapHashTable hashTable;
    private final EvictionPolicy evictionPolicy;
    private final EvictionListener<K, V> evictionListener;
    private final EvictionFilter<K> evictionFilter;
    private final GhostCache<K, V> ghostCache;
    private final OffHeapGhostCache offHeapGhostCache;
    private final MetricsRecorder metrics;

    private final LongAdder globalHits = new LongAdder();
    private final LongAdder globalMisses = new LongAdder();
    private final LongAdder globalEvictions = new LongAdder();
    private final LongAdder evictionsBySize = new LongAdder();
    private final LongAdder evictionsByTtl = new LongAdder();
    private final LongAdder evictionsByExplicit = new LongAdder();

    private volatile boolean closed = false;

    // AUDIT-A6: static ThreadLocal so cleanupThreadLocals() can release the
    // per-thread value buffer regardless of which cache instance is holding
    // a reference. The buffer is generic scratch space (serialize/deserialize)
    // and carries no cache-instance state, so sharing across caches is safe.
    private static final ThreadLocal<CacheContext> context = ThreadLocal.withInitial(CacheContext::new);

    /**
     * ISSUE-007 + AUDIT-A6: Release thread-local buffers for the calling thread.
     * Called via {@link OffHeapCache#cleanupThreadLocals()}. Clears both the
     * key-encoding buffer and the per-thread value scratch buffer.
     */
    static void removeThreadLocals() {
        ThreadLocalKeyBuffer.cleanup();
        context.remove();
    }

    /**
     * ISSUE-008: Murmur-style hash spread to reduce collision clustering.
     * Java's String.hashCode() has known collision patterns (short strings,
     * numeric strings) that degrade Robin Hood probing to O(n).
     */
    private static int spread(int hash) {
        int h = hash;
        h ^= h >>> 16;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        return h;
    }

    private final ScheduledExecutorService maintenanceExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "rmcache-maintenance");
        t.setDaemon(true);
        return t;
    });

    private final boolean isStringKey;
    private final boolean isLatin1Key;
    private final boolean isByteArrayValue;
    private final boolean useKeyMatchFastPath;

    // O5: Pre-computed serializer type casts (avoids per-call instanceof)
    private final SegmentValueSerializer<V> cachedSegSer;
    private final StreamingSerializer<V> cachedStreamSer;
    // O1: Pre-computed ghost cache presence flags
    private final boolean hasGhostCache;
    private final boolean hasOffHeapGhostCache;

    private final Executor asyncExecutor;

    private final boolean backgroundEviction;
    private final long backgroundEvictionIntervalMs;
    private final double evictionHighWatermark;
    private final double evictionLowWatermark;
    private final AtomicBoolean evictionRequested = new AtomicBoolean(false);
    private final AtomicBoolean evictionUrgent = new AtomicBoolean(false);
    private ScheduledExecutorService evictionExecutor;

    private final String cacheName;

    // AUDIT-A2 + AUDIT-A5: captured once at ctor. `hasDefaultTTL` and
    // `tracksTTL` gate the two extra per-put branches behind predicted-false
    // tests, keeping caches without a TTL policy at zero overhead.
    private final long defaultTTLMs;
    private final boolean hasDefaultTTL;
    private final boolean tracksTTL;

    public OffHeapCacheImpl(
            KeySerializer<K> keySerializer,
            ValueSerializer<V> valueSerializer,
            SlabAllocator allocator,
            EntryPool entryPool,
            OffHeapHashTable hashTable,
            EvictionPolicy evictionPolicy,
            EvictionListener<K, V> evictionListener,
            EvictionFilter<K> evictionFilter,
            GhostCache<K, V> ghostCache,
            OffHeapGhostCache offHeapGhostCache,
            Executor asyncExecutor,
            boolean backgroundEviction,
            long backgroundEvictionIntervalMs,
            double evictionHighWatermark,
            double evictionLowWatermark,
            String cacheName,
            MetricsRecorder metrics) {
        if (logger.isDebugEnabled()) {
            logger.debug("OffHeapCacheImpl init start");
        }
        // H6 fix: Register with CoarseClock so the clock thread starts/stops
        // automatically with cache lifecycle.
        CoarseClock.acquire();
        this.keySerializer = keySerializer;
        this.valueSerializer = valueSerializer;
        this.allocator = allocator;
        this.entryPool = entryPool;
        this.hashTable = hashTable;
        this.evictionPolicy = evictionPolicy;
        this.evictionListener = evictionListener;
        this.evictionFilter = evictionFilter;
        this.ghostCache = ghostCache;
        this.offHeapGhostCache = offHeapGhostCache;
        this.asyncExecutor = asyncExecutor;

        this.isStringKey = keySerializer instanceof StringKeySerializer;
        this.isLatin1Key = (keySerializer instanceof StringKeySerializer s) && s.isLatin1FastPath();
        this.isByteArrayValue = valueSerializer instanceof ByteArrayValueSerializer;
        this.useKeyMatchFastPath = keySerializer instanceof com.codeabbot.rmcache.serializer.FastKeySerializer;

        // O5: Cache serializer type casts
        this.cachedSegSer = (valueSerializer instanceof SegmentValueSerializer)
                ? (SegmentValueSerializer<V>) valueSerializer
                : null;
        this.cachedStreamSer = (cachedSegSer == null && valueSerializer instanceof StreamingSerializer)
                ? (StreamingSerializer<V>) valueSerializer
                : null;
        // O1: Cache ghost cache presence
        this.hasGhostCache = ghostCache != null;
        this.hasOffHeapGhostCache = offHeapGhostCache != null;

        this.backgroundEviction = backgroundEviction;
        this.backgroundEvictionIntervalMs = Math.max(1, backgroundEvictionIntervalMs);
        this.evictionHighWatermark = evictionHighWatermark;
        this.evictionLowWatermark = evictionLowWatermark;
        this.cacheName = (cacheName == null || cacheName.isEmpty()) ? "rmcache" : cacheName;
        this.metrics = (metrics == null) ? MetricsRecorder.NOOP : metrics;

        long defTTL = evictionPolicy.getDefaultTTLMs();
        this.defaultTTLMs = defTTL;
        this.hasDefaultTTL = defTTL > 0L;
        this.tracksTTL = evictionPolicy.tracksTTL();

        maintenanceExecutor.scheduleWithFixedDelay(() -> {
            try {
                hashTable.drainCleanupQueue();
            } catch (Throwable e) {
                logger.error("Error during hash table cleanup", e);
            }
        }, 100, 100, TimeUnit.MILLISECONDS);

        // M1 fix: Periodically compact eviction policy internal structures
        // (e.g., timing wheel lazy-cancelled entries) to prevent unbounded growth.
        maintenanceExecutor.scheduleWithFixedDelay(() -> {
            try {
                evictionPolicy.compact();
            } catch (Throwable e) {
                logger.error("Error during eviction policy compaction", e);
            }
        }, 60_000, 60_000, TimeUnit.MILLISECONDS);

        if (backgroundEviction) {
            evictionExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "rmcache-eviction");
                t.setDaemon(true);
                return t;
            });
            evictionExecutor.scheduleWithFixedDelay(this::runEvictionCycle,
                    backgroundEvictionIntervalMs,
                    backgroundEvictionIntervalMs,
                    TimeUnit.MILLISECONDS);
        }
        if (logger.isDebugEnabled()) {
            logger.debug("OffHeapCacheImpl init done");
        }
    }

    private void requestEviction(boolean urgent) {
        if (!backgroundEviction) {
            return;
        }
        if (urgent) {
            evictionUrgent.set(true);
        } else {
            evictionRequested.set(true);
        }
    }

    private boolean isMemoryHigh() {
        if (evictionHighWatermark <= 0) {
            return false;
        }
        long total = allocator.getTotalBytes();
        if (total <= 0) {
            return false;
        }
        double used = allocator.getUsedBytes();
        return (used / total) >= evictionHighWatermark;
    }

    private boolean isMemoryAboveLow() {
        if (evictionLowWatermark <= 0) {
            return false;
        }
        long total = allocator.getTotalBytes();
        if (total <= 0) {
            return false;
        }
        double used = allocator.getUsedBytes();
        return (used / total) >= evictionLowWatermark;
    }

    private void runEvictionCycle() {
        try {
            boolean urgent = evictionUrgent.getAndSet(false);
            boolean requested = evictionRequested.getAndSet(false);
            boolean memoryHigh = isMemoryHigh();
            if (urgent || requested || memoryHigh) {
                evictIfNeeded(urgent || memoryHigh);
            }
        } catch (Throwable t) {
            logger.error("Error during background eviction", t);
        }
    }

    private void checkNotClosed() {
        if (closed)
            throw new IllegalStateException("Cache is closed");
    }

    @Override
    public void put(K key, V value) {
        put(key, value, null, (short) 0);
    }

    @Override
    public void put(K key, V value, Duration ttl) {
        put(key, value, ttl, (short) 0);
    }

    @Override
    public void put(K key, V value, short priority) {
        put(key, value, null, priority);
    }

    @Override
    public void put(K key, V value, Duration ttl, short priority) {
        // M1 fix: Fail fast on null key/value instead of NPE deep in serializer.
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(value, "value must not be null");
        if (closed)
            throw new IllegalStateException("Closed");

        putInternal(key, value, ttl, priority);
        metrics.onPut();
    }

    private void putInternal(K key, V value, Duration ttl, short priority) {
        putInternal(key, value, ttl, priority, false);
    }

    private boolean putInternal(K key, V value, Duration ttl, short priority, boolean putIfAbsent) {
        int keyHash = spread((isStringKey && key instanceof String s) ? s.hashCode() : keySerializer.hashCode(key));

        int existingSlot = 0;
        byte[] keyBytes = null;
        int keyLen = 0;
        if (hasOffHeapGhostCache) {
            // AUDIT-C1: Ghost cache's getSlot() does matchesAt() (full key compare).
            // The remaining TOCTOU is slot freed+reallocated between getSlot and
            // updateValue. This is caught by the key-hash guard inside updateValue
            // (see AUDIT-C1 in EntryPool.SubPool.updateValue) — one off-heap int
            // read under the SubPool lock, zero-cost on the common case.
            existingSlot = offHeapGhostCache.getSlot(key, keyHash, entryPool, keySerializer);
        }
        if (existingSlot == 0) {
            if (useKeyMatchFastPath) {
                existingSlot = hashTable.getWithKey(keyHash, key, keySerializer);
            } else {
                if (isLatin1Key && key instanceof String s) {
                    ThreadLocalKeyBuffer.BufferResult res = ThreadLocalKeyBuffer.encodeString(s);
                    keyLen = res.length();
                    keyBytes = Arrays.copyOf(res.buffer(), keyLen); // Defensive copy — buffer is reused
                } else {
                    keyBytes = keySerializer.serialize(key);
                    keyLen = keyBytes.length;
                }
                existingSlot = hashTable.getWithLen(keyHash, keyBytes, keyLen);
            }
        }

        SegmentValueSerializer<V> segSer = this.cachedSegSer;
        StreamingSerializer<V> streamSer = this.cachedStreamSer;
        int valueMaxLen = 0;
        byte[] valueBytes = null;
        int valueLen = 0;

        if (segSer != null) {
            valueMaxLen = Math.max(0, segSer.estimateSize(value));
        } else if (streamSer != null) {
            // PERF: the per-thread scratch buffer is only needed by the streaming
            // serializer, so fetch the ThreadLocal lazily here rather than up front
            // (the byte[]/segment hot path never touches it).
            CacheContext ctx = context.get();
            int estimate = Math.max(0, streamSer.estimateSize(value));
            byte[] buf = ctx.ensureCapacity(estimate);
            valueLen = streamSer.serializeTo(value, buf, 0);
            if (valueLen > buf.length) {
                // estimateSize undershot — re-allocate and re-serialize
                buf = ctx.ensureCapacity(valueLen);
                valueLen = streamSer.serializeTo(value, buf, 0);
            }
            valueBytes = buf;
        } else if (isByteArrayValue && value instanceof byte[] b) {
            valueLen = b.length;
            valueBytes = b;
        } else {
            valueBytes = valueSerializer.serialize(value);
            valueLen = valueBytes.length;
        }

        // AUDIT-A2: apply TTLPolicy.defaultTTLMs when the caller did not pass an
        // explicit duration. `hasDefaultTTL` is predicted-false for caches with
        // no TTL policy, so the common path is effectively unchanged.
        long expiresAtMillis;
        if (ttl != null) {
            expiresAtMillis = CoarseClock.getNow() + ttl.toMillis();
        } else if (hasDefaultTTL) {
            expiresAtMillis = CoarseClock.getNow() + defaultTTLMs;
        } else {
            expiresAtMillis = 0L;
        }

        if (existingSlot != 0) {
            // Respect putIfAbsent concurrency
            if (putIfAbsent)
                return false;

            // AUDIT-C1 + AUDIT-A3: Pass keyHash for key-hash guard inside updateValue.
            // This catches ghost-cache TOCTOU where slot was reallocated to
            // a different key — one off-heap int read under the SubPool lock.
            // Applied to all three update paths (byte[]/writer/serializer).
            boolean updated = (segSer != null)
                    ? entryPool.updateValueWithSerializer(existingSlot, valueMaxLen, segSer, value, keyHash)
                    : entryPool.updateValueWithLen(existingSlot, valueBytes, valueLen, keyHash);

            if (updated) {
                // AUDIT-H1: Update TTL on in-place value update only when caller
                // specified a TTL. put(key, value) without TTL preserves existing
                // expiration — clearing it would be a surprising behavior change.
                if (ttl != null) {
                    entryPool.setExpiresAt(existingSlot, expiresAtMillis);
                    // AUDIT-A5: reschedule so the timing-wheel heap reflects the
                    // new expiry. Gated on `tracksTTL` so non-TTL caches pay
                    // nothing.
                    if (tracksTTL) {
                        evictionPolicy.onTTLUpdate(existingSlot);
                    }
                }
                evictionPolicy.onAccess(existingSlot, keyHash);
                // Skip ghost cache bookkeeping on updates — slot already tracked
                return true;
            }
        }

        // Delay serialization until allocation is needed
        if (keyBytes == null) {
            if (isLatin1Key && key instanceof String s) {
                ThreadLocalKeyBuffer.BufferResult res = ThreadLocalKeyBuffer.encodeString(s);
                keyLen = res.length();
                keyBytes = Arrays.copyOf(res.buffer(), keyLen);
            } else {
                keyBytes = keySerializer.serialize(key);
                keyLen = keyBytes.length;
            }
        }

        if (existingSlot != 0) {
            int newSlot = (segSer != null)
                    ? entryPool.allocateWithSerializer(keyHash, keyBytes, keyLen, valueMaxLen, segSer, value, priority,
                            expiresAtMillis)
                    : entryPool.allocateWithLen(keyHash, keyBytes, keyLen, valueBytes, valueLen, priority,
                            expiresAtMillis);
            if (newSlot == 0) {
                requestEviction(true);
                evictIfNeeded(true);
                newSlot = (segSer != null)
                        ? entryPool.allocateWithSerializer(keyHash, keyBytes, keyLen, valueMaxLen, segSer, value,
                                priority, expiresAtMillis)
                        : entryPool.allocateWithLen(keyHash, keyBytes, keyLen, valueBytes, valueLen, priority,
                                expiresAtMillis);
                if (newSlot == 0)
                    return false;
            }

            int oldSlotInTable = hashTable.putEntry(keyHash, keyBytes, keyLen, newSlot, false);
            if (oldSlotInTable == -1) {
                entryPool.free(newSlot);
                return false; // K7: probe limit exceeded — insertion failed
            } else if (oldSlotInTable != 0) {
                evictionPolicy.onRemove(oldSlotInTable);
                entryPool.free(oldSlotInTable);
                evictionPolicy.onAccess(newSlot, keyHash);
            } else {
                evictionPolicy.onAdd(newSlot, keyHash, priority);
            }

            if (hasGhostCache)
                ghostCache.put(key, keyHash, value, ttl != null ? ttl.toMillis() : 0);
            if (hasOffHeapGhostCache)
                offHeapGhostCache.put(keyHash, newSlot);
            return true;
        }

        if (evictionPolicy.shouldEvict()) {
            if (backgroundEviction) {
                requestEviction(false);
            } else {
                evictIfNeeded();
            }
        }

        int slot = (segSer != null)
                ? entryPool.allocateWithSerializer(keyHash, keyBytes, keyLen, valueMaxLen, segSer, value, priority,
                        expiresAtMillis)
                : entryPool.allocateWithLen(keyHash, keyBytes, keyLen, valueBytes, valueLen, priority,
                        expiresAtMillis);
        if (slot == 0) {
            requestEviction(true);
            evictIfNeeded(true);
            slot = (segSer != null)
                    ? entryPool.allocateWithSerializer(keyHash, keyBytes, keyLen, valueMaxLen, segSer, value, priority,
                            expiresAtMillis)
                    : entryPool.allocateWithLen(keyHash, keyBytes, keyLen, valueBytes, valueLen, priority,
                            expiresAtMillis);
            if (slot == 0)
                return false;
        }

        try {
            int oldSlot = hashTable.putEntry(keyHash, keyBytes, keyLen, slot, putIfAbsent);
            if (oldSlot == -1) {
                entryPool.free(slot);
                if (putIfAbsent)
                    return false;

                evictIfNeeded(true);
                int retrySlot = (segSer != null)
                        ? entryPool.allocateWithSerializer(keyHash, keyBytes, keyLen, valueMaxLen, segSer, value,
                                priority, expiresAtMillis)
                        : entryPool.allocateWithLen(keyHash, keyBytes, keyLen, valueBytes, valueLen, priority,
                                expiresAtMillis);
                if (retrySlot == 0) {
                    return false;
                }
                int retryOld = hashTable.putEntry(keyHash, keyBytes, keyLen, retrySlot, putIfAbsent);
                if (retryOld == -1) {
                    entryPool.free(retrySlot);
                    return false;
                } else if (retryOld != 0) {
                    if (putIfAbsent) {
                        entryPool.free(retrySlot);
                        return false;
                    } else {
                        evictionPolicy.onRemove(retryOld);
                        entryPool.free(retryOld);
                        evictionPolicy.onAccess(retrySlot, keyHash);
                        if (hasOffHeapGhostCache)
                            offHeapGhostCache.put(keyHash, retrySlot);
                    }
                } else {
                    evictionPolicy.onAdd(retrySlot, keyHash, priority);
                    if (hasOffHeapGhostCache)
                        offHeapGhostCache.put(keyHash, retrySlot);
                }
                if (hasGhostCache)
                    ghostCache.put(key, keyHash, value, ttl != null ? ttl.toMillis() : 0);
                return true;
            } else if (oldSlot != 0) {
                if (putIfAbsent) {
                    entryPool.free(slot); // Race: someone else added it
                    return false;
                } else {
                    evictionPolicy.onRemove(oldSlot);
                    entryPool.free(oldSlot);
                    evictionPolicy.onAccess(slot, keyHash);
                    if (hasOffHeapGhostCache)
                        offHeapGhostCache.put(keyHash, slot);
                }
            } else {
                evictionPolicy.onAdd(slot, keyHash, priority);
                if (hasOffHeapGhostCache)
                    offHeapGhostCache.put(keyHash, slot);
            }
        } catch (Throwable e) {
            entryPool.free(slot);
            throw e;
        }

        if (hasGhostCache)
            ghostCache.put(key, keyHash, value, ttl != null ? ttl.toMillis() : 0);
        return true;
    }

    @Override
    public V get(K key) {
        Objects.requireNonNull(key, "key must not be null");
        if (closed)
            return null;

        int keyHash = spread((isStringKey && key instanceof String s) ? s.hashCode() : keySerializer.hashCode(key));

        if (hasGhostCache) {
            V ghostHit = ghostCache.get(key, keyHash);
            if (ghostHit != null) {
                globalHits.increment();
                return ghostHit;
            }
        }

        byte[] keyBytes = null;
        int keyLen = 0;
        int slot = 0;
        if (hasOffHeapGhostCache) {
            int ghostSlot = offHeapGhostCache.getSlot(key, keyHash, entryPool, keySerializer);
            // Ghost cache's getSlot() already validates the slot via
            // entryPool.matchesAt() — which confirms the slot is live and
            // the key matches. No hash table re-validation needed.
            // The only residual risk is a concurrent eviction between
            // matchesAt (inside getSlot) and the value read below — but
            // this is the same TOCTOU that exists in the normal GET path
            // and is guarded by readValueFromSlot's offset check (C3 fix).
            if (ghostSlot != 0) {
                slot = ghostSlot;
            }
        }

        if (slot == 0) {
            if (useKeyMatchFastPath) {
                slot = hashTable.getWithKey(keyHash, key, keySerializer);
            } else {
                if (keyBytes == null) {
                    if (isLatin1Key && key instanceof String s) {
                        ThreadLocalKeyBuffer.BufferResult res = ThreadLocalKeyBuffer.encodeString(s);
                        keyBytes = res.buffer();
                        keyLen = res.length();
                    } else {
                        keyBytes = keySerializer.serialize(key);
                        keyLen = keyBytes.length;
                    }
                }
                slot = hashTable.getWithLen(keyHash, keyBytes, keyLen);
            }
            if (slot == 0) {
                globalMisses.increment();
                return null;
            }
        }

        if (entryPool.isExpired(slot)) {
            if (keyBytes == null) {
                keyBytes = entryPool.readKey(slot);
                keyLen = (keyBytes != null) ? keyBytes.length : 0;
            }
            if (keyBytes != null) {
                removeInternal(keyHash, keyBytes, keyLen, slot, EvictionCause.EXPIRED);
            }
            globalMisses.increment();
            return null;
        }

        globalHits.increment();
        evictionPolicy.onAccess(slot, keyHash);

        V result = readValueFromSlot(slot, keyHash);

        if (hasGhostCache)
            ghostCache.put(key, keyHash, result);
        if (hasOffHeapGhostCache)
            offHeapGhostCache.put(keyHash, slot);

        return result;
    }

    private V readValueFromSlot(int slot, int keyHash) {
        // A2 (ABA guard) + hot-path read reduction: read the block offset ONCE,
        // copy the value through that snapshot, then validate. The prior C1/C3
        // guard only re-checked "freed" (offset == -1); a slot freed AND reused by
        // a different key mid-copy leaves a valid (non -1) offset, so that guard
        // could return another key's bytes. Requiring the offset to be unchanged
        // AND the stored key-hash to still equal the lookup hash detects that race.
        // Reading the offset once (instead of re-reading it through getValueLen /
        // readValueToBuffer / readValue) also removes redundant volatile reads.
        // O2: opaque read — no acquire barrier on ARM. The dependent value reads
        // below are address-ordered after this load on real hardware, and the
        // post-copy re-read detects any change, so volatile ordering isn't needed.
        long off = entryPool.getOffsetOpaque(slot);
        if (off == -1L)
            return null;

        if (isByteArrayValue) {
            // PERF: copy straight off-heap into the freshly-allocated result array
            // (one copy + one allocation). The previous path copied twice per get:
            // off-heap -> reusable thread-local buffer, then Arrays.copyOf -> result.
            byte[] result = entryPool.readValueAt(off);
            if (entryPool.getOffsetOpaque(slot) != off || entryPool.getKeyHashAt(off) != keyHash)
                return null;
            @SuppressWarnings("unchecked")
            V res = (V) result;
            return res;
        }

        if (cachedStreamSer != null) {
            // Streaming keeps the reusable buffer (deserializeFrom reads from it; no
            // result array is allocated), so the intermediate copy is not wasted here.
            int valLen = entryPool.getValueLenAt(off);
            CacheContext ctx = context.get();
            byte[] vBuf = ctx.ensureCapacity(valLen);
            entryPool.readValueToBufferAt(off, vBuf, 0, valLen);
            if (entryPool.getOffsetOpaque(slot) != off || entryPool.getKeyHashAt(off) != keyHash)
                return null;
            return cachedStreamSer.deserializeFrom(vBuf, 0, valLen);
        }

        byte[] valBytes = entryPool.readValueAt(off);
        if (entryPool.getOffsetOpaque(slot) != off || entryPool.getKeyHashAt(off) != keyHash)
            return null;
        return valueSerializer.deserialize(valBytes);
    }

    @Override
    public boolean remove(K key) {
        Objects.requireNonNull(key, "key must not be null");
        checkNotClosed();
        int keyHash = spread((isStringKey && key instanceof String s) ? s.hashCode() : keySerializer.hashCode(key));
        byte[] keyBytes = null;
        int keyLen = 0;
        int slot;

        if (useKeyMatchFastPath) {
            slot = hashTable.getWithKey(keyHash, key, keySerializer);
        } else {
            if (isLatin1Key && key instanceof String s) {
                ThreadLocalKeyBuffer.BufferResult res = ThreadLocalKeyBuffer.encodeString(s);
                keyLen = res.length();
                keyBytes = Arrays.copyOf(res.buffer(), keyLen); // Defensive copy — buffer is reused
            } else {
                keyBytes = keySerializer.serialize(key);
                keyLen = keyBytes.length;
            }
            slot = hashTable.getWithLen(keyHash, keyBytes, keyLen);
        }

        if (slot == 0)
            return false;

        if (keyBytes == null) {
            keyBytes = entryPool.readKey(slot);
            keyLen = (keyBytes != null) ? keyBytes.length : 0;
        }

        removeInternal(keyHash, keyBytes, keyLen, slot, EvictionCause.EXPLICIT);
        metrics.onRemove();
        return true;
    }

    @Override
    public boolean putIfAbsent(K key, V value) {
        return putIfAbsent(key, value, null);
    }

    @Override
    public boolean putIfAbsent(K key, V value, Duration ttl) {
        checkNotClosed();
        boolean inserted = putInternal(key, value, ttl, (short) 0, true);
        if (inserted) {
            metrics.onPut();
        }
        return inserted;
    }

    @Override
    public V computeIfAbsent(K key, java.util.function.Function<K, V> loader) {
        return computeIfAbsent(key, loader, null);
    }

    @Override
    public V computeIfAbsent(K key, java.util.function.Function<K, V> loader, Duration ttl) {
        // NOTE: This is NOT atomic. The sequence get() → loader.apply() → putIfAbsent()
        // has a TOCTOU window where another thread can insert the same key between
        // the get() miss and the putIfAbsent(). In that case, the loader is invoked
        // but its result is discarded. For expensive loaders (DB, RPC), consider
        // external synchronization (e.g., Striped<Lock>) if duplicate computation
        // is unacceptable.
        checkNotClosed();

        // First attempt to get existing value
        V existing = get(key);
        if (existing != null) {
            return existing;
        }

        // Compute the value
        V computed = loader.apply(key);
        if (computed == null) {
            return null;
        }

        // Try to insert (avoids overwrite race conditions)
        boolean inserted = putInternal(key, computed, ttl, (short) 0, true);
        if (!inserted) {
            // Race lost, retrieve the newly updated value
            return get(key);
        }
        return computed;
    }

    @Override
    public void putAll(java.util.Map<K, V> entries) {
        putAll(entries, null);
    }

    @Override
    public void putAll(java.util.Map<K, V> entries, Duration ttl) {
        checkNotClosed();
        for (java.util.Map.Entry<K, V> entry : entries.entrySet()) {
            put(entry.getKey(), entry.getValue(), ttl, (short) 0);
        }
    }

    @Override
    public java.util.Map<K, V> getAll(java.util.Collection<K> keys) {
        checkNotClosed();
        // P2-O1 fix: Pre-size with load factor to avoid internal resizes
        java.util.Map<K, V> result = new java.util.HashMap<>((int) (keys.size() / 0.75f) + 1);
        for (K key : keys) {
            V value = get(key);
            if (value != null) {
                result.put(key, value);
            }
        }
        return result;
    }

    @Override
    public CompletableFuture<Void> putAsync(K key, V value) {
        return (asyncExecutor != null)
                ? CompletableFuture.runAsync(() -> put(key, value), asyncExecutor)
                : CompletableFuture.runAsync(() -> put(key, value));
    }

    @Override
    public CompletableFuture<V> getAsync(K key) {
        return (asyncExecutor != null)
                ? CompletableFuture.supplyAsync(() -> get(key), asyncExecutor)
                : CompletableFuture.supplyAsync(() -> get(key));
    }

    @Override
    public boolean contains(K key) {
        return get(key) != null;
    }

    @Override
    public int size() {
        return (int) hashTable.size();
    }

    @Override
    public void clear() {
        if (ghostCache != null)
            ghostCache.invalidateAll();
        if (offHeapGhostCache != null)
            offHeapGhostCache.clear();
        // C4 fix: Free all allocated entry blocks before clearing the hash table.
        // Without this, slab memory is leaked until close().
        int total = entryPool.slotCapacity();
        for (int slot = 1; slot < total; slot++) {
            if (entryPool.getOffset(slot) != -1L) {
                evictionPolicy.onRemove(slot);
                entryPool.free(slot);
            }
        }
        hashTable.clear();
    }

    @Override
    public CacheStats getStats() {
        return new CacheStats(
                globalHits.sum(),
                globalMisses.sum(),
                globalEvictions.sum(),
                size(),
                allocator.getUsedBytes(),
                allocator.getTotalBytes(),
                evictionsBySize.sum(),
                evictionsByTtl.sum(),
                evictionsByExplicit.sum());
    }

    @Override
    public String getCacheName() {
        return cacheName;
    }

    @Override
    public String toString() {
        return "OffHeapCache[" + cacheName + ", size=" + size() + "]";
    }

    private void removeInternal(int keyHash, byte[] keyBytes, int keyLen, int slot, EvictionCause cause) {
        if (ghostCache != null)
            ghostCache.invalidate(keyHash);
        if (offHeapGhostCache != null)
            offHeapGhostCache.invalidate(keyHash);

        byte[] lookupKey = (keyBytes.length == keyLen) ? keyBytes : Arrays.copyOf(keyBytes, keyLen);
        int removedSlot = hashTable.remove(keyHash, lookupKey);

        if (removedSlot != 0) {
            try {
                if (evictionListener != null) {
                    K key = keySerializer.deserialize(keyBytes);
                    // Read value EAGERLY before free — prevents use-after-free
                    // if the listener stores the supplier for deferred access
                    byte[] vBytes = entryPool.readValue(removedSlot);
                    java.util.function.Supplier<V> valueLazy = () -> valueSerializer.deserialize(vBytes);
                    evictionListener.onEviction(key, valueLazy, cause);
                }
            } finally {
                // Always clean up — even if listener throws
                evictionPolicy.onRemove(removedSlot);
                entryPool.free(removedSlot);

                switch (cause) {
                    case SIZE     -> { globalEvictions.increment(); evictionsBySize.increment(); }
                    case EXPIRED  -> { globalEvictions.increment(); evictionsByTtl.increment(); }
                    case EXPLICIT -> evictionsByExplicit.increment();
                    default       -> globalEvictions.increment();
                }
            }
        }
    }

    private void evictIfNeeded() {
        evictIfNeeded(false);
    }

    private void evictIfNeeded(boolean force) {
        int attempts = 0;
        int filterRejects = 0; // Track consecutive filter rejections to avoid spin loops
        boolean forceOnce = force;
        int totalAttempts = 0; // H6 fix: hard cap to prevent unbounded blocking
        // P2-M1 fix: Reusable metadata avoids anonymous class allocation per attempt
        ReusableEntryMetadata meta = (evictionFilter != null) ? new ReusableEntryMetadata() : null;
        while (attempts < 100 && totalAttempts < 1000) {
            totalAttempts++;
            if (!forceOnce && !evictionPolicy.shouldEvict() && !isMemoryAboveLow()) {
                break;
            }
            int slot = evictionPolicy.selectVictim();
            if (slot == 0)
                break;

            int keyHash = entryPool.getKeyHash(slot);
            byte[] keyBytes = entryPool.readKey(slot);
            if (keyBytes == null) {
                evictionPolicy.onRemove(slot);
                attempts++;
                continue;
            }

            if (evictionFilter != null) {
                // E2E-H3: Only deserialize key when eviction filter is configured.
                // Without a filter, this String allocation is wasted.
                K key = keySerializer.deserialize(keyBytes);
                meta.setSlot(slot);

                if (!evictionFilter.canEvict(key, meta)) {
                    // Re-admit victim back to LRU. We use onAdd() (not onAccess())
                    // because selectVictim() destructively removes the slot from the
                    // LRU linked list — onAccess() can only promote in-list slots.
                    // Note: onAdd inserts to WINDOW segment. Under aggressive filters
                    // this may shift the SLRU balance. Acceptable for single-server;
                    // clustering phase can add a reAdmitToProbation() method.
                    evictionPolicy.onAdd(slot, keyHash, meta.getPriority());
                    filterRejects++;
                    attempts++;
                    if (filterRejects >= 3) {
                        break;
                    }
                    continue;
                }
            }

            filterRejects = 0;
            removeInternal(keyHash, keyBytes, keyBytes.length, slot, EvictionCause.SIZE);
            attempts = 0;
            forceOnce = false;
        }
    }

    /**
     * P2-M1 fix: Reusable EntryMetadata implementation. One instance per eviction
     * cycle instead of one anonymous class per eviction attempt. At 100 attempts
     * × eviction every 10ms, this eliminates ~10K/sec short-lived objects.
     */
    private class ReusableEntryMetadata implements EntryMetadata {
        private int slot;

        void setSlot(int slot) {
            this.slot = slot;
        }

        @Override
        public short getPriority() {
            return entryPool.getPriority(slot);
        }

        @Override
        public int getExpiresAtSeconds() {
            return (int) (entryPool.getExpiresAt(slot) / 1000);
        }

        @Override
        public boolean isExpired() {
            return entryPool.isExpired(slot);
        }
    }

    /**
     * C3 warning: The MemorySegment passed to the processor points to LIVE off-heap
     * memory. If another thread evicts or updates (realloc) the same entry while
     * the
     * processor is executing, the segment may point to freed or reallocated memory
     * (use-after-free). Callers must ensure external synchronization or accept the
     * risk of reading stale/corrupt data during concurrent eviction.
     */
    @Override
    public <T> T getZeroCopy(K key, java.util.function.Function<MemorySegment, T> processor) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(processor, "processor must not be null");
        if (closed)
            return null;
        int keyHash = spread((isStringKey && key instanceof String s) ? s.hashCode() : keySerializer.hashCode(key));

        int slot = 0;
        byte[] keyBytes = null;
        int keyLen = 0;
        if (offHeapGhostCache != null) {
            int ghostSlot = offHeapGhostCache.getSlot(key, keyHash, entryPool, keySerializer);
            // P2-C2 fix: Re-validate ghost cache hit through hash table (same as get()),
            // to prevent stale ghost entries returning a slot now owned by a different key.
            if (ghostSlot != 0) {
                int confirmedSlot;
                if (useKeyMatchFastPath) {
                    confirmedSlot = hashTable.getWithKey(keyHash, key, keySerializer);
                } else {
                    if (isLatin1Key && key instanceof String s) {
                        ThreadLocalKeyBuffer.BufferResult res = ThreadLocalKeyBuffer.encodeString(s);
                        keyBytes = res.buffer();
                        keyLen = res.length();
                    } else {
                        keyBytes = keySerializer.serialize(key);
                        keyLen = keyBytes.length;
                    }
                    confirmedSlot = hashTable.getWithLen(keyHash, keyBytes, keyLen);
                }
                if (confirmedSlot == ghostSlot) {
                    slot = ghostSlot;
                } else {
                    offHeapGhostCache.invalidate(keyHash);
                    slot = confirmedSlot;
                }
            }
        }

        if (slot == 0) {
            if (useKeyMatchFastPath) {
                slot = hashTable.getWithKey(keyHash, key, keySerializer);
            } else {
                if (keyBytes == null) {
                    if (isLatin1Key && key instanceof String s) {
                        ThreadLocalKeyBuffer.BufferResult res = ThreadLocalKeyBuffer.encodeString(s);
                        keyBytes = res.buffer();
                        keyLen = res.length();
                    } else {
                        keyBytes = keySerializer.serialize(key);
                        keyLen = keyBytes.length;
                    }
                }
                slot = hashTable.getWithLen(keyHash, keyBytes, keyLen);
            }
        }
        if (slot == 0)
            return null;
        if (entryPool.isExpired(slot)) {
            if (keyBytes == null) {
                keyBytes = entryPool.readKey(slot);
                keyLen = (keyBytes != null) ? keyBytes.length : 0;
            }
            if (keyBytes != null) {
                removeInternal(keyHash, keyBytes, keyLen, slot, EvictionCause.EXPIRED);
            }
            return null;
        }
        MemorySegment segment = entryPool.getValueSegment(slot);
        if (segment == null)
            return null;
        evictionPolicy.onAccess(slot, keyHash);
        if (offHeapGhostCache != null)
            offHeapGhostCache.put(keyHash, slot);
        return processor.apply(segment);
    }

    /**
     * M5 warning: The returned CacheValueView holds a raw slot reference and value
     * offset. If the entry is evicted, removed, or reallocated (value resize) after
     * this method returns, the view will read freed/stale off-heap memory. Callers
     * must consume the view immediately and not store it beyond the current scope.
     */
    @Override
    public CacheValueView getView(K key) {
        Objects.requireNonNull(key, "key must not be null");
        if (closed)
            return null;
        int keyHash = spread((isStringKey && key instanceof String s) ? s.hashCode() : keySerializer.hashCode(key));

        int slot = 0;
        byte[] keyBytes = null;
        int keyLen = 0;
        if (offHeapGhostCache != null) {
            // AUDIT-H2: Re-validate ghost cache hit via hash table (same as getZeroCopy).
            int ghostSlot = offHeapGhostCache.getSlot(key, keyHash, entryPool, keySerializer);
            if (ghostSlot != 0) {
                int confirmedSlot;
                if (useKeyMatchFastPath) {
                    confirmedSlot = hashTable.getWithKey(keyHash, key, keySerializer);
                } else {
                    if (isLatin1Key && key instanceof String s) {
                        ThreadLocalKeyBuffer.BufferResult res = ThreadLocalKeyBuffer.encodeString(s);
                        keyBytes = res.buffer();
                        keyLen = res.length();
                    } else {
                        keyBytes = keySerializer.serialize(key);
                        keyLen = keyBytes.length;
                    }
                    confirmedSlot = hashTable.getWithLen(keyHash, keyBytes, keyLen);
                }
                if (confirmedSlot == ghostSlot) {
                    slot = ghostSlot;
                } else {
                    offHeapGhostCache.invalidate(keyHash);
                    slot = confirmedSlot;
                }
            }
        }

        if (slot == 0) {
            if (useKeyMatchFastPath) {
                slot = hashTable.getWithKey(keyHash, key, keySerializer);
            } else {
                if (keyBytes == null) {
                    if (isLatin1Key && key instanceof String s) {
                        ThreadLocalKeyBuffer.BufferResult res = ThreadLocalKeyBuffer.encodeString(s);
                        keyBytes = res.buffer();
                        keyLen = res.length();
                    } else {
                        keyBytes = keySerializer.serialize(key);
                        keyLen = keyBytes.length;
                    }
                }
                slot = hashTable.getWithLen(keyHash, keyBytes, keyLen);
            }
        }
        if (slot == 0)
            return null;
        if (entryPool.isExpired(slot)) {
            if (keyBytes == null) {
                keyBytes = entryPool.readKey(slot);
                keyLen = (keyBytes != null) ? keyBytes.length : 0;
            }
            if (keyBytes != null) {
                removeInternal(keyHash, keyBytes, keyLen, slot, EvictionCause.EXPIRED);
            }
            return null;
        }

        // E2E-E2 fix: Use allocation-free getValueDataOffset() + getValueLen()
        // instead of getValuePosition() which allocates new long[2] per call.
        long valueOffset = entryPool.getValueDataOffset(slot);
        if (valueOffset == -1L)
            return null;
        int valueLen = entryPool.getValueLen(slot);

        evictionPolicy.onAccess(slot, keyHash);
        if (hasOffHeapGhostCache)
            offHeapGhostCache.put(keyHash, slot);

        return new CacheValueViewImpl(entryPool, slot, valueOffset, valueLen);
    }

    @Override
    public void close() {
        closed = true;
        // Shut down executors FIRST and wait for completion before freeing native
        // memory
        if (evictionExecutor != null) {
            evictionExecutor.shutdown();
            try {
                if (!evictionExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                    evictionExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                evictionExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        maintenanceExecutor.shutdown();
        try {
            if (!maintenanceExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                maintenanceExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            maintenanceExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        entryPool.close();
        hashTable.close();
        allocator.close();
        if (offHeapGhostCache != null) {
            offHeapGhostCache.close();
        }
        if (evictionPolicy != null) {
            evictionPolicy.close();
        }
        // H6 fix: Release CoarseClock reference — stops clock thread
        // when the last cache instance is closed.
        CoarseClock.release();
    }
}
