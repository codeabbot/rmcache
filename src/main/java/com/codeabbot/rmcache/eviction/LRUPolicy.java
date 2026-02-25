package com.codeabbot.rmcache.eviction;

import com.codeabbot.rmcache.index.EntryPool;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * High-performance SLRU + TinyLFU policy with Async Maintenance
 * and Striped LRU Locking for reduced contention at scale.
 *
 * @author Rabindra Meher
 */
public class LRUPolicy implements EvictionPolicy, AutoCloseable {

    private final int maxEntries;
    private EntryPool entryPool;

    private final int windowSize;
    private final int protectedSize;
    private final int probationSize;

    // Striped LRU: N shards, each with its own lock and LRU lists
    private final int shardCount;
    private final int shardMask;
    private OffHeapCompactLRU[] shards;
    private final ReentrantLock[] shardLocks;
    private final int windowPerShard;
    private final int protectedPerShard;

    private OffHeapFrequencySketch frequencySketch;

    // Async access buffers (unchanged from before)
    private final int numStripes;
    private final int stripeMask;
    private final int bufferSize = 4096;
    private final int[][] buffers;
    private final AtomicInteger[] bufferIndices;

    private final AtomicInteger _size = new AtomicInteger(0);
    private Runnable maintenanceCallback;

    // Round-robin counter for victim selection across shards
    private final AtomicInteger victimShardCounter = new AtomicInteger(0);

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "rmcache-lru-maintenance");
        t.setDaemon(true);
        return t;
    });

    public LRUPolicy(int maxEntries, int maxSlots, float windowPercent, float protectedPercent) {
        this.maxEntries = maxEntries;
        this.windowSize = Math.max((int) (maxEntries * windowPercent), 1);
        this.protectedSize = (int) (maxEntries * protectedPercent);
        this.probationSize = maxEntries - windowSize - protectedSize;

        // Determine shard count: power of 2, between 4 and 64
        int rawShards = Math.max(Runtime.getRuntime().availableProcessors(), 4);
        this.shardCount = Math.min(Integer.highestOneBit(rawShards), 64);
        this.shardMask = this.shardCount - 1;

        // Per-shard size caps (distribute evenly, rounding up for safety)
        this.windowPerShard = Math.max((windowSize + shardCount - 1) / shardCount, 1);
        this.protectedPerShard = Math.max((protectedSize + shardCount - 1) / shardCount, 1);

        // Initialize shards - each shard handles maxSlots (slots are sparse)
        this.shards = new OffHeapCompactLRU[shardCount];
        this.shardLocks = new ReentrantLock[shardCount];
        for (int i = 0; i < shardCount; i++) {
            shards[i] = new OffHeapCompactLRU(maxSlots);
            shardLocks[i] = new ReentrantLock();
        }

        this.frequencySketch = new OffHeapFrequencySketch(maxSlots);

        int rawStripes = Math.max(Runtime.getRuntime().availableProcessors() * 4, 16);
        this.numStripes = Integer.highestOneBit(rawStripes);
        this.stripeMask = this.numStripes - 1;
        this.buffers = new int[numStripes][bufferSize];
        this.bufferIndices = new AtomicInteger[numStripes];
        for (int i = 0; i < numStripes; i++) {
            bufferIndices[i] = new AtomicInteger(0);
        }

        executor.scheduleWithFixedDelay(this::drainBuffers, 10, 10, TimeUnit.MILLISECONDS);
    }

    public LRUPolicy(int maxEntries) {
        this(maxEntries, maxEntries, 0.01f, 0.80f);
    }

    public void setMaintenanceCallback(Runnable callback) {
        this.maintenanceCallback = callback;
    }

    private int shardFor(int slot) {
        return slot & shardMask;
    }

    @Override
    public void setEntryPool(EntryPool pool) {
        this.entryPool = pool;
        if (pool.slotCapacity() > shards[0].capacity) {
            int newCap = pool.slotCapacity();
            // Free old before allocating new
            for (int i = 0; i < shardCount; i++) {
                shards[i].close();
                shards[i] = new OffHeapCompactLRU(newCap);
            }
            frequencySketch.close();
            this.frequencySketch = new OffHeapFrequencySketch(newCap);
        }
    }

    @Override
    public void onAccess(int slot, int keyHash) {
        if (slot == 0)
            return;

        frequencySketch.increment(keyHash);

        int stripe = (int) (Thread.currentThread().getId() & stripeMask);
        int idx = bufferIndices[stripe].getAndIncrement();
        if (idx < bufferSize) {
            buffers[stripe][idx] = slot;
        } else {
            bufferIndices[stripe].set(bufferSize);
        }
    }

    @Override
    public void onAdd(int slot, int keyHash, short priority) {
        frequencySketch.increment(keyHash);
        int shard = shardFor(slot);
        shardLocks[shard].lock();
        try {
            shards[shard].addToWindow(slot);
            _size.incrementAndGet();

            if (shards[shard].windowSize > windowPerShard) {
                int victim = shards[shard].pollWindow();
                if (victim != 0) {
                    shards[shard].addToProbation(victim);
                }
            }
        } finally {
            shardLocks[shard].unlock();
        }
    }

    @Override
    public void onRemove(int slot) {
        int shard = shardFor(slot);
        boolean removed;
        shardLocks[shard].lock();
        try {
            // H3 fix: only decrement _size if the slot was actually in an LRU list.
            // selectVictim() calls poll*() which clears the segment to NONE,
            // so if onRemove is called after selectVictim, remove() returns false
            // and we skip the decrement. For direct removes (not via eviction),
            // the segment is still set, so remove() returns true and we decrement.
            removed = shards[shard].remove(slot);
        } finally {
            shardLocks[shard].unlock();
        }
        if (removed) {
            _size.decrementAndGet();
        }
    }

    @Override
    public int selectVictim() {
        // Round-robin across shards to find a victim.
        int startShard = victimShardCounter.getAndIncrement() & shardMask;
        for (int i = 0; i < shardCount; i++) {
            int shard = (startShard + i) & shardMask;
            shardLocks[shard].lock();
            try {
                if (shards[shard].probationSize > 0) {
                    int victim = shards[shard].pollProbation();
                    // H3 fix: decrement here since poll already removed from list;
                    // onRemove will see segment=NONE and skip double-decrement.
                    _size.decrementAndGet();
                    return victim;
                }
                if (shards[shard].protectedSize > 0) {
                    int victim = shards[shard].pollProtected();
                    _size.decrementAndGet();
                    return victim;
                }
                if (shards[shard].windowSize > 0) {
                    int victim = shards[shard].pollWindow();
                    _size.decrementAndGet();
                    return victim;
                }
            } finally {
                shardLocks[shard].unlock();
            }
        }
        return 0;
    }

    @Override
    public boolean shouldEvict() {
        return _size.get() >= maxEntries;
    }

    @Override
    public void drainBuffers() {
        for (int stripe = 0; stripe < numStripes; stripe++) {
            int count = Math.min(bufferIndices[stripe].getAndSet(0), bufferSize);
            if (count == 0)
                continue;

            for (int i = 0; i < count; i++) {
                int slot = buffers[stripe][i];
                if (slot != 0) {
                    int shard = shardFor(slot);
                    shardLocks[shard].lock();
                    try {
                        promote(slot, shard);
                    } finally {
                        shardLocks[shard].unlock();
                    }
                }
                buffers[stripe][i] = 0;
            }
        }

        if (maintenanceCallback != null) {
            maintenanceCallback.run();
        }
    }

    private void promote(int slot, int shard) {
        int segment = shards[shard].getSegment(slot);
        switch (segment) {
            case OffHeapCompactLRU.PROBATION -> {
                shards[shard].removeProbation(slot);
                shards[shard].addToProtected(slot);

                if (shards[shard].protectedSize > protectedPerShard) {
                    int demoted = shards[shard].pollProtected();
                    if (demoted != 0)
                        shards[shard].addToProbation(demoted);
                }
            }
            case OffHeapCompactLRU.PROTECTED -> shards[shard].moveToHead(slot, OffHeapCompactLRU.PROTECTED);
            case OffHeapCompactLRU.WINDOW -> shards[shard].moveToHead(slot, OffHeapCompactLRU.WINDOW);
        }
    }

    @Override
    public int size() {
        return _size.get();
    }

    @Override
    public int getMaxEntries() {
        return maxEntries;
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            executor.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (int i = 0; i < shardCount; i++) {
            if (shards[i] != null)
                shards[i].close();
        }
        if (frequencySketch != null)
            frequencySketch.close();
    }
}
