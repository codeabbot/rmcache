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
package com.codeabbot.rmcache.eviction;

import com.codeabbot.rmcache.index.EntryPool;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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

    private final AtomicInteger entryCount = new AtomicInteger(0);
    private Runnable maintenanceCallback;

    // REG-1 fix: Pre-allocated shard batch arrays for drainBuffers().
    // Reusable because drainBuffers is single-threaded (maintenance executor).
    private final int[][] shardBatchSlots;
    private final int[] shardBatchSizes;

    // Round-robin counter for victim selection across shards
    private final AtomicInteger victimShardCounter = new AtomicInteger(0);
    private final AtomicBoolean closeOnce = new AtomicBoolean(false);

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

        // REG-1: Pre-allocate shard batch arrays (reused across drain calls)
        this.shardBatchSlots = new int[shardCount][bufferSize];
        this.shardBatchSizes = new int[shardCount];

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

        int stripe = (int) (Thread.currentThread().threadId() & stripeMask);
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
            entryCount.incrementAndGet();

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
            // H3 fix: only decrement entryCount if the slot was actually in an LRU list.
            // selectVictim() calls poll*() which clears the segment to NONE,
            // so if onRemove is called after selectVictim, remove() returns false
            // and we skip the decrement. For direct removes (not via eviction),
            // the segment is still set, so remove() returns true and we decrement.
            removed = shards[shard].remove(slot);
        } finally {
            shardLocks[shard].unlock();
        }
        if (removed) {
            entryCount.decrementAndGet();
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
                    int victim = admissionVictim(shard);
                    // H3 fix: decrement here since the poll already removed it from
                    // the list; onRemove sees segment=NONE and skips double-decrement.
                    entryCount.decrementAndGet();
                    return victim;
                }
                // A3 fix: evict the colder WINDOW before the hotter PROTECTED
                // segment (protected holds the most-reused entries — last resort).
                if (shards[shard].windowSize > 0) {
                    int victim = shards[shard].pollWindow();
                    entryCount.decrementAndGet();
                    return victim;
                }
                if (shards[shard].protectedSize > 0) {
                    int victim = shards[shard].pollProtected();
                    entryCount.decrementAndGet();
                    return victim;
                }
            } finally {
                shardLocks[shard].unlock();
            }
        }
        return 0;
    }

    /**
     * W-TinyLFU admission, evaluated lazily at eviction time so the get/put hot
     * path pays nothing. The probation MRU head is the most-recently-admitted
     * window graduate (the admission "candidate"); the LRU tail is the SLRU
     * "victim". If the candidate's estimated frequency is lower than the victim's,
     * the candidate is evicted instead — so a one-hit scan entry never displaces a
     * higher-frequency established entry. Runs under the shard lock on the
     * background eviction thread.
     *
     * <p>This is the classic {@code freq(candidate) > freq(victim)} decision, but
     * deferred from the insert path to the eviction path, which is where it is
     * free. Caller must hold {@code shardLocks[shard]}.
     */
    private int admissionVictim(int shard) {
        OffHeapCompactLRU s = shards[shard];
        if (entryPool == null || s.probationSize <= 1) {
            return s.pollProbation();
        }
        int tail = s.peekProbationTail(); // SLRU victim (oldest)
        int head = s.peekProbationHead(); // admission candidate (newest from window)
        if (head == tail || head == 0) {
            return s.pollProbation();
        }
        int tailFreq = frequencySketch.frequency(entryPool.getKeyHash(tail));
        int headFreq = frequencySketch.frequency(entryPool.getKeyHash(head));
        if (headFreq < tailFreq) {
            // Candidate colder than the victim → reject the candidate (scan resistance).
            s.removeProbation(head);
            return head;
        }
        // Candidate earns its place → evict the LRU victim as usual.
        s.removeProbation(tail);
        return tail;
    }

    @Override
    public boolean shouldEvict() {
        return entryCount.get() >= maxEntries;
    }

    @Override
    public void drainBuffers() {
        // REG-1 + P3-M2: True shard-batching with pre-allocated arrays.
        // Uses class fields instead of per-call allocation to eliminate GC pressure.

        // Reset batch sizes
        for (int s = 0; s < shardCount; s++) {
            shardBatchSizes[s] = 0;
        }

        // Pass 1: Partition all stripe buffers into per-shard batches
        for (int stripe = 0; stripe < numStripes; stripe++) {
            int count = Math.min(bufferIndices[stripe].getAndSet(0), bufferSize);
            if (count == 0)
                continue;

            int[] buf = buffers[stripe];
            for (int i = 0; i < count; i++) {
                int slot = buf[i];
                buf[i] = 0;
                if (slot == 0)
                    continue;
                int shard = shardFor(slot);
                int idx = shardBatchSizes[shard];
                if (idx < shardBatchSlots[shard].length) {
                    shardBatchSlots[shard][idx] = slot;
                    shardBatchSizes[shard] = idx + 1;
                }
            }
        }

        // Pass 2: Process each shard batch under a single lock acquisition
        for (int shard = 0; shard < shardCount; shard++) {
            int batchSize = shardBatchSizes[shard];
            if (batchSize == 0)
                continue;
            shardLocks[shard].lock();
            try {
                for (int i = 0; i < batchSize; i++) {
                    promote(shardBatchSlots[shard][i], shard);
                }
            } finally {
                shardLocks[shard].unlock();
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
        return entryCount.get();
    }

    @Override
    public int getMaxEntries() {
        return maxEntries;
    }

    @Override
    public void close() {
        if (!closeOnce.compareAndSet(false, true)) {
            return;
        }
        executor.shutdown();
        try {
            // P2 fix: never free native shards while the maintenance thread might
            // still be inside drainBuffers(). If it has not stopped within the grace
            // window, force shutdown and wait again before proceeding to free().
            if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                executor.awaitTermination(1, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
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
