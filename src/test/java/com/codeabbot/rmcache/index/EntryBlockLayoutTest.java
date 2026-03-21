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
package com.codeabbot.rmcache.index;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class EntryBlockLayoutTest {

    // ---- pad() tests ----

    @Test
    public void testPadZero() {
        assertEquals(0, EntryBlockLayout.pad(0));
    }

    @Test
    public void testPadOne() {
        // 1 padded to next multiple of 4 = 4
        assertEquals(4, EntryBlockLayout.pad(1));
    }

    @Test
    public void testPadThree() {
        assertEquals(4, EntryBlockLayout.pad(3));
    }

    @Test
    public void testPadFourAlreadyAligned() {
        assertEquals(4, EntryBlockLayout.pad(4));
    }

    @Test
    public void testPadFive() {
        assertEquals(8, EntryBlockLayout.pad(5));
    }

    @Test
    public void testPadEight() {
        assertEquals(8, EntryBlockLayout.pad(8));
    }

    @Test
    public void testPadTwo() {
        assertEquals(4, EntryBlockLayout.pad(2));
    }

    @Test
    public void testPadLargeValue() {
        // 17 -> next multiple of 4 = 20
        assertEquals(20, EntryBlockLayout.pad(17));
    }

    // ---- computeSize() tests ----
    // Formula: HEADER_SIZE + 4 + pad(keyLen) + 4 + valLen
    // HEADER_SIZE = 20, so base overhead = 20 + 4 + 4 = 28

    @Test
    public void testComputeSizeMinimal() {
        // keyLen=0, valLen=0 -> 20 + 4 + pad(0) + 4 + 0 = 28
        assertEquals(28, EntryBlockLayout.computeSize(0, 0));
    }

    @Test
    public void testComputeSizeSmallKey() {
        // keyLen=3, valLen=0 -> 20 + 4 + 4 + 4 + 0 = 32
        assertEquals(32, EntryBlockLayout.computeSize(3, 0));
    }

    @Test
    public void testComputeSizeAlignedKey() {
        // keyLen=4, valLen=0 -> 20 + 4 + 4 + 4 + 0 = 32
        assertEquals(32, EntryBlockLayout.computeSize(4, 0));
    }

    @Test
    public void testComputeSizeKeyAndValue() {
        // keyLen=10, valLen=20 -> 20 + 4 + pad(10)=12 + 4 + 20 = 60
        assertEquals(60, EntryBlockLayout.computeSize(10, 20));
    }

    @Test
    public void testComputeSizeLargeKeyAndValue() {
        // keyLen=100, valLen=500 -> 20 + 4 + pad(100)=100 + 4 + 500 = 628
        assertEquals(628, EntryBlockLayout.computeSize(100, 500));
    }

    @Test
    public void testComputeSizeKeyLen1ValLen1() {
        // keyLen=1, valLen=1 -> 20 + 4 + pad(1)=4 + 4 + 1 = 33
        assertEquals(33, EntryBlockLayout.computeSize(1, 1));
    }

    @Test
    public void testComputeSizeKeyLen5ValLen0() {
        // keyLen=5, valLen=0 -> 20 + 4 + pad(5)=8 + 4 + 0 = 36
        assertEquals(36, EntryBlockLayout.computeSize(5, 0));
    }

    // ---- Constants ----

    @Test
    public void testHeaderSize() {
        assertEquals(20, EntryBlockLayout.HEADER_SIZE);
    }

    @Test
    public void testDataOffset() {
        // DATA_OFFSET = HEADER_SIZE + 4
        assertEquals(24, EntryBlockLayout.DATA_OFFSET);
    }
}
