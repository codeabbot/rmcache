package com.codeabbot.rmcache.eviction;

import com.codeabbot.rmcache.memory.NativeMemory;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Off-Heap implementation of CompactLRU (SLRU policy).
 * Stores next/prev pointers in native memory, with segment flags packed
 * into the top 2 bits of the next pointer (saves 1 byte per entry).
 *
 * <p>
 * <b>Thread-Safety:</b> This class is NOT thread-safe. All operations must
 * be externally synchronized by the caller (e.g., via {@code shardLocks[shard]}
 * in {@link LRUPolicy}). Callers MUST hold the shard lock before calling any
 * method on this class.
 * 
 * @author Rabindra Meher
 */
public class OffHeapCompactLRU implements AutoCloseable {
    static final int NONE = 0;
    static final int WINDOW = 1;
    static final int PROBATION = 2;
    static final int PROTECTED = 3;

    // Segment is packed into top 2 bits of next[slot]
    // Next pointer uses lower 30 bits (supports up to ~1B slots)
    private static final int NEXT_MASK = 0x3FFF_FFFF; // lower 30 bits
    private static final int SEG_SHIFT = 30;

    final int capacity;

    // Native memory — segment byte array eliminated by packing into next
    private final MemorySegment next; // top 2 bits = segment, lower 30 bits = next pointer
    private final MemorySegment prev;

    int headWindow = NONE;
    int tailWindow = NONE;
    int windowSize = 0;

    int headProbation = NONE;
    int tailProbation = NONE;
    int probationSize = 0;

    int headProtected = NONE;
    int tailProtected = NONE;
    int protectedSize = 0;

    public OffHeapCompactLRU(int capacity) {
        // C2 fix: Prevent slot IDs from exceeding 30-bit NEXT_MASK.
        // At capacity > 0x3FFFFFFF, slot values would alias with the
        // segment flags packed in the top 2 bits of the next pointer.
        if (capacity > NEXT_MASK) {
            throw new IllegalArgumentException(
                    "OffHeapCompactLRU capacity " + capacity + " exceeds maximum " + NEXT_MASK
                            + " (30-bit limit). Reduce maxEntries or increase shard count.");
        }
        this.capacity = capacity;
        long size = capacity + 1;
        this.next = NativeMemory.calloc(size, ValueLayout.JAVA_INT.byteSize());
        this.prev = NativeMemory.calloc(size, ValueLayout.JAVA_INT.byteSize());
    }

    @Override
    public void close() {
        NativeMemory.free(next);
        NativeMemory.free(prev);
    }

    public int getSegment(int slot) {
        int raw = next.get(ValueLayout.JAVA_INT, (long) slot * 4);
        return raw >>> SEG_SHIFT;
    }

    private void setSegment(int slot, int value) {
        int raw = next.get(ValueLayout.JAVA_INT, (long) slot * 4);
        int nextVal = raw & NEXT_MASK;
        next.set(ValueLayout.JAVA_INT, (long) slot * 4, nextVal | (value << SEG_SHIFT));
    }

    private int getNext(int slot) {
        int raw = next.get(ValueLayout.JAVA_INT, (long) slot * 4);
        return raw & NEXT_MASK;
    }

    private void setNext(int slot, int value) {
        int raw = next.get(ValueLayout.JAVA_INT, (long) slot * 4);
        int seg = raw & ~NEXT_MASK;
        next.set(ValueLayout.JAVA_INT, (long) slot * 4, seg | (value & NEXT_MASK));
    }

    private int getPrev(int slot) {
        return prev.get(ValueLayout.JAVA_INT, (long) slot * 4);
    }

    private void setPrev(int slot, int value) {
        prev.set(ValueLayout.JAVA_INT, (long) slot * 4, value);
    }

    public void addToWindow(int slot) {
        if (headWindow == NONE) {
            headWindow = slot;
            tailWindow = slot;
        } else {
            setNext(slot, headWindow);
            setPrev(headWindow, slot);
            headWindow = slot;
        }
        setSegment(slot, WINDOW);
        windowSize++;
    }

    public int pollWindow() {
        if (tailWindow == NONE)
            return 0;
        int slot = tailWindow;
        removeWindow(slot);
        return slot;
    }

    public void removeWindow(int slot) {
        int p = getPrev(slot);
        int n = getNext(slot);
        if (p != NONE)
            setNext(p, n);
        else
            headWindow = n;
        if (n != NONE)
            setPrev(n, p);
        else
            tailWindow = p;
        // Clear both next pointer and segment bits
        next.set(ValueLayout.JAVA_INT, (long) slot * 4, 0);
        setPrev(slot, NONE);
        windowSize--;
    }

    public void addToProbation(int slot) {
        if (headProbation == NONE) {
            headProbation = slot;
            tailProbation = slot;
        } else {
            setNext(slot, headProbation);
            setPrev(headProbation, slot);
            headProbation = slot;
        }
        setSegment(slot, PROBATION);
        probationSize++;
    }

    public int pollProbation() {
        if (tailProbation == NONE)
            return 0;
        int slot = tailProbation;
        removeProbation(slot);
        return slot;
    }

    public void removeProbation(int slot) {
        int p = getPrev(slot);
        int n = getNext(slot);
        if (p != NONE)
            setNext(p, n);
        else
            headProbation = n;
        if (n != NONE)
            setPrev(n, p);
        else
            tailProbation = p;
        next.set(ValueLayout.JAVA_INT, (long) slot * 4, 0);
        setPrev(slot, NONE);
        probationSize--;
    }

    public void addToProtected(int slot) {
        if (headProtected == NONE) {
            headProtected = slot;
            tailProtected = slot;
        } else {
            setNext(slot, headProtected);
            setPrev(headProtected, slot);
            headProtected = slot;
        }
        setSegment(slot, PROTECTED);
        protectedSize++;
    }

    public int pollProtected() {
        if (tailProtected == NONE)
            return 0;
        int slot = tailProtected;
        removeProtected(slot);
        return slot;
    }

    public void removeProtected(int slot) {
        int p = getPrev(slot);
        int n = getNext(slot);
        if (p != NONE)
            setNext(p, n);
        else
            headProtected = n;
        if (n != NONE)
            setPrev(n, p);
        else
            tailProtected = p;
        next.set(ValueLayout.JAVA_INT, (long) slot * 4, 0);
        setPrev(slot, NONE);
        protectedSize--;
    }

    public boolean remove(int slot) {
        int seg = getSegment(slot);
        if (seg == WINDOW) {
            removeWindow(slot);
            return true;
        } else if (seg == PROBATION) {
            removeProbation(slot);
            return true;
        } else if (seg == PROTECTED) {
            removeProtected(slot);
            return true;
        }
        return false;
    }

    public void moveToHead(int slot, int segType) {
        if (segType == WINDOW && headWindow == slot)
            return;
        if (segType == PROBATION && headProbation == slot)
            return;
        if (segType == PROTECTED && headProtected == slot)
            return;

        if (segType == WINDOW) {
            removeWindow(slot);
            addToWindow(slot);
        } else if (segType == PROBATION) {
            removeProbation(slot);
            addToProbation(slot);
        } else if (segType == PROTECTED) {
            removeProtected(slot);
            addToProtected(slot);
        }
    }
}
