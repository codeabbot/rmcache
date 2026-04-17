# RMCache Implementation Audit

Date: 2026-03-22

Scope:
- Runtime implementation and public API behavior only
- Excludes repo polish, docs quality, packaging, and OSS metadata except where they expose runtime bugs

Validation performed:
- Read core cache, index, allocator, eviction, and serializer paths
- Ran `./gradlew test --rerun-tasks`
- Audited hot update, TTL, ghost-cache, and thread-local cleanup paths

Current assessment:
- The core design is serious and the test suite is broad.
- The library is not implementation-ready for external release yet because several public code paths still have correctness traps.

---

## Status (2026-04-17) — ALL RESOLVED

All 8 findings below have been addressed across three commits. JMH
FairComparisonScaleBenchmark vs the 2026-04-17 pre-remediation baseline:
9/12 metrics improved, 3 flat, 0 regressed beyond 1σ. 581/581 tests pass.

| # | Finding | Commit | Notes |
|---|---------|--------|-------|
| 1 | A1 — silent builder default | `ab749e9` | Added `forStringValues()` / `forByteArrayValues()` typed shortcuts; default path now emits one-time WARN instead of silent fallback. Stricter fail-fast broke 110 legitimate `<String,String>` tests; softer approach honors no-regression rule. |
| 2 | A2 — `TTLPolicy.defaultTTLMs` unused | `02a0e9b` | `EvictionPolicy.getDefaultTTLMs()` captured once at ctor as `final long` + predicted-false branch, so caches with no TTL policy pay ~0 ns. Integration test in `TTLPolicyTest.defaultTTL_appliedOnPutWithoutDuration` (`08a0e15`). |
| 3 | A3 — stale-slot guard missing on serializer path | `02a0e9b` | `expectedKeyHash` param on `updateValueWithSerializer/Writer`; one off-heap int read under the partition lock. Unit tests in `EntryPoolCoverageTest` (`08a0e15`). |
| 4 | A4 — free-before-publish UAF window | `ab749e9` | `EntryPool.SubPool.updateValue` reordered: allocate → write → publish → free (matches sibling `updateValueWithWriter/Serializer`). |
| 5 | A5 — TTL mutation breaks wheel order | `02a0e9b` | `EvictionPolicy.onTTLUpdate()` reschedules (duplicate wheel entry); `OffHeapTimingWheel.pollExpiredOne` does siftDown-on-poll to repair stale head. Gated by `tracksTTL()` so benchmark caches pay nothing. Direct wheel test in `TTLPolicyTest.pollExpiredOne_afterInPlaceTTLMutation_drainsShortenedSlot` (`08a0e15`). |
| 6 | A6 — `cleanupThreadLocals` incomplete | `ab749e9` | Promoted `OffHeapCacheImpl.CacheContext` ThreadLocal to `static`; `removeThreadLocals()` now clears it in addition to `ThreadLocalKeyBuffer`. |
| 7 | A7 — `EntryMetadata` fabricated fields | `ab749e9` | Removed `getCreatedAtSeconds()` / `getAgeMillis()` from the interface. Tracking creation time would cost 8 GB @ 1B entries for no first-party use; use TTLs for age-based eviction. |
| 8 | A8 — dead builder API | `ab749e9` | `withCacheName` now plumbed through `OffHeapCache.getCacheName()` / `toString()`; `zeroMemoryOnStartup` triggers a one-time `segment.fill((byte)0)` in `SlabAllocator`. |

See `build/jmh-runs/phase1_comparison.md` and `build/jmh-runs/phase2_comparison.md` for per-metric deltas. The Changelog in `ISSUE_TRACKER.md` has the commit-level summary.

---

## Findings

### 1. High: generic builder defaults are unsafe and can create caches that fail on first use

Evidence:
- `CacheBuilder.build()` falls back to `STRING_KEY` and `STRING_VALUE` for any omitted serializers in `src/main/java/com/codeabbot/rmcache/CacheBuilder.java:336-341`
- The hot path then trusts the chosen serializer in `src/main/java/com/codeabbot/rmcache/OffHeapCacheImpl.java:383-389`

