package com.codeabbot.rmcache.eviction;

import com.codeabbot.rmcache.index.EntryPool;
import com.codeabbot.rmcache.memory.NativeMemory;
import com.codeabbot.rmcache.util.CoarseClock;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Zero-heap TTL scheduling using a sharded off-heap binary min-heap.
 *
 * <p>
 * Each stripe owns a native {@code int[]} array of slot IDs ordered by
 * expiration time. Expiration values are read directly from the
 * {@link EntryPool}
 * (offset +4 in each entry block), so <em>no expiration data is duplicated on
 * the
 * Java heap</em>.
 *
 * <p>
 * Memory cost: {@code 4 bytes × maxEntries} in native memory (vs ~128
 * bytes/entry
 * with the previous {@code PriorityBlockingQueue<Entry>} approach).
 *
 * <p>
 * Concurrency: {@code numStripes} independent heaps, each guarded by its own
 * {@link ReentrantLock}. Stripe is chosen by {@code slot & stripeMask}.
 */
public final class OffHeapTimingWheel implements AutoCloseable {

    // ── Stripe state ──────────────────────────────────────────────────────────

    private final int numStripes;
    private final int stripeMask;

    /** Per-stripe native int[] heap (slot IDs). */
    private final MemorySegment[] heaps;
    /** Per-stripe current heap size (number of valid slots). */
    private final int[] sizes;
    /** Per-stripe capacity (number of int slots allocated). */
    private final int[] capacities;
    /** Per-stripe lock. */
    private final ReentrantLock[] locks;

    // ── Shared reference ──────────────────────────────────────────────────────

    private final EntryPool entryPool;

    // ── Constants ─────────────────────────────────────────────────────────────

    private static final int INT_BYTES = 4;
    private static final int INITIAL_CAPACITY_PER_STRIPE = 256;

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * @param entryPool  the entry pool used to read expiration times
     * @param maxEntries total expected entries (used to size stripes)
     * @param numStripes number of independent heap shards (must be power-of-2)
     */
    public OffHeapTimingWheel(EntryPool entryPool, int maxEntries, int numStripes) {
        // Round numStripes to power-of-2
        int s = 1;
        while (s < numStripes)
            s <<= 1;
        this.numStripes = s;
        this.stripeMask = s - 1;
        this.entryPool = entryPool;

        int perStripe = Math.max(INITIAL_CAPACITY_PER_STRIPE,
                nextPowerOfTwo((maxEntries + s - 1) / s));

        this.heaps = new MemorySegment[s];
        this.sizes = new int[s];
        this.capacities = new int[s];
        this.locks = new ReentrantLock[s];

        for (int i = 0; i < s; i++) {
            heaps[i] = NativeMemory.malloc((long) perStripe * INT_BYTES);
            sizes[i] = 0;
            capacities[i] = perStripe;
            locks[i] = new ReentrantLock();
        }
    }

    /** Convenience constructor: 16 stripes. */
    public OffHeapTimingWheel(EntryPool entryPool, int maxEntries) {
        this(entryPool, maxEntries, 16);
    }

