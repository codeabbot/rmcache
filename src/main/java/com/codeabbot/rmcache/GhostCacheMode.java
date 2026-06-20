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
package com.codeabbot.rmcache;

/**
 * Configuration for L1 ghost-cache behavior.
 */
public enum GhostCacheMode {
    /** Production default: resolves to {@link #OFF_HEAP}. */
    AUTO,
    /** Heap-backed shortcut. Opt in only when a heap-resident L1 is acceptable. */
    HEAP,
    /** Off-heap shortcut storing hash-to-slot mappings in native memory. */
    OFF_HEAP,
    /** Disable the ghost cache shortcut. */
    DISABLED
}