Impact:
- `new CacheBuilder<String, byte[]>().build()` compiles, but runtime operations will route `byte[]` values through the String serializer path.
- This is a public API correctness problem, not just a documentation issue.

Why it matters:
- A builder should either infer safe defaults only for matching types or fail fast when serializers are required.

Recommended fix:
- Fail fast unless the builder can prove `K`/`V` match a supported default serializer.
- Or add explicit factory methods for common typed configurations.

Missing test:
- No test exercises `CacheBuilder<String, byte[]>` without `valueSerializer(...)`.

### 2. High: `TTLPolicy` does not apply its advertised default/global TTL

Evidence:
- `defaultTTLMs` is stored but not used in `src/main/java/com/codeabbot/rmcache/eviction/TTLPolicy.java:43-45`
- `TTLPolicy.onAdd(...)` only schedules the slot and assumes expiry was already written elsewhere in `src/main/java/com/codeabbot/rmcache/eviction/TTLPolicy.java:100-107`
- `OffHeapCacheImpl` only writes an expiry when the caller passes `put(..., Duration)` in `src/main/java/com/codeabbot/rmcache/OffHeapCacheImpl.java:391`

Impact:
- Wiring `TTLPolicy` through the builder does not give entries a default TTL.
- Entries inserted with plain `put(key, value)` get `expiresAt = 0` and never expire through the TTL policy.

Why it matters:
- This breaks the meaning of a public policy type and makes “global TTL” configurations silently ineffective.

Recommended fix:
- TTLPolicy needs a real integration path that can stamp default expiry on insert, or the API needs to be narrowed so only per-entry TTL is supported.

Missing test:
- No integration test covers `CacheBuilder.eviction(new TTLPolicy(...))` with normal `put(key, value)`.

### 3. High: stale-slot protection is missing on the `SegmentValueSerializer` update path

Evidence:
- `OffHeapCacheImpl` claims the update passes a key-hash guard in `src/main/java/com/codeabbot/rmcache/OffHeapCacheImpl.java:398-403`
- But the guarded overload exists only for `updateValueWithLen(...)` in `src/main/java/com/codeabbot/rmcache/index/EntryPool.java:335-343`
- `updateValueWithSerializer(...)` has no `expectedKeyHash` check in `src/main/java/com/codeabbot/rmcache/index/EntryPool.java:351-354`
- The serializer fast path itself does no equivalent validation in `src/main/java/com/codeabbot/rmcache/index/EntryPool.java:744-796`

Impact:
- If a slot obtained from the ghost-cache fast path is freed and reallocated to another key before update, the serializer path can update the wrong entry.
- This affects the exact high-performance path used by `SegmentValueSerializer`, including the built-in byte-array serializer.

Why it matters:
- This is a correctness issue in a hot concurrent update path.

Recommended fix:
- Add the same key-hash validation to serializer and writer update paths.
- Prefer validating the actual key, not only the hash, when feasible.

Missing test:
- No race-focused test covers stale off-heap ghost slot reuse with `SegmentValueSerializer`.

### 4. High: one reallocation path frees the old native block before publishing the new slot offset

Evidence:
- In `EntryPool.SubPool.updateValue(...)`, the old block is freed at `src/main/java/com/codeabbot/rmcache/index/EntryPool.java:669-670`
- The new offset is not published until `src/main/java/com/codeabbot/rmcache/index/EntryPool.java:678-679`

Impact:
- During that window, readers still consult the old offset from the slot table even though the old block has already been returned to the allocator.
- The existing “check offset before/after read” logic cannot detect this case because the offset entry still points to the freed address until the final swap.

Why it matters:
- This creates a real use-after-free window for concurrent `get()` on value-resize updates in the byte-array/serialized-value path.

Recommended fix:
- Publish the new offset first, then free the old block, matching the safer order already used in `updateValueWithWriter(...)` and `updateValueWithSerializer(...)`.

Missing test:
- No concurrent test stresses `get()` vs resizing update on the `updateValueWithLen(...)` path.

### 5. Medium-High: updating TTL on an existing slot can break timing-wheel ordering

Status:
- Inferred from code path; not directly reproduced in a dedicated test.

