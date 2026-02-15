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
import java.util.Collections;
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
        byte[] valueBuffer = new byte[256 * 1024];
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
        if (closed)
            throw new IllegalStateException("Closed");

        putInternal(key, value, ttl, priority);
    }

    private void putInternal(K key, V value, Duration ttl, short priority) {
        int keyHash = (isStringKey && key instanceof String s) ? s.hashCode() : keySerializer.hashCode(key);

        byte[] keyBytes;
        int keyLen;
        if (isLatin1Key && key instanceof String s) {
            ThreadLocalKeyBuffer.BufferResult res = ThreadLocalKeyBuffer.encodeString(s);
            keyBytes = res.buffer();
            keyLen = res.length();
        } else {
            keyBytes = keySerializer.serialize(key);
            keyLen = keyBytes.length;
        }

        // O5: Use cached serializer types instead of per-call instanceof
        SegmentValueSerializer<V> segSer = this.cachedSegSer;
        StreamingSerializer<V> streamSer = this.cachedStreamSer;
        int valueMaxLen = 0;
        byte[] valueBytes = null;
        int valueLen = 0;

        CacheContext ctx = context.get();
        if (segSer != null) {
            valueMaxLen = Math.max(0, segSer.estimateSize(value));
        } else if (streamSer != null) {
            int estimate = Math.max(0, streamSer.estimateSize(value));
            byte[] buf = ctx.valueBuffer;
            if (estimate > buf.length) {
                buf = new byte[estimate];
                ctx.valueBuffer = buf;
            }
            valueLen = streamSer.serializeTo(value, buf, 0);
            valueBytes = buf;
        } else if (isByteArrayValue && value instanceof byte[] b) {
            valueLen = b.length;
            valueBytes = b;
        } else {
            valueBytes = valueSerializer.serialize(value);
            valueLen = valueBytes.length;
        }

        long expiresAtMillis = (ttl != null) ? CoarseClock.getNow() + ttl.toMillis() : 0L;

        // O1: Use cached boolean instead of null check
        int existingSlot = 0;
        if (hasOffHeapGhostCache) {
            existingSlot = offHeapGhostCache.getSlot(key, keyHash, entryPool, keySerializer);
        }
        if (existingSlot == 0) {
            existingSlot = hashTable.getWithLen(keyHash, keyBytes, keyLen);
        }

        if (existingSlot != 0) {
            boolean updated = (segSer != null)
                    ? entryPool.updateValueWithSerializer(existingSlot, valueMaxLen, segSer, value)
                    : entryPool.updateValueWithLen(existingSlot, valueBytes, valueLen);

            if (updated) {
                evictionPolicy.onAccess(existingSlot, keyHash);
                // O6: Skip ghost cache bookkeeping on updates — slot already tracked
                return;
            }

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
                    return;
            }

            int oldSlotInTable = hashTable.putWithLen(keyHash, keyBytes, keyLen, newSlot);
            if (oldSlotInTable == -1) {
                entryPool.free(newSlot);
                return;
            } else if (oldSlotInTable != 0) {
                entryPool.free(oldSlotInTable);
                evictionPolicy.onAccess(newSlot, keyHash);
            } else {
                evictionPolicy.onAdd(newSlot, keyHash, priority);
            }

            if (hasGhostCache)
                ghostCache.put(key, keyHash, value, ttl != null ? ttl.toMillis() : 0);
            if (hasOffHeapGhostCache)
                offHeapGhostCache.put(keyHash, newSlot);
            return;
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
                return;
        }

        try {
            int oldSlot = hashTable.putWithLen(keyHash, keyBytes, keyLen, slot);
            if (oldSlot == -1) {
                entryPool.free(slot);
                evictIfNeeded(true);
                int retrySlot = (segSer != null)
                        ? entryPool.allocateWithSerializer(keyHash, keyBytes, keyLen, valueMaxLen, segSer, value,
                                priority, expiresAtMillis)
                        : entryPool.allocateWithLen(keyHash, keyBytes, keyLen, valueBytes, valueLen, priority,
                                expiresAtMillis);
                if (retrySlot == 0) {
                    return;
                }
                int retryOld = hashTable.putWithLen(keyHash, keyBytes, keyLen, retrySlot);
                if (retryOld == -1) {
                    entryPool.free(retrySlot);
                    return;
                } else if (retryOld != 0) {
                    entryPool.free(retrySlot);
                    evictionPolicy.onAccess(retryOld, keyHash);
                    if (hasOffHeapGhostCache)
                        offHeapGhostCache.put(keyHash, retryOld);
                } else {
                    evictionPolicy.onAdd(retrySlot, keyHash, priority);
                    if (hasOffHeapGhostCache)
                        offHeapGhostCache.put(keyHash, retrySlot);
                }
                return;
            } else if (oldSlot != 0) {
                entryPool.free(slot); // Race: someone else added it
                evictionPolicy.onAccess(oldSlot, keyHash);
                if (hasOffHeapGhostCache)
                    offHeapGhostCache.put(keyHash, oldSlot);
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
    }

    @Override
    public V get(K key) {
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

        int slot = 0;
        if (hasOffHeapGhostCache) {
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
        if (isByteArrayValue) {
            int valLen = entryPool.getValueLen(slot);
            byte[] vBuf = ctx.valueBuffer;
            if (valLen > vBuf.length) {
                vBuf = new byte[valLen];
                ctx.valueBuffer = vBuf;
            }
            entryPool.readValueToBuffer(slot, vBuf, 0, valLen);
            @SuppressWarnings("unchecked")
            V res = (V) Arrays.copyOf(vBuf, valLen);
            return res;
        }

        if (cachedStreamSer != null) {
            int valLen = entryPool.getValueLen(slot);
            byte[] vBuf = ctx.valueBuffer;
            if (valLen > vBuf.length) {
                vBuf = new byte[valLen];
                ctx.valueBuffer = vBuf;
            }
            entryPool.readValueToBuffer(slot, vBuf, 0, valLen);
            return cachedStreamSer.deserializeFrom(vBuf, 0, valLen);
        }

        byte[] valBytes = entryPool.readValue(slot);
        return valueSerializer.deserialize(valBytes);
    }

    @Override
    public boolean remove(K key) {
        checkNotClosed();
        int keyHash = (isStringKey && key instanceof String s) ? s.hashCode() : keySerializer.hashCode(key);
        byte[] keyBytes;
        int keyLen;
        if (isLatin1Key && key instanceof String s) {
            ThreadLocalKeyBuffer.BufferResult res = ThreadLocalKeyBuffer.encodeString(s);
            keyBytes = res.buffer();
            keyLen = res.length();
        } else {
            keyBytes = keySerializer.serialize(key);
            keyLen = keyBytes.length;
        }
        int slot = hashTable.getWithLen(keyHash, keyBytes, keyLen);
        if (slot == 0)
            return false;
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
        int keyHash = (isStringKey && key instanceof String s) ? s.hashCode() : keySerializer.hashCode(key);
        byte[] keyBytes;
        int keyLen;
        if (isLatin1Key && key instanceof String s) {
            ThreadLocalKeyBuffer.BufferResult res = ThreadLocalKeyBuffer.encodeString(s);
            keyBytes = res.buffer();
            keyLen = res.length();
        } else {
            keyBytes = keySerializer.serialize(key);
            keyLen = keyBytes.length;
        }

        // Check if key already exists
        int existingSlot = hashTable.getWithLen(keyHash, keyBytes, keyLen);
        if (existingSlot != 0) {
            // Key exists, don't overwrite
            return false;
        }

        // Key doesn't exist, perform put
        put(key, value, ttl, (short) 0);
        return true;
    }

    @Override
    public V computeIfAbsent(K key, java.util.function.Function<K, V> loader) {
        return computeIfAbsent(key, loader, null);
    }

    @Override
    public V computeIfAbsent(K key, java.util.function.Function<K, V> loader, Duration ttl) {
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

        // Try to insert (race condition possible, but acceptable)
        put(key, computed, ttl, (short) 0);
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
        return Collections.emptySet();
    }

    @Override
    public void clear() {
        if (ghostCache != null)
            ghostCache.invalidateAll();
        if (offHeapGhostCache != null)
            offHeapGhostCache.clear();
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
            if (evictionListener != null) {
                // Serialization of key for event?
                K key = keySerializer.deserialize(keyBytes);
                // Supplier is simple.
                java.util.function.Supplier<V> valueLazy = () -> {
                    byte[] vBytes = entryPool.readValue(removedSlot);
                    return valueSerializer.deserialize(vBytes);
                };
                evictionListener.onEviction(key, valueLazy, cause);
            }
            evictionPolicy.onRemove(removedSlot);
            entryPool.free(removedSlot);

            if (cause != EvictionCause.EXPLICIT)
                globalEvictions.increment();
        }
    }

    private void evictIfNeeded() {
        evictIfNeeded(false);
    }

    private void evictIfNeeded(boolean force) {
        int attempts = 0;
        boolean forceOnce = force;
        while (attempts < 100) {
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
                    attempts++;
                    continue;
                }
            }

            removeInternal(keyHash, keyBytes, keyBytes.length, slot, EvictionCause.SIZE);
            attempts = 0;
            forceOnce = false;
        }
    }

    @Override
    public <T> T getZeroCopy(K key, java.util.function.Function<MemorySegment, T> processor) {
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

    @Override
    public CacheValueView getView(K key) {
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
        if (evictionExecutor != null) {
            evictionExecutor.shutdown();
            try {
                evictionExecutor.awaitTermination(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        maintenanceExecutor.shutdown();
        try {
            maintenanceExecutor.awaitTermination(200, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
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
    }
}
