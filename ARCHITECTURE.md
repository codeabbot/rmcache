# RMCache — Architecture & Technical Design

RMCache is a high-performance, billion-scale, near-zero-on-heap caching library for **Java 25+ (LTS)**. It leverages the Java Foreign Function & Memory (FFM) API to manage massive datasets in native memory, eliminating GC pauses even when managing hundreds of gigabytes of data.

---

## 1. System Overview

```
┌──────────────────────────────────────────────────────────────────────┐
│                          OffHeapCacheImpl                            │
│  ┌──────────┐  ┌─────────────┐  ┌──────────┐  ┌────────────────┐   │
│  │  K/V     │  │  Thread-    │  │  Ghost   │  │  Eviction      │   │
│  │  Serial- │  │  Local      │  │  Cache   │  │  Policy        │   │
│  │  izers   │  │  Buffers    │  │  (L1)    │  │  (W-TinyLFU)   │   │
│  └──────────┘  └─────────────┘  └──────────┘  └────────────────┘   │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │                   OffHeapHashTable (L2)                      │   │
│  │  ┌────────┐ ┌────────┐ ┌────────┐  ...  ┌────────┐          │   │
│  │  │Stripe 0│ │Stripe 1│ │Stripe 2│       │Stripe N│          │   │
│  │  │Stamped │ │Stamped │ │Stamped │       │Stamped │          │   │
│  │  │Lock    │ │Lock    │ │Lock    │       │Lock    │          │   │
│  │  └────────┘ └────────┘ └────────┘       └────────┘          │   │
│  └──────────────────────────────────────────────────────────────┘   │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │                      EntryPool                               │   │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐  ┌──────────┐       │   │
│  │  │SubPool 0 │ │SubPool 1 │ │SubPool 2 │..│SubPool N │       │   │
│  │  │Reentrant │ │Reentrant │ │Reentrant │  │Reentrant │       │   │
│  │  └──────────┘ └──────────┘ └──────────┘  └──────────┘       │   │
│  └──────────────────────────────────────────────────────────────┘   │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │                    SlabAllocator                             │   │
│  │  ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐       ┌──────────┐    │   │
│  │  │64B   │ │128B  │ │256B  │ │512B  │  ...  │BuddyAlloc│    │   │
│  │  │class │ │class │ │class │ │class │       │(>64KB)   │    │   │
│  │  └──────┘ └──────┘ └──────┘ └──────┘       └──────────┘    │   │
│  └──────────────────────────────────────────────────────────────┘   │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │                   Native Memory (malloc)                     │   │
│  │  [Slab Region: 75%]                 [Buddy Region: 25%]     │   │
│  └──────────────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────────────┘
```

Three design pillars:
1. **Zero-heap data storage** — keys, values, hash table arrays, LRU lists, and frequency sketch all reside in native memory.
2. **Lock-free or optimistic concurrency** — `StampedLock` optimistic reads, CAS bitmaps, and striped write locks minimize contention.
3. **Scan-resistant eviction** — W-TinyLFU (Window/Probation/Protected SLRU + Count-Min Sketch) prevents cache pollution from one-hit-wonder scans.

---

## 2. Memory Management

### Native Memory Regions

Total native memory (via `NativeMemory.malloc()`) is split at allocation time:

| Region | Share | Purpose |
|--------|-------|---------|
| **Slab Region** | 75% | Fixed-size block allocations (64 B – 64 KB) |
| **Buddy Region** | 25% | Large allocations (>64 KB) |

### Slab Allocator — 11 Size Classes

| Index | Block Size | Blocks per 64 KB Slab |
|-------|-----------|----------------------|
| 0 | 64 B | 1024 |
| 1 | 128 B | 512 |
| 2 | 256 B | 256 |
| 3 | 512 B | 128 |
| 4 | 1 KB | 64 |
| 5 | 2 KB | 32 |
| 6 | 4 KB | 16 |
| 7 | 8 KB | 8 |
| 8 | 16 KB | 4 |
| 9 | 32 KB | 2 |
| 10 | 64 KB | 1 |

Allocations exceeding 64 KB are delegated to the **BuddyAllocator** (`allocateLarge()`).

### `LockFreeSlabManager` — CAS Bitmap Allocation

Each slab uses an `AtomicLongArray` bitmap (1 bit per block):
- Find free block: `Long.numberOfTrailingZeros(~word)` → O(1) amortized
- Claim block: CAS on the bitmap word (only one thread wins)
- A volatile **scan hint** skips known-full words

