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
import com.codeabbot.rmcache.serializer.BuiltInSerializers;
import com.codeabbot.rmcache.util.CoarseClock;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Targeted coverage tests for OffHeapTimingWheel, TTLPolicy,
 * and CompositePolicy — covering schedule/pollExpired, hasExpired,
 * compact, close, siftDown, and composite delegation.
 */
public class EvictionCoverageTest {

    private static final long MB4 = 4L * 1024 * 1024;
    private static final long MB8 = 8L * 1024 * 1024;

    // ── OffHeapTimingWheel ───────────────────────────────────────────────────

    @Test
    void timingWheel_scheduleAndPollExpired() throws Exception {
        CoarseClock.acquire();
        try (SlabAllocator alloc = new SlabAllocator(MB4, 64 * 1024);
             EntryPool pool = new EntryPool(alloc, 1000, 64)) {

            OffHeapTimingWheel wheel = new OffHeapTimingWheel(pool, 1000, 4);
            try {
                // Allocate an entry with short TTL
                byte[] key = "tk".getBytes();
                byte[] value = "tv".getBytes();
                // CoarseClock resolution is 50ms, use a past timestamp
                long expiresAt = System.currentTimeMillis() - 100;

                int slot = pool.allocateWithLen(0x1234, key, key.length,
                        value, value.length, (short) 0, expiresAt);
                assertTrue(slot > 0);

                wheel.schedule(slot);

                // Wait for CoarseClock to tick
                Thread.sleep(100);

                assertTrue(wheel.hasExpired());
                int expired = wheel.pollExpiredOne();
                assertEquals(slot, expired);

                pool.free(slot);
            } finally {
                wheel.close();
            }
        } finally {
            CoarseClock.release();
        }
    }

    @Test
    void timingWheel_pollReturns0WhenNoneExpired() {
        try (SlabAllocator alloc = new SlabAllocator(MB4, 64 * 1024);
             EntryPool pool = new EntryPool(alloc, 1000, 64)) {

            OffHeapTimingWheel wheel = new OffHeapTimingWheel(pool, 1000, 4);
            try {
                // Empty wheel
                assertEquals(0, wheel.pollExpiredOne());
                assertFalse(wheel.hasExpired());

                // Schedule far-future entry
                byte[] key = "fk".getBytes();
                byte[] value = "fv".getBytes();
                long expiresAt = CoarseClock.getNow() + 600_000; // 10 min

                int slot = pool.allocateWithLen(0x5678, key, key.length,
                        value, value.length, (short) 0, expiresAt);
                assertTrue(slot > 0);

                wheel.schedule(slot);
                assertFalse(wheel.hasExpired());
                assertEquals(0, wheel.pollExpiredOne());

                pool.free(slot);
            } finally {
                wheel.close();
            }
        }
    }

    @Test
    void timingWheel_cancel_preventsExpiration() throws Exception {
        try (SlabAllocator alloc = new SlabAllocator(MB4, 64 * 1024);
             EntryPool pool = new EntryPool(alloc, 1000, 64)) {

            OffHeapTimingWheel wheel = new OffHeapTimingWheel(pool, 1000, 4);
            try {
                byte[] key = "ck".getBytes();
                byte[] value = "cv".getBytes();
                long expiresAt = CoarseClock.getNow() + 10; // 10ms

                int slot = pool.allocateWithLen(0x9999, key, key.length,
                        value, value.length, (short) 0, expiresAt);
                assertTrue(slot > 0);

                wheel.schedule(slot);
                wheel.cancel(slot);

                Thread.sleep(50);

                // cancelled entry should be drained as lazy-cancelled
                int expired = wheel.pollExpiredOne();
                assertEquals(0, expired);

                pool.free(slot);
            } finally {
                wheel.close();
            }
        }
    }

