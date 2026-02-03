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
import com.codeabbot.rmcache.index.ValueWriter;
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
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.binder.BaseUnits;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
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
        // Sub-100ns optimization: thread-local stats
        long hits = 0;
        long misses = 0;
        int opCounter = 0; // Mask check trigger
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
    private final MeterRegistry meterRegistry;
    private final String cacheName;

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
            MeterRegistry meterRegistry,
            String cacheName,
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
        this.meterRegistry = meterRegistry;
        this.cacheName = cacheName;

        this.isStringKey = keySerializer instanceof StringKeySerializer;
        this.isLatin1Key = (keySerializer instanceof StringKeySerializer s) && s.isLatin1FastPath();
        this.isByteArrayValue = valueSerializer instanceof ByteArrayValueSerializer;

        this.backgroundEviction = backgroundEviction;
        this.backgroundEvictionIntervalMs = Math.max(1, backgroundEvictionIntervalMs);
        this.evictionHighWatermark = evictionHighWatermark;
        this.evictionLowWatermark = evictionLowWatermark;

        if (meterRegistry != null) {
            List<Tag> tags = List.of(Tag.of("cache", cacheName));

            FunctionCounter.builder("rmcache.hits", globalHits, LongAdder::sum)
                    .tags(tags)
                    .description("Number of cache hits")
                    .register(meterRegistry);

            FunctionCounter.builder("rmcache.misses", globalMisses, LongAdder::sum)
                    .tags(tags)
                    .description("Number of cache misses")
                    .register(meterRegistry);

            FunctionCounter.builder("rmcache.evictions", globalEvictions, LongAdder::sum)
                    .tags(tags)
                    .description("Number of cache evictions")
                    .register(meterRegistry);

            Gauge.builder("rmcache.size", hashTable, h -> (double) h.size())
                    .tags(tags)
                    .description("Current number of entries")
                    .register(meterRegistry);

            Gauge.builder("rmcache.memory.used", allocator, a -> (double) a.getUsedBytes())
                    .tags(tags)
                    .baseUnit(BaseUnits.BYTES)
                    .description("Off-heap memory used")
                    .register(meterRegistry);
        }

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

    private void flushStats(CacheContext ctx) {
        if (ctx.hits > 0) {
            globalHits.add(ctx.hits);
            ctx.hits = 0;
        }
        if (ctx.misses > 0) {
            globalMisses.add(ctx.misses);
            ctx.misses = 0;
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

        CacheContext ctx = context.get();
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

        SegmentValueSerializer<V> segSer =
                (valueSerializer instanceof SegmentValueSerializer) ? (SegmentValueSerializer<V>) valueSerializer
                        : null;
        StreamingSerializer<V> streamSer =
                (segSer == null && valueSerializer instanceof StreamingSerializer)
                        ? (StreamingSerializer<V>) valueSerializer
                        : null;
        ValueWriter writer = null;
        int valueMaxLen = 0;
        byte[] valueBytes = null;
        int valueLen = 0;

        if (segSer != null) {
            valueMaxLen = Math.max(0, segSer.estimateSize(value));
            writer = (segment, offset, maxLen) -> segSer.serializeTo(value, segment, offset, maxLen);
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

        int existingSlot = 0;
        if (offHeapGhostCache != null) {
            existingSlot = offHeapGhostCache.getSlot(key, keyHash, entryPool, keySerializer);
        }
        if (existingSlot == 0) {
            existingSlot = hashTable.getWithLen(keyHash, keyBytes, keyLen);
        }

        if (existingSlot != 0) {
            boolean updated = (writer != null)
                    ? entryPool.updateValueWithWriter(existingSlot, valueMaxLen, writer)
                    : entryPool.updateValueWithLen(existingSlot, valueBytes, valueLen);

            if (updated) {
                evictionPolicy.onAccess(existingSlot, keyHash);
                if (ghostCache != null)
                    ghostCache.put(key, keyHash, value, ttl != null ? ttl.toMillis() : 0);
                if (offHeapGhostCache != null)
                    offHeapGhostCache.put(keyHash, existingSlot);
                return;
            }

            int newSlot = (writer != null)
                    ? entryPool.allocateWithWriter(keyHash, keyBytes, keyLen, valueMaxLen, writer, priority,
                            expiresAtMillis)
                    : entryPool.allocateWithLen(keyHash, keyBytes, keyLen, valueBytes, valueLen, priority,
                            expiresAtMillis);
            if (newSlot == 0) {
                requestEviction(true);
                evictIfNeeded(true);
                newSlot = (writer != null)
                        ? entryPool.allocateWithWriter(keyHash, keyBytes, keyLen, valueMaxLen, writer, priority,
                                expiresAtMillis)
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

            if (ghostCache != null)
                ghostCache.put(key, keyHash, value, ttl != null ? ttl.toMillis() : 0);
            if (offHeapGhostCache != null)
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

        int slot = (writer != null)
                ? entryPool.allocateWithWriter(keyHash, keyBytes, keyLen, valueMaxLen, writer, priority,
                        expiresAtMillis)
                : entryPool.allocateWithLen(keyHash, keyBytes, keyLen, valueBytes, valueLen, priority,
                        expiresAtMillis);
        if (slot == 0) {
            requestEviction(true);
            evictIfNeeded(true);
            slot = (writer != null)
                    ? entryPool.allocateWithWriter(keyHash, keyBytes, keyLen, valueMaxLen, writer, priority,
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
                int retrySlot = (writer != null)
                        ? entryPool.allocateWithWriter(keyHash, keyBytes, keyLen, valueMaxLen, writer, priority,
                                expiresAtMillis)
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
                    if (offHeapGhostCache != null)
                        offHeapGhostCache.put(keyHash, retryOld);
                } else {
                    evictionPolicy.onAdd(retrySlot, keyHash, priority);
                    if (offHeapGhostCache != null)
                        offHeapGhostCache.put(keyHash, retrySlot);
                }
                return;
            } else if (oldSlot != 0) {
                entryPool.free(slot); // Race: someone else added it
                evictionPolicy.onAccess(oldSlot, keyHash);
                if (offHeapGhostCache != null)
                    offHeapGhostCache.put(keyHash, oldSlot);
            } else {
                evictionPolicy.onAdd(slot, keyHash, priority);
                if (offHeapGhostCache != null)
                    offHeapGhostCache.put(keyHash, slot);
            }
        } catch (Throwable e) {
            entryPool.free(slot);
            throw e;
        }

        if (ghostCache != null)
            ghostCache.put(key, keyHash, value, ttl != null ? ttl.toMillis() : 0);
    }

    @Override
    public V get(K key) {
        if (closed)
            return null;

        CacheContext ctx = context.get();

        int keyHash = (isStringKey && key instanceof String s) ? s.hashCode() : keySerializer.hashCode(key);

        if (ghostCache != null) {
            V ghostHit = ghostCache.get(key, keyHash);
            if (ghostHit != null) {
                ctx.hits++;
                if (((++ctx.opCounter) & 0x7F) == 0)
                    flushStats(ctx);
                return ghostHit;
            }
        }

        int slot = 0;
        if (offHeapGhostCache != null) {
            slot = offHeapGhostCache.getSlot(key, keyHash, entryPool, keySerializer);
        }

        byte[] keyBytes = null;
        int keyLen = 0;
        if (slot == 0) {
            if (isLatin1Key && key instanceof String s) {
                ThreadLocalKeyBuffer.BufferResult res = ThreadLocalKeyBuffer.encodeString(s);
                keyBytes = res.buffer();
                keyLen = res.length();
            } else {
                keyBytes = keySerializer.serialize(key);
                keyLen = keyBytes.length;
            }

            slot = hashTable.getWithLen(keyHash, keyBytes, keyLen);
            if (slot == 0) {
                ctx.misses++;
                if (((++ctx.opCounter) & 0x7F) == 0)
                    flushStats(ctx);
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
            ctx.misses++;
            if (((++ctx.opCounter) & 0x7F) == 0)
                flushStats(ctx);
            return null;
        }

        ctx.hits++;
        if (((++ctx.opCounter) & 0x7F) == 0)
            flushStats(ctx);
        evictionPolicy.onAccess(slot, keyHash);

        V result = readValueFromSlot(slot, ctx);

        if (ghostCache != null)
            ghostCache.put(key, keyHash, result);
        if (offHeapGhostCache != null)
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

        if (valueSerializer instanceof StreamingSerializer) {
            StreamingSerializer<V> sSer = (StreamingSerializer<V>) valueSerializer;
            int valLen = entryPool.getValueLen(slot);
            byte[] vBuf = ctx.valueBuffer;
            if (valLen > vBuf.length) {
                vBuf = new byte[valLen];
                ctx.valueBuffer = vBuf;
            }
            entryPool.readValueToBuffer(slot, vBuf, 0, valLen);
            return sSer.deserializeFrom(vBuf, 0, valLen);
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
                // Lazy value loading logic... for java we might just load it?
                // Kotlin used lazy. Let's load eagerly or create supplier.
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

        // Ported offset calculation
        int storedKeyLen = NativeMemory.UNLIMITED.get(java.lang.foreign.ValueLayout.JAVA_INT, offset + 24);
        long valueOffset = offset + 24 + 4 + com.codeabbot.rmcache.index.EntryBlockLayout.pad(storedKeyLen);
        int valueLen = NativeMemory.UNLIMITED.get(java.lang.foreign.ValueLayout.JAVA_INT, valueOffset);

        evictionPolicy.onAccess(slot, keyHash);
        if (offHeapGhostCache != null)
            offHeapGhostCache.put(keyHash, slot);

        return new CacheValueViewImpl(entryPool, slot, valueOffset + 4, valueLen);
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
        CacheContext ctx = context.get();
        return new CacheStats(
                globalHits.sum() + ctx.hits,
                globalMisses.sum() + ctx.misses,
                globalEvictions.sum(),
                size(),
                allocator.getUsedBytes(),
                allocator.getTotalBytes());
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
