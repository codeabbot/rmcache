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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class OffHeapCompactLRUTest {

    private OffHeapCompactLRU lru;

    @BeforeEach
    public void setUp() {
        lru = new OffHeapCompactLRU(100);
    }

    @AfterEach
    public void tearDown() {
        lru.close();
    }

    // ---- Window segment tests ----

    @Test
    public void testAddToWindowSingle() {
        lru.addToWindow(1);
        assertEquals(OffHeapCompactLRU.WINDOW, lru.getSegment(1));
        assertEquals(1, lru.windowSize);
        assertEquals(1, lru.headWindow);
        assertEquals(1, lru.tailWindow);
    }

    @Test
    public void testAddToWindowMultiple() {
        lru.addToWindow(1);
        lru.addToWindow(2);
        lru.addToWindow(3);
        assertEquals(3, lru.windowSize);
        // Most recent add goes to head
        assertEquals(3, lru.headWindow);
        assertEquals(1, lru.tailWindow);
    }

    @Test
    public void testPollWindow() {
        lru.addToWindow(1);
        lru.addToWindow(2);
        lru.addToWindow(3);
        // Poll returns tail (LRU order)
        assertEquals(1, lru.pollWindow());
        assertEquals(2, lru.windowSize);
        assertEquals(OffHeapCompactLRU.NONE, lru.getSegment(1));
    }

    @Test
    public void testPollWindowEmpty() {
        assertEquals(0, lru.pollWindow());
    }

    @Test
    public void closeIsIdempotent() {
        assertDoesNotThrow(lru::close);
        assertDoesNotThrow(lru::close);
    }

    @Test
    public void testRemoveWindowHead() {
        lru.addToWindow(1);
        lru.addToWindow(2);
        lru.addToWindow(3);
        lru.removeWindow(3); // head
        assertEquals(2, lru.windowSize);
        assertEquals(2, lru.headWindow);
        assertEquals(OffHeapCompactLRU.NONE, lru.getSegment(3));
    }

    @Test
    public void testRemoveWindowMiddle() {
        lru.addToWindow(1);
        lru.addToWindow(2);
        lru.addToWindow(3);
        lru.removeWindow(2); // middle
        assertEquals(2, lru.windowSize);
        assertEquals(3, lru.headWindow);
        assertEquals(1, lru.tailWindow);
    }

    @Test
    public void testRemoveWindowTail() {
        lru.addToWindow(1);
        lru.addToWindow(2);
        lru.removeWindow(1); // tail
        assertEquals(1, lru.windowSize);
        assertEquals(2, lru.tailWindow);
    }

    @Test
    public void testRemoveWindowOnly() {
        lru.addToWindow(5);
        lru.removeWindow(5);
        assertEquals(0, lru.windowSize);
        assertEquals(OffHeapCompactLRU.NONE, lru.headWindow);
        assertEquals(OffHeapCompactLRU.NONE, lru.tailWindow);
    }

    // ---- Probation segment tests ----

    @Test
    public void testAddToProbationSingle() {
        lru.addToProbation(10);
        assertEquals(OffHeapCompactLRU.PROBATION, lru.getSegment(10));
        assertEquals(1, lru.probationSize);
        assertEquals(10, lru.headProbation);
        assertEquals(10, lru.tailProbation);
    }

    @Test
    public void testAddToProbationMultiple() {
        lru.addToProbation(10);
        lru.addToProbation(11);
        lru.addToProbation(12);
        assertEquals(3, lru.probationSize);
        assertEquals(12, lru.headProbation);
        assertEquals(10, lru.tailProbation);
    }

    @Test
    public void testPollProbation() {
        lru.addToProbation(10);
        lru.addToProbation(11);
        assertEquals(10, lru.pollProbation());
        assertEquals(1, lru.probationSize);
    }

    @Test
    public void testPollProbationEmpty() {
        assertEquals(0, lru.pollProbation());
    }

    @Test
    public void testRemoveProbationMiddle() {
        lru.addToProbation(10);
        lru.addToProbation(11);
        lru.addToProbation(12);
        lru.removeProbation(11);
        assertEquals(2, lru.probationSize);
        assertEquals(12, lru.headProbation);
        assertEquals(10, lru.tailProbation);
    }

    // ---- Protected segment tests ----

    @Test
    public void testAddToProtectedSingle() {
        lru.addToProtected(20);
        assertEquals(OffHeapCompactLRU.PROTECTED, lru.getSegment(20));
        assertEquals(1, lru.protectedSize);
        assertEquals(20, lru.headProtected);
        assertEquals(20, lru.tailProtected);
    }

    @Test
    public void testAddToProtectedMultiple() {
        lru.addToProtected(20);
        lru.addToProtected(21);
        lru.addToProtected(22);
        assertEquals(3, lru.protectedSize);
        assertEquals(22, lru.headProtected);
        assertEquals(20, lru.tailProtected);
    }

    @Test
    public void testPollProtected() {
        lru.addToProtected(20);
        lru.addToProtected(21);
        assertEquals(20, lru.pollProtected());
        assertEquals(1, lru.protectedSize);
    }

    @Test
    public void testPollProtectedEmpty() {
        assertEquals(0, lru.pollProtected());
    }

    @Test
    public void testRemoveProtectedHead() {
        lru.addToProtected(20);
        lru.addToProtected(21);
        lru.removeProtected(21);
        assertEquals(1, lru.protectedSize);
        assertEquals(20, lru.headProtected);
    }

    // ---- Generic remove() tests ----

    @Test
    public void testRemoveFromWindow() {
        lru.addToWindow(1);
        assertTrue(lru.remove(1));
        assertEquals(0, lru.windowSize);
        assertEquals(OffHeapCompactLRU.NONE, lru.getSegment(1));
    }

    @Test
    public void testRemoveFromProbation() {
        lru.addToProbation(10);
        assertTrue(lru.remove(10));
        assertEquals(0, lru.probationSize);
    }

    @Test
    public void testRemoveFromProtected() {
        lru.addToProtected(20);
        assertTrue(lru.remove(20));
        assertEquals(0, lru.protectedSize);
    }

    @Test
    public void testRemoveSlotNotInAnySegment() {
        assertFalse(lru.remove(50));
    }

    // ---- moveToHead tests ----

    @Test
    public void testMoveToHeadWindow() {
        lru.addToWindow(1);
        lru.addToWindow(2);
        lru.addToWindow(3);
        // 1 is tail, move it to head
        lru.moveToHead(1, OffHeapCompactLRU.WINDOW);
        assertEquals(1, lru.headWindow);
        assertEquals(2, lru.tailWindow);
        assertEquals(3, lru.windowSize);
    }

    @Test
    public void testMoveToHeadWindowAlreadyHead() {
        lru.addToWindow(1);
        lru.addToWindow(2);
        // 2 is already head; no-op
        lru.moveToHead(2, OffHeapCompactLRU.WINDOW);
        assertEquals(2, lru.headWindow);
        assertEquals(1, lru.tailWindow);
        assertEquals(2, lru.windowSize);
    }

    @Test
    public void testMoveToHeadProbation() {
        lru.addToProbation(10);
        lru.addToProbation(11);
        lru.addToProbation(12);
        lru.moveToHead(10, OffHeapCompactLRU.PROBATION);
        assertEquals(10, lru.headProbation);
        assertEquals(11, lru.tailProbation);
    }

    @Test
    public void testMoveToHeadProtected() {
        lru.addToProtected(20);
        lru.addToProtected(21);
        lru.addToProtected(22);
        lru.moveToHead(20, OffHeapCompactLRU.PROTECTED);
        assertEquals(20, lru.headProtected);
        assertEquals(21, lru.tailProtected);
    }

    // ---- ISSUE-009 guard: duplicate add is a no-op ----

    @Test
    public void testIssue009AddToWindowWhenAlreadyInProbation() {
        lru.addToProbation(5);
        assertEquals(OffHeapCompactLRU.PROBATION, lru.getSegment(5));

        // Attempt to add to window while already in probation should be a no-op
        lru.addToWindow(5);
        assertEquals(OffHeapCompactLRU.PROBATION, lru.getSegment(5));
        assertEquals(0, lru.windowSize);
        assertEquals(1, lru.probationSize);
    }

    @Test
    public void testIssue009AddToProbationWhenAlreadyInWindow() {
        lru.addToWindow(5);
        lru.addToProbation(5);
        assertEquals(OffHeapCompactLRU.WINDOW, lru.getSegment(5));
        assertEquals(1, lru.windowSize);
        assertEquals(0, lru.probationSize);
    }

    @Test
    public void testIssue009AddToProtectedWhenAlreadyInWindow() {
        lru.addToWindow(5);
        lru.addToProtected(5);
        assertEquals(OffHeapCompactLRU.WINDOW, lru.getSegment(5));
        assertEquals(1, lru.windowSize);
        assertEquals(0, lru.protectedSize);
    }

    // ---- Two-element list verifications ----

    @Test
    public void testTwoElementsWindowPollBoth() {
        lru.addToWindow(1);
        lru.addToWindow(2);
        assertEquals(1, lru.pollWindow()); // tail
        assertEquals(2, lru.pollWindow()); // remaining
        assertEquals(0, lru.windowSize);
        assertEquals(0, lru.pollWindow()); // empty
    }

    @Test
    public void testTwoElementsProbationRemoveHead() {
        lru.addToProbation(10);
        lru.addToProbation(11);
        lru.removeProbation(11); // head
        assertEquals(1, lru.probationSize);
        assertEquals(10, lru.headProbation);
        assertEquals(10, lru.tailProbation);
    }

    // ---- Cross-segment independence ----

    @Test
    public void testSegmentsAreIndependent() {
        lru.addToWindow(1);
        lru.addToProbation(2);
        lru.addToProtected(3);

        assertEquals(1, lru.windowSize);
        assertEquals(1, lru.probationSize);
        assertEquals(1, lru.protectedSize);

        assertEquals(OffHeapCompactLRU.WINDOW, lru.getSegment(1));
        assertEquals(OffHeapCompactLRU.PROBATION, lru.getSegment(2));
        assertEquals(OffHeapCompactLRU.PROTECTED, lru.getSegment(3));
    }

    // ---- close() frees memory ----

    @Test
    public void testCloseFreesMemory() {
        OffHeapCompactLRU local = new OffHeapCompactLRU(16);
        local.addToWindow(1);
        local.addToProbation(2);
        local.close();
        // No assertion beyond verifying close() does not throw
    }

    // ---- Capacity overflow guard ----

    @Test
    public void testCapacityOverflowThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new OffHeapCompactLRU(0x3FFF_FFFF + 1));
    }
}