Evidence:
- Existing-slot update changes expiry in place via `entryPool.setExpiresAt(...)` in `src/main/java/com/codeabbot/rmcache/OffHeapCacheImpl.java:409-412`
- `TTLPolicy.onAccess(...)` is a no-op in `src/main/java/com/codeabbot/rmcache/eviction/TTLPolicy.java:96-98`
- `OffHeapTimingWheel.pollExpiredOne()` only examines the current heap head in `src/main/java/com/codeabbot/rmcache/eviction/OffHeapTimingWheel.java:164-178`

Impact:
- If the expiry of a scheduled slot is extended or shortened without reheapifying, the min-heap invariant can become false.
- A now-future head can block unrelated expired entries deeper in the same stripe.

Why it matters:
- TTL enforcement for untouched entries can be delayed arbitrarily after updates.

Recommended fix:
- Any expiry mutation for a scheduled slot must reschedule or otherwise restore heap order.

Missing test:
- No integration test covers updating TTL on an existing key while a TTL policy is active.

### 6. Medium: `cleanupThreadLocals()` only cleans key buffers, not the main per-thread value buffer

Evidence:
- The public contract promises cleanup of per-thread buffers in `src/main/java/com/codeabbot/rmcache/OffHeapCache.java:188-200`
- The cache keeps its own per-thread `CacheContext.valueBuffer` in `src/main/java/com/codeabbot/rmcache/OffHeapCacheImpl.java:73-88`
- The actual cleanup hook only removes `ThreadLocalKeyBuffer` in `src/main/java/com/codeabbot/rmcache/OffHeapCacheImpl.java:116-117`
- `ThreadLocalKeyBuffer.cleanup()` only removes its own thread-local in `src/main/java/com/codeabbot/rmcache/util/ThreadLocalKeyBuffer.java:79-85`

Impact:
- In long-lived thread pools, the main serialization/read buffer can remain pinned even after callers invoke the documented cleanup API.

Why it matters:
- The public leak-mitigation contract is incomplete.

Recommended fix:
- Also remove the cache’s `context` thread-local, or centralize thread-local ownership so cleanup is complete.

Missing test:
- Existing cleanup tests only assert “does not throw”; they do not verify buffer release.

### 7. Medium: `EntryMetadata` exposes fields the implementation cannot currently provide truthfully

Evidence:
- `EntryMetadata` promises creation time and age in `src/main/java/com/codeabbot/rmcache/eviction/EntryMetadata.java:21-30`
- The only live implementation returns `0` for both in `src/main/java/com/codeabbot/rmcache/OffHeapCacheImpl.java:947-959`

Impact:
- Any user eviction filter relying on `getCreatedAtSeconds()` or `getAgeMillis()` will make incorrect decisions.

Why it matters:
- Public metadata APIs should not fabricate values.

Recommended fix:
- Either store the missing metadata off-heap and return real values, or remove those methods from the contract.

Missing test:
- Existing filter tests only check the key-based veto path and do not validate metadata correctness.

### 8. Low-Medium: two public builder options are currently no-ops

Evidence:
- `zeroMemoryOnStartup()` and `withCacheName()` only set fields in `src/main/java/com/codeabbot/rmcache/CacheBuilder.java:60-64` and `src/main/java/com/codeabbot/rmcache/CacheBuilder.java:248-255`
- Those fields are never consumed on the build path in `src/main/java/com/codeabbot/rmcache/CacheBuilder.java:343-382`

Impact:
- Users can configure options that do nothing.

Why it matters:
- Dead public API creates false expectations and raises maintenance cost.

Recommended fix:
- Implement the behavior or remove/deprecate the surface before release.

## What looked solid

- Native index/allocator lifecycle is broadly covered by tests.
- Hash-table, entry-pool, allocator, zero-copy, and policy components all have meaningful direct test coverage.
- `./gradlew test --rerun-tasks` completed successfully during this audit.

## Priority order before external release

1. Fix serializer defaulting so invalid builder configurations fail fast.
2. Fix TTL semantics: default/global TTL and rescheduling on TTL update.
3. Add stale-slot validation to all update paths.
4. Fix the free-before-publish ordering bug in `updateValue(...)`.
5. Make thread-local cleanup complete.
6. Remove or implement dead public API and misleading metadata methods.

