package com.codeabbot.rmcache.eviction;

import com.codeabbot.rmcache.index.EntryPool;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * High-performance SLRU + TinyLFU policy with Async Maintenance.
 *
 * @author Rabindra Meher
 */
public class LRUPolicy implements EvictionPolicy, AutoCloseable {

    private final int maxEntries;
    private EntryPool entryPool;

    private final int windowSize;
    private final int protectedSize;
    private final int probationSize;

    private OffHeapCompactLRU lru;
    private OffHeapFrequencySketch frequencySketch;
    private final ReentrantLock lock = new ReentrantLock();

    private final int numStripes;
    private final int bufferSize = 1024;
    private final int[][] buffers;
    private final AtomicInteger[] bufferIndices;

    private final AtomicInteger _size = new AtomicInteger(0);
    private Runnable maintenanceCallback;

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

        this.lru = new OffHeapCompactLRU(maxSlots);
        this.frequencySketch = new OffHeapFrequencySketch(maxSlots);

        this.numStripes = Math.max(Runtime.getRuntime().availableProcessors() * 4, 16);
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

    @Override
    public void setEntryPool(EntryPool pool) {
        this.entryPool = pool;
        if (pool.slotCapacity() > lru.capacity) {
            int newCap = pool.slotCapacity();
            // Free old before allocating new to save space
            lru.close();
            frequencySketch.close();

            this.lru = new OffHeapCompactLRU(newCap);
            this.frequencySketch = new OffHeapFrequencySketch(newCap);
        }
    }

    @Override
    public void onAccess(int slot, int keyHash) {
        if (slot == 0)
            return;

        frequencySketch.increment(keyHash);

        int stripe = (int) (Thread.currentThread().getId() % numStripes);
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
        lock.lock();
        try {
            lru.addToWindow(slot);
            _size.incrementAndGet();

            if (lru.windowSize > windowSize) {
                int victim = lru.pollWindow();
                if (victim != 0) {
                    lru.addToProbation(victim);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void onRemove(int slot) {
        lock.lock();
        try {
            if (lru.remove(slot)) {
                _size.decrementAndGet();
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int selectVictim() {
        lock.lock();
        try {
            if (lru.probationSize > 0) {
                int victim = lru.pollProbation();
                _size.decrementAndGet();
                return victim;
            }
            if (lru.protectedSize > 0) {
                int victim = lru.pollProtected();
                _size.decrementAndGet();
                return victim;
            }
            if (lru.windowSize > 0) {
                int victim = lru.pollWindow();
                _size.decrementAndGet();
                return victim;
            }
            return 0;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean shouldEvict() {
        return _size.get() >= maxEntries;
    }

    @Override
    public void drainBuffers() {
        if (lock.tryLock()) {
            try {
                for (int stripe = 0; stripe < numStripes; stripe++) {
                    int count = Math.min(bufferIndices[stripe].getAndSet(0), bufferSize);
                    for (int i = 0; i < count; i++) {
                        int slot = buffers[stripe][i];
                        if (slot != 0)
                            promote(slot);
                        buffers[stripe][i] = 0;
                    }
                }
            } finally {
                lock.unlock();
            }
        }

        if (maintenanceCallback != null) {
            maintenanceCallback.run();
        }
    }

    private void promote(int slot) {
        int segment = lru.getSegment(slot);
        switch (segment) {
            case OffHeapCompactLRU.PROBATION -> {
                lru.removeProbation(slot);
                lru.addToProtected(slot);

                if (lru.protectedSize > protectedSize) {
                    int demoted = lru.pollProtected();
                    if (demoted != 0)
                        lru.addToProbation(demoted);
                }
            }
            case OffHeapCompactLRU.PROTECTED -> lru.moveToHead(slot, OffHeapCompactLRU.PROTECTED);
            case OffHeapCompactLRU.WINDOW -> lru.moveToHead(slot, OffHeapCompactLRU.WINDOW);
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
        // Free native memory
        if (lru != null)
            lru.close();
        if (frequencySketch != null)
            frequencySketch.close();
    }
}
