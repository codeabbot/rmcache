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
import com.codeabbot.rmcache.util.CoarseClock;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Time-to-Live (TTL) eviction policy — zero on-heap design.
 *
 * <p>
 * All expiration metadata lives in two places:
 * <ol>
 * <li>The entry block itself (offset +4, written by {@link EntryPool}) — source
 * of truth.
 * <li>An {@link OffHeapTimingWheel} — sharded off-heap binary min-heap of slot
 * IDs,
 * ordered by expiration time. Reads expiration directly from the entry block.
 * </ol>
 *
 * <p>
 * The previous {@code ConcurrentHashMap<Integer,Long>} and
 * {@code PriorityBlockingQueue<Entry>} have been removed entirely.
 * Memory cost drops from ~128 bytes/entry (heap) to 4 bytes/entry (native).
 */
public class TTLPolicy implements EvictionPolicy {

    @SuppressWarnings("FieldCanBeLocal")
    private final long defaultTTLMs; // retained for API/logging purposes
    private final int maxEntries;
    private final OffHeapTimingWheel wheel;
    private final AtomicInteger entryCount = new AtomicInteger(0);

    /**
     * @param defaultTTLMs default TTL in milliseconds
     * @param maxEntries   maximum number of tracked entries
     * @param entryPool    entry pool used by the wheel to read expiration times
     */
    public TTLPolicy(long defaultTTLMs, int maxEntries, EntryPool entryPool) {
        this.defaultTTLMs = defaultTTLMs;
        this.maxEntries = maxEntries;
        this.wheel = new OffHeapTimingWheel(entryPool, maxEntries);
    }

    /**
     * Convenience constructor — uses {@code Integer.MAX_VALUE} as max entries.
     * The wheel will grow dynamically.
     */
    public TTLPolicy(long defaultTTLMs, EntryPool entryPool) {
        this(defaultTTLMs, Integer.MAX_VALUE, entryPool);
    }

    /**
     * Legacy constructor for tests that do not have an EntryPool.
     * Uses a no-op wheel (expiration is checked via
     * {@link EntryPool#isExpired(int)}
     * on the hot path anyway; the wheel is only needed for proactive eviction).
     *
     * @deprecated Prefer {@link #TTLPolicy(long, EntryPool)}.
     */
    @Deprecated
    public TTLPolicy(long defaultTTLMs) {
        this.defaultTTLMs = defaultTTLMs;
        this.maxEntries = Integer.MAX_VALUE;
        this.wheel = null; // no-op mode
    }

    // ── EvictionPolicy ────────────────────────────────────────────────────────

    @Override
    public int size() {
        return entryCount.get();
    }

    @Override
    public int getMaxEntries() {
        return maxEntries;
    }

    @Override
    public void onAccess(int slot, int keyHash) {
        // TTL is not refreshed on access (strict TTL semantics)
    }

    @Override
    public void onAdd(int slot, int keyHash, short priority) {
        // The expiration is already written into the entry block by OffHeapCacheImpl
        // before onAdd is called. We just register the slot in the wheel.
        if (wheel != null) {
            wheel.schedule(slot);
        }
        entryCount.incrementAndGet();
    }

    /**
     * Add with a custom per-entry TTL (overrides the default).
     * The caller is responsible for writing the correct {@code expiresAt} into
     * the entry block before calling this method.
     */
    public void onAddWithTTL(int slot, int keyHash, long ttlMs) {
        // expiresAt is already written into the entry block by the caller
        if (wheel != null) {
            wheel.schedule(slot);
        }
        entryCount.incrementAndGet();
    }

    @Override
    public void onRemove(int slot) {
        if (wheel != null) {
            wheel.cancel(slot);
        }
        // P3-C2 + REG-2 fix: Clamp to prevent negative entryCount (P3-C2),
        // using manual CAS instead of updateAndGet(lambda) (REG-2).
        // updateAndGet allocates a lambda capture + uses CAS retry;
        // this manual loop skips entirely when already at zero.
        int prev;
        do {
            prev = entryCount.get();
            if (prev <= 0)
                return;
        } while (!entryCount.compareAndSet(prev, prev - 1));
    }

    @Override
    public int selectVictim() {
        if (wheel == null)
            return 0;
        return wheel.pollExpiredOne();
    }

    @Override
    public boolean shouldEvict() {
        if (wheel != null && wheel.hasExpired())
            return true;
        return entryCount.get() >= maxEntries;
    }

    @Override
    public long getDefaultTTLMs() {
        return defaultTTLMs;
    }

    @Override
    public boolean tracksTTL() {
        return wheel != null;
    }

    @Override
    public void onTTLUpdate(int slot) {
        // AUDIT-A5: add a fresh heap entry for this slot. Duplicates are harmless
        // because pollExpiredOne drains cancelled/freed slots naturally (the
        // second occurrence hits expiresAt<=0 after the first eviction).
        if (wheel != null) {
            wheel.schedule(slot);
        }
    }

    /**
     * Check if a slot is expired by reading directly from the entry block.
     * This is O(1) and involves no heap allocation.
     */
    public boolean isExpired(int slot) {
        // Delegate to EntryPool via the wheel's entryPool reference,
        // but TTLPolicy itself doesn't hold an EntryPool reference —
        // OffHeapCacheImpl calls entryPool.isExpired(slot) directly on the hot path.
        // This method is kept for test compatibility only.
        if (wheel == null)
            return false;
        long exp = wheel.getEntryPool().getExpiresAt(slot);
        return exp > 0 && CoarseClock.getNow() >= exp;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * M1 fix: Compact the timing wheel by removing lazily-cancelled entries.
     * Prevents unbounded native heap growth in long-running caches with TTL churn.
     */
    @Override
    public void compact() {
        if (wheel != null)
            wheel.compact();
    }

    @Override
    public void close() {
        if (wheel != null)
            wheel.close();
    }
}