    /** Returns the EntryPool used for expiration lookups. */
    public EntryPool getEntryPool() {
        return entryPool;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Schedule {@code slot} for expiry. O(log N) per stripe.
     */
    public void schedule(int slot) {
        int stripe = slot & stripeMask;
        ReentrantLock lock = locks[stripe];
        lock.lock();
        try {
            ensureCapacity(stripe);
            int pos = sizes[stripe]++;
            setSlot(stripe, pos, slot);
            siftUp(stripe, pos);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns {@code true} if any stripe has an expired entry at its head.
     */
    public boolean hasExpired() {
        long now = CoarseClock.getNow();
        for (int i = 0; i < numStripes; i++) {
            ReentrantLock lock = locks[i];
            lock.lock();
            try {
                if (sizes[i] > 0) {
                    int head = getSlot(i, 0);
                    long exp = entryPool.getExpiresAt(head);
                    if (exp > 0 && now >= exp)
                        return true;
                }
            } finally {
                lock.unlock();
            }
        }
        return false;
    }

    /**
     * Poll up to {@code maxCount} expired slot IDs across all stripes.
     * Returns the first expired slot found, or {@code 0} if none.
     * Lazily-cancelled entries (exp <= 0) at the heap head are drained.
     */
    public int pollExpiredOne() {
        long now = CoarseClock.getNow();
        for (int i = 0; i < numStripes; i++) {
            ReentrantLock lock = locks[i];
            lock.lock();
            try {
                // Drain lazily-cancelled entries from the top of the heap
                while (sizes[i] > 0) {
                    int head = getSlot(i, 0);
                    long exp = entryPool.getExpiresAt(head);
                    if (exp <= 0) {
                        // Lazy-cancelled entry — discard from heap
                        removeTop(i);
                        continue;
                    }
                    if (now >= exp) {
                        removeTop(i);
                        return head;
                    }
                    break; // Head is not expired yet
                }
            } finally {
                lock.unlock();
            }
        }
        return 0;
    }

    /**
     * Cancel a slot's TTL by marking it as never-expiring.
     * Uses lazy deletion — the slot remains in the heap but will be
     * skipped during {@link #pollExpiredOne()} since its expiration
     * is cleared. This is O(1) instead of the previous O(N) scan.
     */
    public void cancel(int slot) {
        // Clear the expiration so the entry is treated as non-expiring.
        // pollExpiredOne() already handles this via expiresAt() returning
        // Long.MAX_VALUE for exp <= 0.
        entryPool.clearExpiresAt(slot);
    }

    @Override
    public void close() {
        for (int i = 0; i < numStripes; i++) {
            if (heaps[i] != null) {
                NativeMemory.free(heaps[i]);
                heaps[i] = null;
            }
        }
    }

    /**
     * H1 fix: Compact all stripes by removing lazily-cancelled entries (exp ≤ 0).
     * This reclaims native heap memory that would otherwise grow unboundedly
     * in long-running caches with high churn. Should be called periodically
     * from a maintenance thread (e.g., every 60s).
     */
    public void compact() {
        for (int i = 0; i < numStripes; i++) {
            locks[i].lock();
            try {
                compactStripe(i);
            } finally {
                locks[i].unlock();
            }
        }
    }

    /** Rebuild stripe heap in place, dropping entries with exp ≤ 0. */
    private void compactStripe(int stripe) {
        int n = sizes[stripe];
        int write = 0;
        for (int read = 0; read < n; read++) {
            int slot = getSlot(stripe, read);
            long exp = entryPool.getExpiresAt(slot);
            if (exp > 0) {
                if (write != read) {
                    setSlot(stripe, write, slot);
                }
                write++;
            }
        }
        sizes[stripe] = write;
        // Re-heapify from scratch (O(n), faster than n sift operations)
        for (int i = (write >>> 1) - 1; i >= 0; i--) {
            siftDown(stripe, i);
        }
    }

    // ── Heap operations (caller must hold stripe lock) ────────────────────────

    private void siftUp(int stripe, int pos) {
        while (pos > 0) {
            int parent = (pos - 1) >>> 1;
            if (expiresAt(stripe, parent) <= expiresAt(stripe, pos))
                break;
            swap(stripe, parent, pos);
            pos = parent;
        }
    }

    private void siftDown(int stripe, int pos) {
        int n = sizes[stripe];
        while (true) {
            int left = (pos << 1) + 1;
            int right = left + 1;
            int smallest = pos;

            if (left < n && expiresAt(stripe, left) < expiresAt(stripe, smallest))
                smallest = left;
            if (right < n && expiresAt(stripe, right) < expiresAt(stripe, smallest))
                smallest = right;

            if (smallest == pos)
                break;
            swap(stripe, pos, smallest);
            pos = smallest;
        }
    }

    /** Remove the root (minimum expiry) and re-heapify. Caller holds lock. */
    private void removeTop(int stripe) {
        int n = sizes[stripe];
        if (n == 0)
            return;
        int last = getSlot(stripe, n - 1);
        setSlot(stripe, 0, last);
        sizes[stripe]--;
        if (sizes[stripe] > 0)
            siftDown(stripe, 0);
    }

    // ── Native memory helpers ─────────────────────────────────────────────────

    private int getSlot(int stripe, int idx) {
        return NativeMemory.UNLIMITED.get(ValueLayout.JAVA_INT,
                heaps[stripe].address() + (long) idx * INT_BYTES);
    }

    private void setSlot(int stripe, int idx, int slot) {
        NativeMemory.UNLIMITED.set(ValueLayout.JAVA_INT,
                heaps[stripe].address() + (long) idx * INT_BYTES, slot);
    }

    private void swap(int stripe, int a, int b) {
        int sa = getSlot(stripe, a);
        int sb = getSlot(stripe, b);
        setSlot(stripe, a, sb);
        setSlot(stripe, b, sa);
    }

    /** Read expiration for the slot at heap position {@code idx}. */
    private long expiresAt(int stripe, int idx) {
        int slot = getSlot(stripe, idx);
        long exp = entryPool.getExpiresAt(slot);
        return exp > 0 ? exp : Long.MAX_VALUE; // treat no-TTL as never-expiring
    }

    // ── Growth ────────────────────────────────────────────────────────────────

    private void ensureCapacity(int stripe) {
        if (sizes[stripe] < capacities[stripe])
            return;
        int newCap = capacities[stripe] * 2;
        MemorySegment newSeg = NativeMemory.malloc((long) newCap * INT_BYTES);
        // Copy existing data
        MemorySegment.copy(heaps[stripe], 0, newSeg, 0, (long) capacities[stripe] * INT_BYTES);
        NativeMemory.free(heaps[stripe]);
        heaps[stripe] = newSeg;
        capacities[stripe] = newCap;
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private static int nextPowerOfTwo(int n) {
        if (n <= 1)
            return 1;
        int p = 1;
        while (p < n)
            p <<= 1;
        return p;
    }
}
