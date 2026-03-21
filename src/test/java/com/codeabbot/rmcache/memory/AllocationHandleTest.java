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
package com.codeabbot.rmcache.memory;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class AllocationHandleTest {

    @Test
    public void testPackUnpackRoundtripBasic() {
        long offset = 12345L;
        int capacity = 256; // multiple of 64
        int sizeClass = 3;

        long packed = AllocationHandle.pack(offset, capacity, sizeClass);
        assertEquals(offset, AllocationHandle.unpackOffset(packed));
        assertEquals(capacity, AllocationHandle.unpackCapacity(packed));
        assertEquals(sizeClass, AllocationHandle.unpackSizeClass(packed));
    }

    @Test
    public void testPackUnpackOffsetZero() {
        long packed = AllocationHandle.pack(0L, 64, 0);
        assertEquals(0L, AllocationHandle.unpackOffset(packed));
        assertEquals(64, AllocationHandle.unpackCapacity(packed));
        assertEquals(0, AllocationHandle.unpackSizeClass(packed));
    }

    @Test
    public void testPackUnpackMaxOffset() {
        // 40-bit max offset = 2^40 - 1 = 1099511627775
        long maxOffset = (1L << 40) - 1;
        int capacity = 128;
        int sizeClass = 5;

        long packed = AllocationHandle.pack(maxOffset, capacity, sizeClass);
        assertEquals(maxOffset, AllocationHandle.unpackOffset(packed));
        assertEquals(capacity, AllocationHandle.unpackCapacity(packed));
        assertEquals(sizeClass, AllocationHandle.unpackSizeClass(packed));
    }

    @Test
    public void testPackUnpackSizeClassNegativeOne() {
        // sizeClass=-1 maps to 0xF (large allocation)
        long offset = 1000L;
        int capacity = 64;
        int sizeClass = -1;

        long packed = AllocationHandle.pack(offset, capacity, sizeClass);
        assertEquals(offset, AllocationHandle.unpackOffset(packed));
        assertEquals(capacity, AllocationHandle.unpackCapacity(packed));
        assertEquals(-1, AllocationHandle.unpackSizeClass(packed));
    }

    @Test
    public void testPackUnpackSizeClassZero() {
        long packed = AllocationHandle.pack(500L, 64, 0);
        assertEquals(0, AllocationHandle.unpackSizeClass(packed));
    }

    @Test
    public void testPackUnpackSizeClassTen() {
        long packed = AllocationHandle.pack(500L, 128, 10);
        assertEquals(10, AllocationHandle.unpackSizeClass(packed));
    }

    @Test
    public void testCapacityEncodingMultiplesOf64() {
        // Capacity is encoded by right-shifting 6 bits (dividing by 64)
        // and decoded by left-shifting 6 bits, so it must be a multiple of 64
        int[] capacities = {64, 128, 256, 512, 1024, 4096, 65536};
        for (int cap : capacities) {
            long packed = AllocationHandle.pack(100L, cap, 1);
            assertEquals(cap, AllocationHandle.unpackCapacity(packed),
                    "capacity " + cap + " should roundtrip correctly");
        }
    }

    @Test
    public void testCapacityNonMultipleOf64TruncatesLowBits() {
        // A capacity of 100 is not a multiple of 64.
        // Encoding: 100 >>> 6 = 1, decoding: 1 << 6 = 64
        long packed = AllocationHandle.pack(0L, 100, 0);
        assertEquals(64, AllocationHandle.unpackCapacity(packed),
                "non-multiple-of-64 capacity should be truncated to nearest lower multiple");
    }

    @Test
    public void testDifferentOffsetsProduceDifferentPacked() {
        long p1 = AllocationHandle.pack(100L, 128, 1);
        long p2 = AllocationHandle.pack(200L, 128, 1);
        assertNotEquals(p1, p2);
    }

    @Test
    public void testDifferentSizeClassesProduceDifferentPacked() {
        long p1 = AllocationHandle.pack(100L, 128, 1);
        long p2 = AllocationHandle.pack(100L, 128, 2);
        assertNotEquals(p1, p2);
    }

    @Test
    public void testLargeCapacity() {
        // 20 bits for encoded capacity, shifted left by 6 = max (2^20-1)*64 = 67108800
        int maxEncodable = ((1 << 20) - 1) << 6; // 67108800
        long packed = AllocationHandle.pack(0L, maxEncodable, 0);
        assertEquals(maxEncodable, AllocationHandle.unpackCapacity(packed));
    }

    @Test
    public void testPackUnpackVariousOffsets() {
        long[] offsets = {0, 1, 255, 65536, (1L << 30), (1L << 39)};
        for (long off : offsets) {
            long packed = AllocationHandle.pack(off, 256, 2);
            assertEquals(off, AllocationHandle.unpackOffset(packed),
                    "offset " + off + " should roundtrip correctly");
        }
    }
}
