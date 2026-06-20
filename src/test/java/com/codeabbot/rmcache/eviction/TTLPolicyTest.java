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

import com.codeabbot.rmcache.CacheBuilder;
import com.codeabbot.rmcache.GhostCacheMode;
import com.codeabbot.rmcache.OffHeapCache;
import com.codeabbot.rmcache.index.EntryPool;
import com.codeabbot.rmcache.memory.SlabAllocator;
import com.codeabbot.rmcache.util.CoarseClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TTLPolicy using a real EntryPool so expiration reads work off-heap.
 */
@SuppressWarnings("deprecation") // intentionally exercises the test-only @Deprecated TTLPolicy(long) ctor
public class TTLPolicyTest {

    private static final long MEMORY = 32 * 1024 * 1024L; // 32 MB
    private static final int MAX = 1024;

    private SlabAllocator allocator;
    private EntryPool entryPool;

    @BeforeEach
    void setUp() {
        CoarseClock.acquire(); // H6: clock no longer auto-starts
        allocator = new SlabAllocator(MEMORY);
        entryPool = new EntryPool(allocator, MAX);
    }

    @AfterEach
    void tearDown() {
        entryPool.close();
        allocator.close();
        CoarseClock.release(); // H6: stop clock when done
    }

    /** Allocate a real entry with the given TTL and return its slot. */
    private int allocateEntry(int keyHash, long ttlMs) {
        byte[] key = new byte[] { (byte) (keyHash & 0xFF) };
        byte[] value = new byte[] { 1, 2, 3 };
        long expiresAt = ttlMs > 0 ? CoarseClock.getNow() + ttlMs : 0L;
        return entryPool.allocate(keyHash, key, value, (short) 0, expiresAt);
    }

    @Test
    public void entriesWithTTLAreTracked() {
        TTLPolicy policy = new TTLPolicy(60_000L, entryPool);

        int s0 = allocateEntry(12345, 60_000L);
        int s1 = allocateEntry(67890, 60_000L);
        policy.onAdd(s0, 12345, (short) 0);
        policy.onAdd(s1, 67890, (short) 0);

        assertEquals(2, policy.size());
    }

    @Test
    public void expiredEntriesAreDetected() throws InterruptedException {
        TTLPolicy policy = new TTLPolicy(1L, entryPool); // 1ms TTL

        int slot = allocateEntry(12345, 1L);
        policy.onAdd(slot, 12345, (short) 0);

        // Wait for expiry — CoarseClock updates every ~100ms
        Thread.sleep(200);

        assertTrue(policy.isExpired(slot));
    }

    @Test
    public void nonExpiredEntriesAreNotExpired() {
        TTLPolicy policy = new TTLPolicy(60_000L, entryPool);

        int slot = allocateEntry(12345, 60_000L);
        policy.onAdd(slot, 12345, (short) 0);

        assertFalse(policy.isExpired(slot));
    }

    @Test
    public void removeClearsEntryFromTracking() {
        TTLPolicy policy = new TTLPolicy(60_000L, entryPool);

        int slot = allocateEntry(12345, 60_000L);
        policy.onAdd(slot, 12345, (short) 0);
        assertEquals(1, policy.size());

        policy.onRemove(slot);
        assertEquals(0, policy.size());
    }

    @Test
    public void closeIsIdempotent() {
        TTLPolicy policy = new TTLPolicy(60_000L, entryPool);

        assertDoesNotThrow(policy::close);
        assertDoesNotThrow(policy::close);
    }

    @Test
    public void customTTLViaOnAddWithTTL() throws InterruptedException {
        TTLPolicy policy = new TTLPolicy(60_000L, entryPool);

        // Allocate with 1ms TTL
        int slot = allocateEntry(12345, 1L);
        policy.onAddWithTTL(slot, 12345, 1L);

        // Wait for expiry — CoarseClock updates every ~100ms
        Thread.sleep(200);

        assertTrue(policy.isExpired(slot));
    }

    // ── AUDIT-A2: default TTL is applied on put without explicit duration ───

    @Test
    public void defaultTTL_appliedOnPutWithoutDuration() throws InterruptedException {
        // Legacy TTLPolicy(long) — wheel is null but getDefaultTTLMs() still
        // reports the value, which is all OffHeapCacheImpl needs to stamp
        // expiresAt on the entry block (lookup-time expiry check does the rest).
        TTLPolicy policy = new TTLPolicy(50L);

        try (OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(8L * 1024 * 1024)
                .eviction(policy)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .backgroundEviction(false)
                .forStringValues()
                .build()) {

            // put without explicit duration — default TTL should apply
            cache.put("k", "v");
            assertEquals("v", cache.get("k"), "value readable before default TTL elapses");

            // CoarseClock ticks ~50ms; sleep well past default TTL + one tick
            Thread.sleep(300);

            assertNull(cache.get("k"),
                    "entry must expire via TTLPolicy.defaultTTLMs on put(k, v)");
        }
    }

    @Test
    public void defaultTTL_explicitDurationTakesPrecedence() throws InterruptedException {
        // Default 50ms; explicit 5s on a specific put — explicit wins
        TTLPolicy policy = new TTLPolicy(50L);

        try (OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(8L * 1024 * 1024)
                .eviction(policy)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .backgroundEviction(false)
                .forStringValues()
                .build()) {

            cache.put("long", "v", Duration.ofSeconds(5));
            cache.put("short", "v");

            Thread.sleep(300);

            assertEquals("v", cache.get("long"), "explicit 5s TTL must not be shortened to default");
            assertNull(cache.get("short"), "no-duration put must pick up default 50ms TTL");
        }
    }

    // ── AUDIT-A5: timing-wheel siftDown-on-poll repairs stale head ──────────

    @Test
    public void pollExpiredOne_afterInPlaceTTLMutation_drainsShortenedSlot()
            throws InterruptedException {
        // Single stripe → deterministic heap layout for this test
        OffHeapTimingWheel wheel = new OffHeapTimingWheel(entryPool, 1024, 1);
        try {
            long now = CoarseClock.getNow();

            // S1 initially has the shorter TTL → lands at the heap head.
            int s1 = allocateEntry(0xAAAA, 5L);
            int s2 = allocateEntry(0xBBBB, 60_000L);
            wheel.schedule(s1);
            wheel.schedule(s2);

            // Simulate the audit-A5 scenario: TTL of S1 is extended far into the
            // future while S2's TTL is shortened. OffHeapCacheImpl mutates the
            // entry block in place and then calls onTTLUpdate (= wheel.schedule)
            // to re-insert — replicate that here.
            entryPool.setExpiresAt(s1, now + 120_000L);
            wheel.schedule(s1);
            entryPool.setExpiresAt(s2, now + 10L);
            wheel.schedule(s2);

            // Wait until S2's shortened TTL elapses on the CoarseClock
            Thread.sleep(250);

            // Before the fix, the stale head (S1 with now-120s expiry) would
            // short-circuit the poll and return 0. After the fix, siftDown
            // bubbles up S2 and the poll returns it.
            int expired = wheel.pollExpiredOne();
            assertEquals(s2, expired,
                    "wheel must drain S2 even though the heap head was stale after in-place TTL mutation");
        } finally {
            wheel.close();
        }
    }

    @Test
    public void timingWheelCloseIsIdempotent() {
        OffHeapTimingWheel wheel = new OffHeapTimingWheel(entryPool, 1024, 1);

        assertDoesNotThrow(wheel::close);
        assertDoesNotThrow(wheel::close);
    }
}
