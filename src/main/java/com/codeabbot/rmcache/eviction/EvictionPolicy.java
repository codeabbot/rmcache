package com.codeabbot.rmcache.eviction;

import com.codeabbot.rmcache.index.EntryPool;

/**
 * Pluggable eviction policy interface.
 */
public interface EvictionPolicy extends AutoCloseable {
    /** Called when entry is accessed (get/put) */
    void onAccess(int slot, int keyHash);

    /** Called when new entry is added */
    void onAdd(int slot, int keyHash, short priority);

    /** Called when entry is removed */
    void onRemove(int slot);

    /** Select next victim for eviction. Return 0 if none. */
    int selectVictim();

    /** Check if eviction is needed */
    boolean shouldEvict();

    /** Process any pending buffered operations */
    default void drainBuffers() {
    }

    /**
     * M1: Compact internal data structures (e.g., timing wheel lazy-cancelled
     * entries)
     */
    default void compact() {
    }

    /** Number of entries tracked */
    int size();

    /** Maximum entries allowed (-1 for unlimited) */
    int getMaxEntries();

    /** Inject EntryPool for intrusive policies */
    default void setEntryPool(EntryPool pool) {
    }

    /** Free resources */
    @Override
    default void close() {
    }
}
