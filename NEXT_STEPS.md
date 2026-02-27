# RMCache — Future Enhancements & Next Steps

## Current State (v1.0 — Single-Server)

- **34 fixes** across 5 review passes, **88/88 tests passing**, zero regressions
- Supports up to **~1B entries / 300GB** on a single server
- Near-zero on-heap: all data structures off-heap via Panama FFM API
- Benchmark: **GET 137-498ns**, **PUT 170-488ns** (1.15-1.67× faster than NMA, 2-6× faster than Ehcache)

---

## Phase 1 — Defense-in-Depth (Low Risk)

### 1.1 OffHeapCompactLRU 30-bit Cross-Check
- Add guard in `CacheBuilder.build()` to fail-fast if `slotCapacity > 0x3FFFFFFF`
- Prevents silent LRU corruption if E2E-C1 limit is ever raised
- **Files:** `CacheBuilder.java`

### 1.2 Bounded Executor for Async Ops
- Replace `ForkJoinPool.commonPool()` in `putAsync()`/`getAsync()` with a user-configurable bounded executor
- At 1B-scale throughput, the unbounded submission queue creates GC pressure and latency spikes
- **Files:** `OffHeapCacheImpl.java`, `CacheBuilder.java`

### 1.3 Eviction Filter Re-Admission Improvement
- Add `reAdmitToProbation(slot)` method to `LRUPolicy` / `EvictionPolicy`
- Currently, rejected eviction candidates are re-admitted to the **window** segment via `onAdd()`, which can distort SLRU balance under aggressive filters
- **Files:** `EvictionPolicy.java`, `LRUPolicy.java`, `OffHeapCacheImpl.java`

---

## Phase 2 — Clustering & Horizontal Scale

### 2.1 Lift `int` Slot Capacity to `long`
- Current `slotCapacity` is `int` — capped at ~2.1B via E2E-C1 guard
- Clustering phase needs sharded slot spaces across nodes, each with its own int-range
- **Files:** `EntryPool.java`, `OffHeapCacheImpl.java`, `LRUPolicy.java`, `OffHeapCompactLRU.java`

### 2.2 Expand AllocationHandle to 48-bit Offset
- Current packed format: 40-bit offset = **1TB max** slab region
- Expand to 48 bits (256TB) for large-memory nodes or clustered aggregation
- **Files:** `AllocationHandle.java`, `EntryPool.java`

### 2.3 Slot Pinning / RefCount for CacheValueView
- `CacheValueView` (from `getView()`) has an inherent TOCTOU: the underlying slot can be evicted between the validity check and the read
- Add a lightweight pin/refcount mechanism so pinned slots are excluded from eviction
- **Files:** `CacheValueViewImpl.java`, `EntryPool.java`, `OffHeapCacheImpl.java`

### 2.4 Distributed Hash Table / Consistent Hashing
- Shard key space across cluster nodes using consistent hashing (e.g., jump hash or rendezvous hashing)
- Each node manages its own `SlabAllocator` + `EntryPool` + `OffHeapHashTable`
- **New files:** `ClusterManager.java`, `NodeRegistry.java`, `ConsistentHashRouter.java`

### 2.5 Replication & Failover
- Async replication of PUT operations to replica nodes
- Leader election for shard ownership on node failure
- **New files:** `ReplicationManager.java`, `ShardLeaderElection.java`

---

## Phase 3 — Observability & Production Ops

### 3.1 Metrics Integration
- Expose cache metrics (hit rate, eviction rate, memory usage) via Micrometer / Prometheus
- Previously prototyped and rolled back due to performance overhead — revisit with async recording
- **Files:** `OffHeapCacheImpl.java`, new `MetricsRecorder.java`

### 3.2 Cache Warming / Persistence
- Snapshot cache state to disk for warm restarts
- Memory-mapped file backing for the slab region
- **New files:** `CacheSnapshot.java`, `MmapSlabAllocator.java`

### 3.3 Admin API
- JMX or HTTP endpoint for runtime cache inspection, manual eviction, and configuration changes
- **New files:** `CacheAdminMBean.java`, `CacheAdminHttpHandler.java`

---

## Known Limits (Single-Server Phase)

| Limit | Value | Root Cause |
|-------|-------|------------|
| Max entries | ~2.1B | `int slotCapacity` (E2E-C1 guard) |
| Max slab region | 1TB | `AllocationHandle` 40-bit offset |
| Max LRU slots per shard | ~1.07B | `OffHeapCompactLRU` 30-bit NEXT_MASK |
| Max value for reusable buffer | 256KB | `CacheContext.MAX_BUFFER_SIZE` (>256KB uses one-off alloc) |
| `clear()` complexity | O(N) | Sequential slot iteration |
| `getView()` safety | TOCTOU | No slot pinning (use `toByteArray()` for safety) |