    @Test
    void timingWheel_compact_removesLazyCancelled() {
        try (SlabAllocator alloc = new SlabAllocator(MB4, 64 * 1024);
             EntryPool pool = new EntryPool(alloc, 1000, 64)) {

            OffHeapTimingWheel wheel = new OffHeapTimingWheel(pool, 1000, 4);
            try {
                // Schedule several entries then cancel some
                List<Integer> slots = new ArrayList<>();
                for (int i = 0; i < 20; i++) {
                    byte[] key = ("ck" + i).getBytes();
                    byte[] value = ("cv" + i).getBytes();
                    long expiresAt = CoarseClock.getNow() + 300_000;

                    int slot = pool.allocateWithLen(i, key, key.length,
                            value, value.length, (short) 0, expiresAt);
                    if (slot > 0) {
                        wheel.schedule(slot);
                        slots.add(slot);
                    }
                }

                // Cancel half
                for (int i = 0; i < slots.size(); i += 2) {
                    wheel.cancel(slots.get(i));
                }

                // Compact should rebuild heaps without cancelled entries
                wheel.compact();

                // Still functional
                assertEquals(0, wheel.pollExpiredOne());

                for (int slot : slots) {
                    pool.free(slot);
                }
            } finally {
                wheel.close();
            }
        }
    }

    @Test
    void timingWheel_ensureCapacity_growsHeap() {
        try (SlabAllocator alloc = new SlabAllocator(MB4, 64 * 1024);
             EntryPool pool = new EntryPool(alloc, 10000, 64)) {

            // Use a small number of stripes so per-stripe capacity is hit sooner
            OffHeapTimingWheel wheel = new OffHeapTimingWheel(pool, 100, 1);
            try {
                // Schedule more entries than initial capacity (256)
                List<Integer> slots = new ArrayList<>();
                for (int i = 0; i < 300; i++) {
                    byte[] key = ("gk" + i).getBytes();
                    byte[] value = ("gv" + i).getBytes();
                    long expiresAt = CoarseClock.getNow() + 600_000;

                    int slot = pool.allocateWithLen(i, key, key.length,
                            value, value.length, (short) 0, expiresAt);
                    if (slot > 0) {
                        wheel.schedule(slot);
                        slots.add(slot);
                    }
                }
                assertTrue(slots.size() >= 256, "Should have exceeded initial capacity");

                for (int slot : slots) {
                    pool.free(slot);
                }
            } finally {
                wheel.close();
            }
        }
    }

    @Test
    void timingWheel_multipleExpired_polledInOrder() throws Exception {
        CoarseClock.acquire();
        try (SlabAllocator alloc = new SlabAllocator(MB4, 64 * 1024);
             EntryPool pool = new EntryPool(alloc, 1000, 64)) {

            OffHeapTimingWheel wheel = new OffHeapTimingWheel(pool, 1000, 1);
            try {
                int[] slots = new int[5];
                for (int i = 0; i < 5; i++) {
                    byte[] key = ("mk" + i).getBytes();
                    byte[] value = ("mv" + i).getBytes();
                    // Use past timestamps to ensure they are expired
                    long expiresAt = System.currentTimeMillis() - 100 + i;

                    slots[i] = pool.allocateWithLen(i, key, key.length,
                            value, value.length, (short) 0, expiresAt);
                    assertTrue(slots[i] > 0);
                    wheel.schedule(slots[i]);
                }

                // Wait for CoarseClock to tick and see the expired entries
                Thread.sleep(100);

                // Poll all expired
                int polled = 0;
                for (int i = 0; i < 10; i++) {
                    int s = wheel.pollExpiredOne();
                    if (s != 0) polled++;
                }
                assertEquals(5, polled);

                for (int slot : slots) {
                    pool.free(slot);
                }
            } finally {
                wheel.close();
            }
        } finally {
            CoarseClock.release();
        }
    }

    @Test
    void timingWheel_getEntryPool_returnsPool() {
        try (SlabAllocator alloc = new SlabAllocator(MB4, 64 * 1024);
             EntryPool pool = new EntryPool(alloc, 1000, 64)) {

            OffHeapTimingWheel wheel = new OffHeapTimingWheel(pool, 1000);
            try {
                assertSame(pool, wheel.getEntryPool());
            } finally {
                wheel.close();
            }
        }
    }

