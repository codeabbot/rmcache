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
import com.codeabbot.rmcache.memory.NativeMemory;
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
import java.util.Set;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
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

    private final LongAdder globalHits = new LongAdder();
    private final LongAdder globalMisses = new LongAdder();
    private final LongAdder globalEvictions = new LongAdder();

    private volatile boolean closed = false;

    private final ThreadLocal<CacheContext> context = ThreadLocal.withInitial(CacheContext::new);

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

    private final boolean backgroundEviction;
    private final long backgroundEvictionIntervalMs;
    private final double evictionHighWatermark;
    private final double evictionLowWatermark;
    private final AtomicBoolean evictionRequested = new AtomicBoolean(false);
    private final AtomicBoolean evictionUrgent = new AtomicBoolean(false);
    private ScheduledExecutorService evictionExecutor;

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
            boolean backgroundEviction,
            long backgroundEvictionIntervalMs,
            double evictionHighWatermark,
            double evictionLowWatermark) {
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
    }

    private void putInternal(K key, V value, Duration ttl, short priority) {
        putInternal(key, value, ttl, priority, false);
    }

    private boolean putInternal(K key, V value, Duration ttl, short priority, boolean putIfAbsent) {
        int keyHash = (isStringKey && key instanceof String s) ? s.hashCode() : keySerializer.hashCode(key);

        CacheContext ctx = context.get();

        int existingSlot = 0;
        if (hasOffHeapGhostCache) {
            existingSlot = offHeapGhostCache.getSlot(key, keyHash, entryPool, keySerializer);
        }
        byte[] keyBytes = null;
        int keyLen = 0;
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

        long expiresAtMillis = (ttl != null) ? CoarseClock.getNow() + ttl.toMillis() : 0L;

        if (existingSlot != 0) {
            // Respect putIfAbsent concurrency
            if (putIfAbsent)
                return false;

            boolean updated = (segSer != null)
                    ? entryPool.updateValueWithSerializer(existingSlot, valueMaxLen, segSer, value)
                    : entryPool.updateValueWithLen(existingSlot, valueBytes, valueLen);

            if (updated) {
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

        CacheContext ctx = context.get();
        int keyHash = (isStringKey && key instanceof String s) ? s.hashCode() : keySerializer.hashCode(key);

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
            // Re-validate ghost cache hit through hash table to prevent stale slot
            // references
            if (ghostSlot != 0) {
                int confirmedSlot;
                if (useKeyMatchFastPath) {
                    confirmedSlot = hashTable.getWithKey(keyHash, key, keySerializer);
                } else {
                    if (isLatin1Key && key instanceof String s) {
                        // M7 warning: ThreadLocalKeyBuffer is a SHARED buffer. This is safe
                        // as long as this get() path has no callbacks or re-entrancy points.
                        // Adding eviction listeners, cache loaders, or nested cache calls
                        // here would corrupt the buffer. If re-entrancy is ever needed,
                        // copy keyBytes before any callback: keyBytes = Arrays.copyOf(res.buffer(),
                        // res.length()).
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
                    // Ghost cache was stale — invalidate and use hash table result
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

        V result = readValueFromSlot(slot, ctx);

        if (hasGhostCache)
            ghostCache.put(key, keyHash, result);
        if (hasOffHeapGhostCache)
            offHeapGhostCache.put(keyHash, slot);

        return result;
    }

    private V readValueFromSlot(int slot, CacheContext ctx) {
        // C3 fix: Guard against slot freed between lookup and read
        if (entryPool.getOffset(slot) == -1L)
            return null;
        if (isByteArrayValue) {
            int valLen = entryPool.getValueLen(slot);
            byte[] vBuf = ctx.ensureCapacity(valLen);
            entryPool.readValueToBuffer(slot, vBuf, 0, valLen);
            // C1 fix: Post-read validation — if slot was freed while we were
            // copying bytes, the data may be corrupt. Re-check offset.
            if (entryPool.getOffset(slot) == -1L)
                return null;
            @SuppressWarnings("unchecked")
            V res = (V) Arrays.copyOf(vBuf, valLen);
            return res;
        }

        if (cachedStreamSer != null) {
            int valLen = entryPool.getValueLen(slot);
            byte[] vBuf = ctx.ensureCapacity(valLen);
            entryPool.readValueToBuffer(slot, vBuf, 0, valLen);
            // C1 fix: Post-read validation
            if (entryPool.getOffset(slot) == -1L)
                return null;
            return cachedStreamSer.deserializeFrom(vBuf, 0, valLen);
        }

        byte[] valBytes = entryPool.readValue(slot);
        // C1 fix: Post-read validation
        if (entryPool.getOffset(slot) == -1L)
            return null;
        return valueSerializer.deserialize(valBytes);
    }

    @Override
    public boolean remove(K key) {
        Objects.requireNonNull(key, "key must not be null");
        checkNotClosed();
        int keyHash = (isStringKey && key instanceof String s) ? s.hashCode() : keySerializer.hashCode(key);
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
        return true;
    }

    @Override
    public boolean putIfAbsent(K key, V value) {
        return putIfAbsent(key, value, null);
    }

    @Override
    public boolean putIfAbsent(K key, V value, Duration ttl) {
        checkNotClosed();
        return putInternal(key, value, ttl, (short) 0, true);
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
        java.util.Map<K, V> result = new java.util.HashMap<>(keys.size());
        for (K key : keys) {
            V value = get(key);
            if (value != null) {
                result.put(key, value);
            }
        }
        return result;
    }

    // H3 note: putAsync/getAsync delegate to ForkJoinPool.commonPool() which has
    // an unbounded submission queue. At 1B-scale throughput (millions of async
    // ops/sec), this can create GC pressure and latency spikes. For production
    // use at scale, prefer synchronous put/get or provide a bounded executor.
    // TODO: Accept an optional Executor via CacheBuilder for async operations.
    @Override
    public CompletableFuture<Void> putAsync(K key, V value) {
        return CompletableFuture.runAsync(() -> put(key, value));
    }

    @Override
    public CompletableFuture<V> getAsync(K key) {
        return CompletableFuture.supplyAsync(() -> get(key));
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
    public Set<K> getKeys() {
        throw new UnsupportedOperationException(
                "getKeys() is not supported by OffHeapCacheImpl. " +
                        "Off-heap entries cannot be iterated without full key deserialization.");
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
                allocator.getTotalBytes());
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

                if (cause != EvictionCause.EXPLICIT)
                    globalEvictions.increment();
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
            K key = keySerializer.deserialize(keyBytes);

            if (evictionFilter != null) {
                final int s = slot;
                EntryMetadata meta = new EntryMetadata() {
                    @Override
                    public short getPriority() {
                        return entryPool.getPriority(s);
                    }

                    @Override
                    public int getCreatedAtSeconds() {
                        return 0;
                    }

                    @Override
                    public int getExpiresAtSeconds() {
                        return (int) (entryPool.getExpiresAt(s) / 1000);
                    }

                    @Override
                    public long getAgeMillis() {
                        return 0;
                    }

                    @Override
                    public boolean isExpired() {
                        return entryPool.isExpired(s);
                    }
                };

                if (!evictionFilter.canEvict(key, meta)) {
                    evictionPolicy.onAdd(slot, keyHash, meta.getPriority());
                    filterRejects++;
                    attempts++;
                    // Avoid infinite re-admission loop: if the same slots keep getting rejected,
                    // break out after a small number of consecutive rejections
                    if (filterRejects >= 3) {
                        break;
                    }
                    continue;
                }
            }

            filterRejects = 0; // Reset on successful eviction
            removeInternal(keyHash, keyBytes, keyBytes.length, slot, EvictionCause.SIZE);
            attempts = 0;
            forceOnce = false;
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
        int keyHash = (isStringKey && key instanceof String s) ? s.hashCode() : keySerializer.hashCode(key);

        int slot = 0;
        if (offHeapGhostCache != null) {
            slot = offHeapGhostCache.getSlot(key, keyHash, entryPool, keySerializer);
        }

        byte[] keyBytes = null;
        int keyLen = 0;
        if (slot == 0) {
            if (useKeyMatchFastPath) {
                slot = hashTable.getWithKey(keyHash, key, keySerializer);
            } else {
                if (isLatin1Key && key instanceof String s) {
                    ThreadLocalKeyBuffer.BufferResult res = ThreadLocalKeyBuffer.encodeString(s);
                    keyBytes = res.buffer();
                    keyLen = res.length();
                } else {
                    keyBytes = keySerializer.serialize(key);
                    keyLen = keyBytes.length;
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
        int keyHash = (isStringKey && key instanceof String s) ? s.hashCode() : keySerializer.hashCode(key);

        int slot = 0;
        if (offHeapGhostCache != null) {
            slot = offHeapGhostCache.getSlot(key, keyHash, entryPool, keySerializer);
        }

        byte[] keyBytes = null;
        int keyLen = 0;
        if (slot == 0) {
            if (useKeyMatchFastPath) {
                slot = hashTable.getWithKey(keyHash, key, keySerializer);
            } else {
                if (isLatin1Key && key instanceof String s) {
                    ThreadLocalKeyBuffer.BufferResult res = ThreadLocalKeyBuffer.encodeString(s);
                    keyBytes = res.buffer();
                    keyLen = res.length();
                } else {
                    keyBytes = keySerializer.serialize(key);
                    keyLen = keyBytes.length;
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

        long offset = entryPool.getOffset(slot);
        if (offset == -1L)
            return null;

        int storedKeyLen = NativeMemory.UNLIMITED.get(java.lang.foreign.ValueLayout.JAVA_INT,
                offset + com.codeabbot.rmcache.index.EntryBlockLayout.HEADER_SIZE);
        long valueOffset = offset + com.codeabbot.rmcache.index.EntryBlockLayout.HEADER_SIZE + 4
                + com.codeabbot.rmcache.index.EntryBlockLayout.pad(storedKeyLen);
        int valueLen = NativeMemory.UNLIMITED.get(java.lang.foreign.ValueLayout.JAVA_INT, valueOffset);

        evictionPolicy.onAccess(slot, keyHash);
        if (hasOffHeapGhostCache)
            offHeapGhostCache.put(keyHash, slot);

        return new CacheValueViewImpl(entryPool, slot, valueOffset + 4, valueLen);
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
