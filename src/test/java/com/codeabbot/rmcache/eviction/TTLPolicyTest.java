package com.codeabbot.rmcache.eviction;

import com.codeabbot.rmcache.index.EntryPool;
import com.codeabbot.rmcache.memory.SlabAllocator;
import com.codeabbot.rmcache.util.CoarseClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TTLPolicy using a real EntryPool so expiration reads work off-heap.
 */
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
    public void customTTLViaOnAddWithTTL() throws InterruptedException {
        TTLPolicy policy = new TTLPolicy(60_000L, entryPool);

        // Allocate with 1ms TTL
        int slot = allocateEntry(12345, 1L);
        policy.onAddWithTTL(slot, 12345, 1L);

        // Wait for expiry — CoarseClock updates every ~100ms
        Thread.sleep(200);

        assertTrue(policy.isExpired(slot));
    }
}