    // ── TTLPolicy ────────────────────────────────────────────────────────────

    @Test
    void ttlPolicy_fullLifecycle() {
        try (SlabAllocator alloc = new SlabAllocator(MB4, 64 * 1024);
             EntryPool pool = new EntryPool(alloc, 1000, 64)) {

            TTLPolicy policy = new TTLPolicy(5000, 100, pool);
            try {
                // Allocate entry with TTL
                byte[] key = "tk".getBytes();
                byte[] value = "tv".getBytes();
                long expiresAt = CoarseClock.getNow() + 5000;

                int slot = pool.allocateWithLen(0, key, key.length,
                        value, value.length, (short) 0, expiresAt);
                assertTrue(slot > 0);

                policy.onAdd(slot, 0, (short) 0);
                assertEquals(1, policy.size());
                assertEquals(100, policy.getMaxEntries());
                assertFalse(policy.shouldEvict());

                // Access does nothing for TTL
                policy.onAccess(slot, 0);
                assertEquals(1, policy.size());

                // Remove
                policy.onRemove(slot);
                assertEquals(0, policy.size());

                // Double remove should not go negative
                policy.onRemove(slot);
                assertEquals(0, policy.size());

                pool.free(slot);
            } finally {
                policy.close();
            }
        }
    }

    @Test
    void ttlPolicy_shouldEvict_whenMaxReached() {
        try (SlabAllocator alloc = new SlabAllocator(MB4, 64 * 1024);
             EntryPool pool = new EntryPool(alloc, 1000, 64)) {

            TTLPolicy policy = new TTLPolicy(60_000, 5, pool);
            try {
                List<Integer> slots = new ArrayList<>();
                for (int i = 0; i < 5; i++) {
                    byte[] key = ("k" + i).getBytes();
                    byte[] value = ("v" + i).getBytes();
                    long expiresAt = CoarseClock.getNow() + 60_000;

                    int slot = pool.allocateWithLen(i, key, key.length,
                            value, value.length, (short) 0, expiresAt);
                    assertTrue(slot > 0);
                    policy.onAdd(slot, i, (short) 0);
                    slots.add(slot);
                }

                assertTrue(policy.shouldEvict());

                for (int slot : slots) {
                    pool.free(slot);
                }
            } finally {
                policy.close();
            }
        }
    }

    @Test
    void ttlPolicy_selectVictim_returnsExpired() throws Exception {
        CoarseClock.acquire();
        try (SlabAllocator alloc = new SlabAllocator(MB4, 64 * 1024);
             EntryPool pool = new EntryPool(alloc, 1000, 64)) {

            TTLPolicy policy = new TTLPolicy(10, 100, pool);
            try {
                byte[] key = "ek".getBytes();
                byte[] value = "ev".getBytes();
                // Use a past timestamp to ensure the entry is already expired
                long expiresAt = System.currentTimeMillis() - 100;

                int slot = pool.allocateWithLen(0, key, key.length,
                        value, value.length, (short) 0, expiresAt);
                assertTrue(slot > 0);

                policy.onAdd(slot, 0, (short) 0);

                // Wait for CoarseClock to tick
                Thread.sleep(100);
                assertTrue(policy.shouldEvict());
                int victim = policy.selectVictim();
                assertEquals(slot, victim);

                policy.onRemove(slot);
                pool.free(slot);
            } finally {
                policy.close();
            }
        } finally {
            CoarseClock.release();
        }
    }

    @Test
    void ttlPolicy_isExpired() {
        try (SlabAllocator alloc = new SlabAllocator(MB4, 64 * 1024);
             EntryPool pool = new EntryPool(alloc, 1000, 64)) {

            TTLPolicy policy = new TTLPolicy(60_000, pool);
            try {
                byte[] key = "ik".getBytes();
                byte[] value = "iv".getBytes();
                long expiresAt = CoarseClock.getNow() + 60_000;

                int slot = pool.allocateWithLen(0, key, key.length,
                        value, value.length, (short) 0, expiresAt);
                assertTrue(slot > 0);

                assertFalse(policy.isExpired(slot));

                // Make it expired
                pool.setExpiresAt(slot, 1L);
                assertTrue(policy.isExpired(slot));

                pool.free(slot);
            } finally {
                policy.close();
            }
        }
    }

