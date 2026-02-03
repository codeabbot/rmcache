package com.codeabbot.rmcache.eviction;

import com.codeabbot.rmcache.memory.NativeMemory;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Off-Heap implementation of CompactLRU (SLRU policy).
 * Stores next/prev pointers and segment flags in native memory.
 * 
 * @author Rabindra Meher
 */
public class OffHeapCompactLRU implements AutoCloseable {
    static final int NONE = 0;
    static final int WINDOW = 1;
    static final int PROBATION = 2;
    static final int PROTECTED = 3;

    final int capacity;

    // Native memory segments
    private final MemorySegment next;
    private final MemorySegment prev;
    private final MemorySegment segment;

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
        this.capacity = capacity;
        // Allocate native memory for arrays (1-based indexing, so capacity + 1)
        long size = capacity + 1;
        this.next = NativeMemory.calloc(size, ValueLayout.JAVA_INT.byteSize());
        this.prev = NativeMemory.calloc(size, ValueLayout.JAVA_INT.byteSize());
        this.segment = NativeMemory.calloc(size, ValueLayout.JAVA_BYTE.byteSize());
    }

    @Override
    public void close() {
        NativeMemory.free(next);
        NativeMemory.free(prev);
        NativeMemory.free(segment);
    }

    public int getSegment(int slot) {
        return segment.get(ValueLayout.JAVA_BYTE, slot);
    }

    private void setSegment(int slot, int value) {
        segment.set(ValueLayout.JAVA_BYTE, slot, (byte) value);
    }

    private int getNext(int slot) {
        return next.get(ValueLayout.JAVA_INT, (long) slot * 4);
    }

    private void setNext(int slot, int value) {
        next.set(ValueLayout.JAVA_INT, (long) slot * 4, value);
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
        setNext(slot, NONE);
        setPrev(slot, NONE);
        setSegment(slot, 0);
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
        setNext(slot, NONE);
        setPrev(slot, NONE);
        setSegment(slot, 0);
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
        setNext(slot, NONE);
        setPrev(slot, NONE);
        setSegment(slot, 0);
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
        // Simple optimization: if already head, do nothing
        if (segType == WINDOW && headWindow == slot)
            return;
        if (segType == PROBATION && headProbation == slot)
            return;
        if (segType == PROTECTED && headProtected == slot)
            return;

        // Otherwise unlink and add to head
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
