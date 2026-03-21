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
