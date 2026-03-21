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

/**
 * Memory layout for entries in the EntryPool.
 */
public class EntryBlockLayout {
    private EntryBlockLayout() {
    }

    public static final int HEADER_SIZE = 20;
    /** Offset to the start of key bytes = HEADER_SIZE + sizeof(keyLen:4). */
    public static final int DATA_OFFSET = HEADER_SIZE + 4;

    public static int pad(int len) {
        return (len + 3) & ~3;
    }

    public static int computeSize(int keyLen, int valLen) {
        return HEADER_SIZE + 4 + pad(keyLen) + 4 + valLen;
    }
}
