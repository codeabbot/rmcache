package com.codeabbot.rmcache.eviction;

import com.codeabbot.rmcache.util.CoarseClock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.PriorityBlockingQueue;

/**
 * Hierarchical timing wheel for O(1) timeout scheduling.
 * Simplified as a PriorityBlockingQueue for this implementation.
 */
class TimingWheel {
    private static class Entry implements Comparable<Entry> {
        final int slot;
        final long expiresAtMs;

        Entry(int slot, long expiresAtMs) {
            this.slot = slot;
            this.expiresAtMs = expiresAtMs;
        }

        @Override
        public int compareTo(Entry other) {
            return Long.compare(this.expiresAtMs, other.expiresAtMs);
        }
    }

    private final PriorityBlockingQueue<Entry> queue = new PriorityBlockingQueue<>();

    public void schedule(int slot, long expiresAtMs) {
        queue.offer(new Entry(slot, expiresAtMs));
    }

    public boolean hasExpired() {
        Entry head = queue.peek();
        return head != null && CoarseClock.getNow() >= head.expiresAtMs;
    }

    public List<Integer> pollExpired(int maxCount) {
        long now = CoarseClock.getNow();
        List<Integer> result = new ArrayList<>();
        while (result.size() < maxCount) {
            Entry entry = queue.peek();
            if (entry == null || entry.expiresAtMs > now)
                break;
            queue.poll();
            result.add(entry.slot);
        }
        return result;
    }
}