### Packed Allocation Handle

`allocatePacked()` returns a 64-bit handle — no heap object:

```
Bits 63──56  55──48  47──────────────────────────────────0
     [rsvd ] [ SC  ] [          byte offset             ]
```

- **SC** (8 bits): size class index (0–10)
- **offset** (48 bits): byte offset into the native memory segment

### Entry Block Layout (Off-Heap)

Each cache entry is a contiguous native memory block:

```
Offset   Field              Size       Notes
────────────────────────────────────────────────────────────
 0       Key Hash           4 bytes    Murmur-spread 32-bit hash
 4       Slot ID            4 bytes    Back-reference to EntryPool slot
 8       Expiration         8 bytes    Absolute epoch-ms; 8-byte aligned (0 = no TTL)
16       Size Class         1 byte     Index 0–10 into SIZE_CLASSES
17       Padding            1 byte     Alignment pad
18       Priority           2 bytes    User-supplied eviction priority (short)
20       Key Length         4 bytes    Serialized key byte count
24       Key Data           variable   Serialized key bytes
24+K     Padding            0–7 bytes  Align value to 8-byte boundary
24+K+P   Value Length       4 bytes
28+K+P   Value Data         variable   Serialized value bytes
```

> **Alignment note (ISSUE-017):** `expiresAt` is at offset 8 (8-byte aligned), enabling
> `ValueLayout.JAVA_LONG` for the TTL read. The earlier layout (offset 4, unaligned) required
> `UNALIGNED_LONG` and was 17–37% slower. Slot ID moved to offset 4 (naturally 4-byte aligned).

---

## 3. Indexing Layer

### OffHeapHashTable — Striped Robin Hood

The primary key→slot directory:
- **Robin Hood hashing** — open addressing, displacement on insert minimises probe variance; backward-shift delete avoids tombstones.
- **Striped `StampedLock`** — each stripe has one lock; reads use `tryOptimisticRead()` (no lock acquisition on GET hot path).
- **Packed 64-bit slot entry** — each slot stores `[hash:32 | slotId:32]` in one `long`, enabling a single atomic read.
- **Graceful resizing** — when load factor is exceeded, the stripe doubles. The old `MemorySegment` is wrapped in a `RetiredSegment` record and reclaimed after a 2-second grace period (`RETIRED_GRACE_NANOS`), preventing use-after-free for concurrent optimistic readers.

```
GET path (hot):
  1. spread(keyHash) → stripe index
  2. StampedLock.tryOptimisticRead()
  3. Linear probe — Robin Hood ordering
  4. Validate stamp; if invalid → acquire read lock + retry

PUT path:
  1. spread(keyHash) → stripe index
  2. StampedLock.writeLock()
  3. Robin Hood insert with displacement tracking
  4. Load factor check → async resize if needed
```

### EntryPool — Partitioned Slot Metadata

Maps 32-bit `int` slot IDs to native memory offsets. Divided into N partitions (default 128, power-of-2):
- Each partition has its own `ReentrantLock` and a native-memory free-slot stack (CAS push/pop).
- Allocation hashes to a partition: `(spread(keyHash)) & mask`.
- CAS double-free guard in `SubPool.free()` — if slot is already freed (`packed == -1L`), the CAS fails silently.
- 32-bit slot IDs cap total entries at ~2.1 billion per instance while reducing memory vs 64-bit pointers.

---

## 4. Eviction Layer

### W-TinyLFU (Window TinyLFU)

```
┌──────────┐    ┌──────────────┐    ┌──────────────┐
│  Window  │───▶│  Probation   │───▶│  Protected   │
│   (1%)   │    │    (19%)     │    │    (80%)     │
└──────────┘    └──────────────┘    └──────────────┘
                       │                    │
                Victim ▼              Demote ▼
           ┌──────────────────┐
           │  Frequency       │
           │  Sketch          │
           │  (Count-Min)     │
           └──────────────────┘
```

- **Window** (1%): New entries always enter here.
- **Probation** (19%): Promoted from Window. Candidates for eviction.
- **Protected** (80%): High-frequency entries. Demoted to Probation under eviction pressure.

**Eviction decision:** Pick tail of Probation (round-robin across shards). If the incoming entry's estimated frequency > victim's → evict victim, admit incoming. Otherwise → reject incoming.

### OffHeapCompactLRU

Doubly-linked LRU lists stored entirely in native memory:

