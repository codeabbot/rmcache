package com.codeabbot.rmcache.eviction;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class TTLPolicyTest {

    @Test
    public void entriesWithTTLAreTracked() {
        TTLPolicy policy = new TTLPolicy(60000); // 60s default

        policy.onAdd(0, 12345, (short) 0);
        policy.onAdd(1, 67890, (short) 0);

        assertEquals(2, policy.size());
    }

    @Test
    public void expiredEntriesAreDetected() throws InterruptedException {
        TTLPolicy policy = new TTLPolicy(1); // 1ms TTL - expires quickly

        policy.onAdd(0, 12345, (short) 0);

        // Wait for expiry
        Thread.sleep(10);

        assertTrue(policy.isExpired(0));
    }

    @Test
    public void nonExpiredEntriesAreNotExpired() {
        TTLPolicy policy = new TTLPolicy(60000); // 60s TTL

        policy.onAdd(0, 12345, (short) 0);

        assertFalse(policy.isExpired(0));
    }

    @Test
    public void removeClearsEntryFromTracking() {
        TTLPolicy policy = new TTLPolicy(60000);

        policy.onAdd(0, 12345, (short) 0);
        assertEquals(1, policy.size());

        policy.onRemove(0);
        assertEquals(0, policy.size());
    }

    @Test
    public void customTTLViaOnAddWithTTL() throws InterruptedException {
        TTLPolicy policy = new TTLPolicy(60000);

        // Add with custom short TTL
        policy.onAddWithTTL(0, 12345, 1); // 1ms

        Thread.sleep(10);

        assertTrue(policy.isExpired(0));
    }
}