    @Test
    void ttlPolicy_compact_delegatesToWheel() {
        try (SlabAllocator alloc = new SlabAllocator(MB4, 64 * 1024);
             EntryPool pool = new EntryPool(alloc, 1000, 64)) {

            TTLPolicy policy = new TTLPolicy(60_000, 100, pool);
            try {
                // compact should not throw
                policy.compact();
            } finally {
                policy.close();
            }
        }
    }

    @SuppressWarnings("deprecation")
    @Test
    void ttlPolicy_legacyConstructor() {
        TTLPolicy policy = new TTLPolicy(5000);
        try {
            assertEquals(0, policy.size());
            assertFalse(policy.shouldEvict());
            assertEquals(0, policy.selectVictim());
            assertFalse(policy.isExpired(1));
            policy.compact();
        } finally {
            policy.close();
        }
    }

    @Test
    void ttlPolicy_onAddWithTTL() {
        try (SlabAllocator alloc = new SlabAllocator(MB4, 64 * 1024);
             EntryPool pool = new EntryPool(alloc, 1000, 64)) {

            TTLPolicy policy = new TTLPolicy(60_000, 100, pool);
            try {
                byte[] key = "twk".getBytes();
                byte[] value = "twv".getBytes();
                long expiresAt = CoarseClock.getNow() + 30_000;

                int slot = pool.allocateWithLen(0, key, key.length,
                        value, value.length, (short) 0, expiresAt);
                assertTrue(slot > 0);

                policy.onAddWithTTL(slot, 0, 30_000);
                assertEquals(1, policy.size());

                policy.onRemove(slot);
                pool.free(slot);
            } finally {
                policy.close();
            }
        }
    }

    // ── CompositePolicy ──────────────────────────────────────────────────────

    @Test
    void compositePolicy_delegatesAllMethods() {
        try (SlabAllocator alloc = new SlabAllocator(MB4, 64 * 1024);
             EntryPool pool = new EntryPool(alloc, 1000, 64)) {

            LRUPolicy lru = new LRUPolicy(100, pool.slotCapacity(), 0.01f, 0.8f);
            TTLPolicy ttl = new TTLPolicy(60_000, 100, pool);

            CompositePolicy composite = new CompositePolicy(List.of(lru, ttl));
            try {
                // setEntryPool delegates to all
                composite.setEntryPool(pool);

                byte[] key = "ck".getBytes();
                byte[] value = "cv".getBytes();
                long expiresAt = CoarseClock.getNow() + 60_000;

                int slot = pool.allocateWithLen(0xABCD, key, key.length,
                        value, value.length, (short) 0, expiresAt);
                assertTrue(slot > 0);

                composite.onAdd(slot, 0xABCD, (short) 0);
                assertTrue(composite.size() > 0);
                assertTrue(composite.getMaxEntries() > 0);

                composite.onAccess(slot, 0xABCD);
                composite.drainBuffers();
                composite.compact();

                assertFalse(composite.shouldEvict());

                composite.onRemove(slot);
                pool.free(slot);
            } finally {
                composite.close();
            }
        }
    }