```
Per entry: 8 bytes
  next (4B) — top 2 bits = segment flag: 00=WINDOW, 01=PROBATION, 10=PROTECTED
  prev (4B) — 30-bit slot ID

Total: maxSlots × 8 bytes
```

Slot IDs are limited to 30 bits (`0x3FFFFFFF`). `CacheBuilder.build()` enforces this with a fail-fast guard.

### OffHeapFrequencySketch

Count-Min Sketch stored off-heap:

```
Each long holds 16 × 4-bit counters
4 hash functions → 4 different long words
Saturation at 15, periodic halving when sample count > 10 × maxEntries
```

Thread safety: `VarHandle.getVolatile` + single-attempt `compareAndExchange` (approximate by design — no retry needed).

### TTL Eviction — OffHeapTimingWheel

Hierarchical timing wheel stored in native memory. Only expired "ticks" are scanned — avoids O(N) entry iteration. Expiration times are read directly from the entry block (offset 8); no on-heap duplication.

### LRU Access Drain

```
Access recording (hot path — lock-free):
  1. Hash threadId to stripe: threadId & stripeMask
  2. AtomicInteger.getAndIncrement on buffer index
  3. Write slot to int[stripe][index] (4096-slot buffer)

Drain (background — every 10ms):
  1. Collect all stripe buffers (swap index to 0)
  2. Partition slots into per-shard batches (pre-allocated arrays)
  3. Acquire each shard lock once per batch
  4. Promote: Probation → Protected if frequency high
  5. Run maintenanceCallback (background eviction check)
```

---

## 5. Ghost Cache L1

A direct-mapped off-heap array acting as an L1 shortcut:

```
Per slot: 8 bytes — [hash:32 | slotId:32] packed long
Lookup: O(1) — hash & mask → index
Read: single non-volatile 64-bit load (no lock)
Miss: falls through to OffHeapHashTable L2
```

**GhostCacheMode options:**
- `HEAP` — Java `Entry<K,V>[]` array (object references, easy eviction callback)
- `OFF_HEAP` — `OffHeapGhostCache` native array (zero heap)
- `DISABLED` — no L1 shortcut
- `AUTO` — selects `HEAP` by default; `OFF_HEAP` when `zeroHeapProfile()` is set

---

## 6. Zero-Copy Access

Two APIs avoid heap allocation for large value reads:

- **`getZeroCopy(key, processor)`** — passes a `MemorySegment` pointing into live off-heap memory to a user function. Avoids the intermediate `byte[]` copy entirely.
- **`getView(key)`** — returns a `CacheValueView` (a thin wrapper around the native segment). Exposes `getByte`, `getInt`, `getLong` with bounds checking.

> **TOCTOU warning:** Both APIs give direct access to live off-heap memory. If another thread evicts or reallocates the same entry concurrently, the segment may reference freed memory. See [docs/zero-copy-access.md](docs/zero-copy-access.md) for safe usage patterns.

---

## 7. Concurrency Model

| Component | Mechanism |
|-----------|-----------|
| Hash table reads | `StampedLock.tryOptimisticRead()` — lock-free on success |
| Hash table writes | `StampedLock.writeLock()` per stripe |
| Hash table resize | Atomic swap on stripe segment; old segment in grace period queue |
| EntryPool allocation | `ReentrantLock` per partition (128 default) |
| EntryPool free | CAS double-free guard (`compareAndSet` to `-1L`) |
| Slab allocation | `AtomicLongArray` CAS bitmap — fully lock-free |
| LRU access recording | Lock-free stripe buffer write (`AtomicInteger`) |
| LRU list mutation | `ReentrantLock` per LRU shard (up to 64) |
| Eviction counters | `LongAdder` — contention-free per-cause counting |
| TTL clock | `CoarseClock` — single background thread updates volatile `long` |

**No `Unsafe`**: Built exclusively on `java.lang.foreign` APIs.

---

## 8. Background Threads

All threads are daemon threads and do not prevent JVM shutdown.

| Thread Name | Purpose | Interval |
|-------------|---------|----------|
| `rmcache-coarse-clock` | Updates `CoarseClock.now` for TTL reads | 100 ms |
| `rmcache-maintenance` | Hash table cleanup queue drain (retired segments) | 1 s |
| `rmcache-lru-maintenance` | LRU buffer drain + frequency sketch reset + eviction check | 10 ms |
| `rmcache-eviction` | Async eviction processing (background eviction mode) | on-demand |

