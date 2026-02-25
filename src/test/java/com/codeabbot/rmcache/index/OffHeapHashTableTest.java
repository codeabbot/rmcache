package com.codeabbot.rmcache.index;

import com.codeabbot.rmcache.memory.SlabAllocator;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class OffHeapHashTableTest {

    @Test
    public void testBasicOps() {
        SlabAllocator allocator = new SlabAllocator(10 * 1024 * 1024);
        EntryPool pool = new EntryPool(allocator, 1000);
        OffHeapHashTable hashTable = new OffHeapHashTable(pool, 1024 * 1024, 16, true);

        String key = "test";
        byte[] keyBytes = key.getBytes();
        int hash = key.hashCode();
        int slot = pool.allocateWithLen(hash, keyBytes, keyBytes.length, "val".getBytes(), 3, (short) 0, 0);

        // PutWithLen
        hashTable.putEntry(hash, keyBytes, keyBytes.length, slot, false);

        // GetWithLen
        int retSlot = hashTable.getWithLen(hash, keyBytes, keyBytes.length);
        assertEquals(slot, retSlot);

        // Remove
        int removed = hashTable.remove(hash, keyBytes);
        assertEquals(slot, removed);
        assertEquals(0, hashTable.getWithLen(hash, keyBytes, keyBytes.length));

        hashTable.close();
        allocator.close();
    }

    @Test
    public void testCollisions() {
        SlabAllocator allocator = new SlabAllocator(10 * 1024 * 1024);
        EntryPool pool = new EntryPool(allocator, 1000);
        OffHeapHashTable hashTable = new OffHeapHashTable(pool, 1024 * 1024, 16, true);

        // Same hash for different keys
        int h = 1;
        byte[] k1 = "k1".getBytes();
        byte[] k2 = "k2".getBytes();
        int k1Len = k1.length;
        int k2Len = k2.length;

        int s1 = pool.allocateWithLen(h, k1, k1Len, "v1".getBytes(), 2, (short) 0, 0);
        int s2 = pool.allocateWithLen(h, k2, k2Len, "v2".getBytes(), 2, (short) 0, 0);

        hashTable.putEntry(h, k1, k1Len, s1, false);
        hashTable.putEntry(h, k2, k2Len, s2, false);

        assertEquals(s1, hashTable.getWithLen(h, k1, k1Len));
        assertEquals(s2, hashTable.getWithLen(h, k2, k2Len));

        hashTable.close();
        allocator.close();
    }

    @Test
    public void testResizing() {
        SlabAllocator allocator = new SlabAllocator(10 * 1024 * 1024);
        EntryPool pool = new EntryPool(allocator, 2000);
        // Set small stripes but very tiny initial capacity if possible?
        // Initial capacity is hardcoded to 256.
        // Force 500 inserts into one stripe.
        OffHeapHashTable hashTable = new OffHeapHashTable(pool, 1024 * 1024, 1, true);

        for (int i = 1; i <= 500; i++) {
            byte[] k = ("key-" + i).getBytes();
            int h = i; // Simple hash
            int s = pool.allocateWithLen(h, k, k.length, "v".getBytes(), 1, (short) 0, 0);
            hashTable.putEntry(h, k, k.length, s, false);
        }

        assertEquals(500, hashTable.size());

        // Verify all exist
        for (int i = 1; i <= 500; i++) {
            byte[] k = ("key-" + i).getBytes();
            int h = i;
            int s = hashTable.getWithLen(h, k, k.length);
            assertTrue(s > 0, "Key " + i + " not found");
        }

        hashTable.close();
        allocator.close();
    }

    @Test
    public void testBitPackingEdgeCases() {
        SlabAllocator allocator = new SlabAllocator(10 * 1024 * 1024);
        EntryPool pool = new EntryPool(allocator, 1000);
        OffHeapHashTable hashTable = new OffHeapHashTable(pool, 1024 * 1024, 1, true);

        // Case 1: Negative Hash
        int negHash = -12345;
        byte[] k1 = "negHash".getBytes();
        int s1 = pool.allocateWithLen(negHash, k1, k1.length, "v".getBytes(), 1, (short) 0, 0);
        hashTable.putEntry(negHash, k1, k1.length, s1, false);
        assertEquals(s1, hashTable.getWithLen(negHash, k1, k1.length));

        // Case 2: Max Integer Hash
        int maxHash = Integer.MAX_VALUE;
        byte[] k2 = "maxHash".getBytes();
        int s2 = pool.allocateWithLen(maxHash, k2, k2.length, "v".getBytes(), 1, (short) 0, 0);
        hashTable.putEntry(maxHash, k2, k2.length, s2, false);
        assertEquals(s2, hashTable.getWithLen(maxHash, k2, k2.length));

        // Case 3: Min Integer Hash
        int minHash = Integer.MIN_VALUE;
        byte[] k3 = "minHash".getBytes();
        int s3 = pool.allocateWithLen(minHash, k3, k3.length, "v".getBytes(), 1, (short) 0, 0);
        hashTable.putEntry(minHash, k3, k3.length, s3, false);
        assertEquals(s3, hashTable.getWithLen(minHash, k3, k3.length));

        // Case 4: Verify separation (ensure slots don't bleed into hash)
        // We can't easily force a specific large Slot ID without hacking EntryPool,
        // but normal usage with these hashes verifies basic packing.

        hashTable.close();
        allocator.close();
    }
}