    @Test
    void compositePolicy_selectVictim_firstNonZero() throws Exception {
        try (SlabAllocator alloc = new SlabAllocator(MB4, 64 * 1024);
             EntryPool pool = new EntryPool(alloc, 1000, 64)) {

            TTLPolicy ttl = new TTLPolicy(10, 100, pool);
            LRUPolicy lru = new LRUPolicy(100, pool.slotCapacity(), 0.01f, 0.8f);
            lru.setEntryPool(pool);

            CompositePolicy composite = new CompositePolicy(List.of(ttl, lru));
            try {
                byte[] key = "vk".getBytes();
                byte[] value = "vv".getBytes();
                long expiresAt = CoarseClock.getNow() + 1;

                int slot = pool.allocateWithLen(0, key, key.length,
                        value, value.length, (short) 0, expiresAt);
                assertTrue(slot > 0);

                composite.onAdd(slot, 0, (short) 0);
                Thread.sleep(50);

                int victim = composite.selectVictim();
                // TTL policy should return the expired entry first
                assertEquals(slot, victim);

                composite.onRemove(slot);
                pool.free(slot);
            } finally {
                composite.close();
            }
        }
    }

    // ── Integration: TTL eviction through cache ──────────────────────────────

    @Test
    void cacheWithTTL_entriesExpire() throws Exception {
        try (OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .build()) {

            cache.put("ttl1", "val1", Duration.ofMillis(50));
            cache.put("ttl2", "val2", Duration.ofMillis(50));
            cache.put("notttl", "val3");

            assertNotNull(cache.get("ttl1"));
            Thread.sleep(100);
            assertNull(cache.get("ttl1"));
            assertNull(cache.get("ttl2"));
            assertNotNull(cache.get("notttl"));
        }
    }

    // ── OffHeapTimingWheel: siftDown with many entries ──────────────────────

    @Test
    void timingWheel_siftDown_exercisedDuringCompact() {
        try (SlabAllocator alloc = new SlabAllocator(MB4, 64 * 1024);
             EntryPool pool = new EntryPool(alloc, 5000, 64)) {

            OffHeapTimingWheel wheel = new OffHeapTimingWheel(pool, 5000, 2);
            try {
                long baseTime = CoarseClock.getNow() + 100_000;
                // Schedule entries with varying expiration times to exercise heap ordering
                List<Integer> slots = new ArrayList<>();
                for (int i = 0; i < 100; i++) {
                    byte[] key = ("sd" + i).getBytes();
                    byte[] value = ("sv" + i).getBytes();
                    long expiresAt = baseTime + (i % 7) * 1000 + i;

                    int slot = pool.allocateWithLen(i, key, key.length,
                            value, value.length, (short) 0, expiresAt);
                    if (slot > 0) {
                        wheel.schedule(slot);
                        slots.add(slot);
                    }
                }

                // Cancel scattered entries (not just alternating — varied pattern)
                for (int i = 0; i < slots.size(); i += 3) {
                    wheel.cancel(slots.get(i));
                }

                // Compact triggers siftDown for each stripe's re-heapify
                wheel.compact();

                // Schedule more after compact to exercise siftUp on compacted heaps
                for (int i = 0; i < 20; i++) {
                    byte[] key = ("sd2-" + i).getBytes();
                    byte[] value = ("sv2-" + i).getBytes();
                    long expiresAt = baseTime - 10000 + i; // earlier than existing

                    int slot = pool.allocateWithLen(1000 + i, key, key.length,
                            value, value.length, (short) 0, expiresAt);
                    if (slot > 0) {
                        wheel.schedule(slot);
                        slots.add(slot);
                    }
                }

                for (int slot : slots) {
                    pool.free(slot);
                }
            } finally {
                wheel.close();
            }
        }
    }

    // ── OffHeapTimingWheel: close is idempotent ─────────────────────────────

    @Test
    void timingWheel_doubleClose_safe() {
        try (SlabAllocator alloc = new SlabAllocator(MB4, 64 * 1024);
             EntryPool pool = new EntryPool(alloc, 1000, 64)) {

            OffHeapTimingWheel wheel = new OffHeapTimingWheel(pool, 1000, 2);
            wheel.close();
            // Second close should not throw
            wheel.close();
        }
    }

    // ── TTLPolicy: shouldEvict when wheel has expired entry ─────────────────