---

## 9. Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| `malloc` over `Arena` | Raw `malloc` avoids managed Arena overhead; `NativeMemory.UNLIMITED` provides unrestricted segment access with no per-access bounds check overhead |
| Robin Hood hashing | Minimises worst-case probe length; backward-shift delete eliminates tombstones |
| `StampedLock` optimistic reads | GET hot path acquires zero physical locks on success |
| Packed 64-bit slot entries | One `long` read fetches both hash and slot ID; zero heap object |
| Packed 64-bit allocation handles | Encodes offset + size class in one `long`; eliminates `AllocationHandle` heap objects |
| Slab + Buddy hybrid | Slabs for O(1) fixed-size alloc; buddy for rare large allocs with O(log N) coalescing |
| Thread-local key buffers | Eliminates `byte[]` per key serialization on the Latin-1 hot path |
| Coarse clock (100 ms) | Avoids `System.currentTimeMillis()` syscall on every TTL check; ±100ms TTL precision is acceptable |
| Sharded LRU (up to 64 shards) | Eliminates single-lock bottleneck; round-robin victim selection distributes eviction evenly |
| Murmur-style hash spread | `h ^= h>>>16; h *= 0x85ebca6b; h ^= h>>>13` applied at all 5 hash computation sites; prevents clustering on low-entropy keys |
| Entry header alignment | `expiresAt` at offset 8 (8-byte aligned) enables `ValueLayout.JAVA_LONG` — 17–37% faster than unaligned reads |

---

## 10. Near-Zero Heap Guarantee

**Steady-state heap footprint:**

```
Heap ≈ FixedOverhead (~1.5 MB)
     + PerThread      (~260 KB × activeThreads)
     + GhostCache     (~0 in OFF_HEAP or DISABLED mode)
```

**Everything in native memory** at 1B-entry scale:
- Key + value bytes
- Hash table arrays (8 B/slot × slots)
- EntryPool offset arrays (8 B/slot)
- LRU linked lists (8 B/entry)
- Frequency sketch (8 B per 16 counters)
- Timing wheel buckets

**Unavoidable heap allocations** (transient):
- Key serialization `byte[]` — created per `put`/`get`, eligible for GC immediately
- Value copy `byte[]` returned by `get()` — required by JVM type system; use `getZeroCopy`/`getView` to eliminate

---

## 11. File Reference

### Core
| File | Description |
|------|-------------|
| `OffHeapCacheImpl.java` | Main cache facade — orchestrates all components |
| `CacheBuilder.java` | Fluent builder with memory estimation and profile presets |
| `OffHeapCache.java` | Public API interface + `CacheStats` record |

### Memory Management
| File | Description |
|------|-------------|
| `SlabAllocator.java` | Slab allocator with 11 size classes + buddy delegation |
| `BuddyAllocator.java` | Buddy allocator for large (>64 KB) values |
| `LockFreeSlabManager.java` | CAS bitmap for per-slab block management |
| `NativeMemory.java` | FFM `malloc`/`free` wrapper + `UNLIMITED` segment |

### Indexing
| File | Description |
|------|-------------|
| `OffHeapHashTable.java` | Striped Robin Hood hash table with `StampedLock` + graceful resize |
| `EntryPool.java` | Partitioned slot→offset mapping with CAS-guarded free lists |
| `OffHeapGhostCache.java` | Off-heap direct-mapped L1 ghost cache |
| `GhostCache.java` | On-heap ghost cache (`Entry[]` with K/V references) |

### Eviction
| File | Description |
|------|-------------|
| `LRUPolicy.java` | Sharded W-TinyLFU with async buffer drain |
| `OffHeapCompactLRU.java` | Off-heap doubly-linked list for Window/Probation/Protected segments |
| `OffHeapFrequencySketch.java` | Off-heap Count-Min Sketch (4-bit counters, VarHandle CAS) |
| `TTLPolicy.java` | Time-to-live eviction via `OffHeapTimingWheel` |
| `CompositePolicy.java` | Combiner for LRU + TTL policies |

### Utilities
| File | Description |
|------|-------------|
| `CoarseClock.java` | Reference-counted ~100 ms-granularity clock |
| `ThreadLocalKeyBuffer.java` | Zero-allocation Latin-1 key encoding with thread-local cleanup |
| `Prefetch.java` | CPU cache prefetching via FFM |
| `MemoryEstimator.java` | Off-heap memory budgeting and index sizing |