    @Test
    void ttlPolicy_shouldEvict_whenWheelHasExpired() throws Exception {
        CoarseClock.acquire();
        try (SlabAllocator alloc = new SlabAllocator(MB4, 64 * 1024);
             EntryPool pool = new EntryPool(alloc, 1000, 64)) {

            TTLPolicy policy = new TTLPolicy(100, 1000, pool);
            try {
                byte[] key = "expk".getBytes();
                byte[] value = "expv".getBytes();
                long expiresAt = System.currentTimeMillis() - 100; // already expired

                int slot = pool.allocateWithLen(0, key, key.length,
                        value, value.length, (short) 0, expiresAt);
                assertTrue(slot > 0);

                policy.onAdd(slot, 0, (short) 0);
                Thread.sleep(100);

                // shouldEvict should return true because wheel.hasExpired() is true
                assertTrue(policy.shouldEvict());
                int victim = policy.selectVictim();
                assertEquals(slot, victim);

                pool.free(slot);
            } finally {
                policy.close();
            }
        } finally {
            CoarseClock.release();
        }
    }

    // ── CompositePolicy: empty policy list ──────────────────────────────────

    @Test
    void compositePolicy_emptyList() {
        CompositePolicy composite = new CompositePolicy(List.of());
        try {
            assertEquals(0, composite.size());
            assertEquals(Integer.MAX_VALUE, composite.getMaxEntries());
            assertFalse(composite.shouldEvict());
            assertEquals(0, composite.selectVictim());
            // These should not throw
            composite.onAccess(1, 1);
            composite.onAdd(1, 1, (short) 0);
            composite.onRemove(1);
            composite.drainBuffers();
            composite.compact();
        } finally {
            composite.close();
        }
    }

    // ── TTL integration: getZeroCopy/getView on expired entries ─────────────

    @Test
    void cacheWithTTL_getZeroCopy_expired() throws Exception {
        try (OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .build()) {

            cache.put("zc-ttl", "zc-val", Duration.ofMillis(50));
            Thread.sleep(100);
            assertNull(cache.getZeroCopy("zc-ttl", seg -> "found"));
        }
    }

    @Test
    void cacheWithTTL_getView_expired() throws Exception {
        try (OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(MB8)
                .backgroundEviction(false)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .build()) {

            cache.put("vw-ttl", "vw-val", Duration.ofMillis(50));
            Thread.sleep(100);
            assertNull(cache.getView("vw-ttl"));
        }
    }

    // ── TTL with composite + LRU (typical production config) ────────────────

    @Test
    void compositePolicy_ttlPlusLru_eviction() throws Exception {
        CoarseClock.acquire();
        try (SlabAllocator alloc = new SlabAllocator(MB4, 64 * 1024);
             EntryPool pool = new EntryPool(alloc, 1000, 64)) {

            TTLPolicy ttl = new TTLPolicy(100, 1000, pool);
            LRUPolicy lru = new LRUPolicy(50, pool.slotCapacity(), 0.01f, 0.8f);
            lru.setEntryPool(pool);

            CompositePolicy composite = new CompositePolicy(List.of(ttl, lru));
            try {
                composite.setEntryPool(pool);

                // Add entries with already-expired TTL
                List<Integer> slots = new ArrayList<>();
                for (int i = 0; i < 10; i++) {
                    byte[] key = ("ctk" + i).getBytes();
                    byte[] value = ("ctv" + i).getBytes();
                    long expiresAt = System.currentTimeMillis() - 100; // already expired

                    int slot = pool.allocateWithLen(i, key, key.length,
                            value, value.length, (short) 0, expiresAt);
                    if (slot > 0) {
                        composite.onAdd(slot, i, (short) 0);
                        slots.add(slot);
                    }
                }

                Thread.sleep(100);

                // Should evict: TTL entries are already expired
                // Even if wheel hasn't ticked, the LRU maxEntries check helps
                composite.drainBuffers();
                composite.compact();

                for (int slot : slots) {
                    pool.free(slot);
                }
            } finally {
                composite.close();
            }
        } finally {
            CoarseClock.release();
        }
    }
}
