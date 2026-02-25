# RMCache — Technical Details

Deep technical reference for the internal architecture and data structures of RMCache.

---

## Architecture Overview

```
┌──────────────────────────────────────────────────────────────────────┐
│                          OffHeapCacheImpl                           │
│  ┌──────────┐  ┌─────────────┐  ┌──────────┐  ┌────────────────┐  │
│  │  K/V     │  │  Thread-    │  │  Ghost   │  │  Eviction      │  │
│  │Serial-   │  │  Local      │  │  Cache   │  │  Policy        │  │
│  │izers     │  │  Buffers    │  │  (L1)    │  │  (W-TinyLFU)   │  │
│  └──────────┘  └─────────────┘  └──────────┘  └────────────────┘  │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │                   OffHeapHashTable                           │  │
│  │  ┌────────┐ ┌────────┐ ┌────────┐         ┌────────┐       │  │
│  │  │Stripe 0│ │Stripe 1│ │Stripe 2│  ...    │Stripe N│       │  │
│  │  │Stamped │ │Stamped │ │Stamped │         │Stamped │       │  │
│  │  │Lock    │ │Lock    │ │Lock    │         │Lock    │       │  │
│  │  └────────┘ └────────┘ └────────┘         └────────┘       │  │
│  └──────────────────────────────────────────────────────────────┘  │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │                      EntryPool                              │  │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐   ┌──────────┐     │  │
│  │  │SubPool 0 │ │SubPool 1 │ │SubPool 2 │...│SubPool N │     │  │
│  │  │Reentrant │ │Reentrant │ │Reentrant │   │Reentrant │     │  │
│  │  └──────────┘ └──────────┘ └──────────┘   └──────────┘     │  │
│  └──────────────────────────────────────────────────────────────┘  │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │                    SlabAllocator                             │  │
│  │  ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐        ┌──────────┐   │  │
│  │  │64B   │ │128B  │ │256B  │ │512B  │  ...   │BuddyAlloc│   │  │
│  │  │class │ │class │ │class │ │class │        │(>64KB)    │   │  │
│  │  └──────┘ └──────┘ └──────┘ └──────┘        └──────────┘   │  │
│  └──────────────────────────────────────────────────────────────┘  │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │                   Native Memory (malloc)                    │  │
│  │  [Slab Region: 75%] [Buddy Region: 25%]                    │  │
│  └──────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Memory Layout

### Native Memory Regions

The total native memory (via `NativeMemory.malloc()`) is divided into:

| Region | Share | Purpose |
|--------|-------|---------|
| **Slab Region** | 75% | Fixed-size block allocations (64B – 64KB) |
| **Buddy Region** | 25% | Large allocations (>64KB) |

### Size Classes

The slab allocator uses 11 size classes:

| Index | Block Size | Blocks/Slab (64KB slab) |
|-------|-----------|------------------------|
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

Allocations exceeding 64KB are delegated to the **BuddyAllocator**.

### Entry Block Layout (Off-Heap)

Each cache entry is stored as a contiguous block in native memory:

```
Offset  Field                Size      Description
─────────────────────────────────────────────────────
0       Key Hash (int)       4 bytes   Full 32-bit hash
4       Expiration (long)    8 bytes   Absolute ms (0 = no TTL)
12      Slot ID (int)        4 bytes   Back-reference to slot
16      Size Class (byte)    1 byte    Index into SIZE_CLASSES
17      Fingerprint (short)  2 bytes   Compact key fingerprint
19      Padding              1 byte    Alignment
20      Key Length (int)     4 bytes   Key byte length
24      Key Data             variable  Serialized key bytes
24+K    Padding              0–7 bytes 8-byte alignment
24+K+P  Value Length (int)   4 bytes
28+K+P  Value Data           variable  Serialized value bytes
```

Total entry size = 28 + keyLen + padding + valueLen, rounded up to the nearest size class.

### Packed Slot ID Format (EntryPool → OffHeapHashTable)

The hash table stores 8-byte **packed slots**:

```
Bits 63────────────32  31─────────────0
     [  32-bit hash  ] [  32-bit slot  ]
```

This allows single 64-bit atomic reads/writes and avoids separate hash + pointer fields.

### Packed Allocation Handle (SlabAllocator)

The `allocatePacked()` method returns a 64-bit handle:

```
Bits 63──56  55──48  47──────────────0
     [rsvd ] [ SC  ] [   offset     ]
```

- **SC**: Size class index (0–10)
- **offset**: Byte offset into the native memory segment
- This avoids allocating an `AllocationHandle` object on the heap.

---

## Concurrency Model

### Hash Table: Optimistic Reads + Striped Write Locks

```
GET path (hot):
  1. Compute stripe index: hash >>> stripeShift
  2. StampedLock.tryOptimisticRead()
  3. Linear probe with Robin Hood ordering
  4. Validate stamp — if invalid, acquire read lock and retry

PUT path:
  1. Compute stripe index
  2. StampedLock.writeLock()
  3. Robin Hood insert with displacement
  4. Check load factor → resize if needed (async cleanup of old table)
```

The number of stripes defaults to `max(16384, numStripes)` — providing extreme concurrency.

### Entry Pool: Partitioned Free Lists

The `EntryPool` is divided into `N` partitions (default 128, power-of-2). Each partition has:
- Its own `ReentrantLock`
- A native-memory free-slot stack (CAS-based push/pop)
- Independent slot ID space (partition prefix)

Allocation hashes to a partition via `(keyHash ^ (keyHash >>> 16)) & mask`, distributing evenly.

### Slab Allocator: Lock-Free Bitmap CAS

Each slab is managed by a `LockFreeSlabManager` with an `AtomicLongArray` bitmap:
- Each bit represents one block (0=free, 1=used)
- Allocation uses `Long.numberOfTrailingZeros(~word)` to find free bits
- CAS ensures only one thread claims each block
- A **scan hint** (`volatile int`) skips known-full words for O(1) amortized allocation

### LRU Policy: Sharded + Async Drain

```
Access recording (hot path):
  1. Hash thread ID to stripe: threadId & stripeMask  
  2. AtomicInteger.getAndIncrement on buffer index
  3. Write slot ID to int[stripe][index]
  4. If buffer full → trigger async drain

Drain (background — every 10ms):
  1. For each stripe, swap buffer index to 0
  2. Acquire per-shard ReentrantLock
  3. Move accessed entries: Probation → Protected (if frequency high)
  4. Reset frequency sketch counters if needed
```

This design avoids any lock acquisition on the read hot path.

---

## W-TinyLFU Eviction

RMCache implements the **Window TinyLFU** policy:

```
┌──────────┐    ┌──────────────┐    ┌──────────────┐
│  Window   │───▶│  Probation   │───▶│  Protected   │
│  (1%)     │    │  Segment     │    │  (80%)       │
└──────────┘    └──────────────┘    └──────────────┘
                       │                    │
                Victim ▼              Demote ▼
           ┌──────────────────┐
           │  Frequency       │
           │  Sketch          │
           │  (Count-Min)     │
           └──────────────────┘
```

- **Window** (1%): New entries enter here
- **Probation** (19%): Entries must prove frequency to be promoted
- **Protected** (80%): High-frequency entries; demoted to probation on eviction pressure
- **Frequency Sketch**: Off-heap Count-Min Sketch with 4-bit counters packed into `long` values

### Eviction Decision

When the cache is full:
1. Pick victim from **probation tail** (round-robin across shards)
2. Compare `frequency(victim)` vs `frequency(incoming)`
3. If incoming has higher frequency → evict victim, admit incoming
4. Otherwise → reject incoming

---

## Off-Heap Data Structures

### OffHeapCompactLRU

Stores LRU `next`/`prev` pointers in native memory:

```
Per entry: 8 bytes (next: 4B, prev: 4B)
Segment type packed in top 2 bits of `next`:
  00 = WINDOW
  01 = PROBATION  
  10 = PROTECTED
```

Total memory: `maxSlots × 8 bytes`

### OffHeapFrequencySketch

Count-Min Sketch stored off-heap:

```
Layout: long[] array in native memory
Each long holds 16 × 4-bit counters
4 hash functions, each indexes into a different long
```

Counter saturation at 15, periodic halving (`reset()`) when `sampleSize > 10 × maxEntries`.

### OffHeapGhostCache

Direct-mapped ghost cache in native memory:

```
Per entry: 8 bytes (4B hash + 4B slot)
Lookup: O(1) — hash & mask → index
```

Stores recently evicted key hashes to detect "one-hit-wonder" vs. "recurring" access patterns.

---

## Background Threads

| Thread | Purpose | Interval |
|--------|---------|----------|
| `rmcache-coarse-clock` | Updates `CoarseClock.now` | 100ms |
| `rmcache-maintenance` | Hash table cleanup queue drain | 1s |
| `rmcache-lru-maintenance` | LRU buffer drain + frequency reset | 10ms |
| `rmcache-eviction` | Async eviction processing | On-demand |

All threads are daemon threads and will not prevent JVM shutdown.

---

## Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| `malloc` over `Arena` | Raw `malloc` avoids managed Arena overhead; `NativeMemory.UNLIMITED` provides unrestricted segment |
| Robin Hood hashing | Reduces worst-case probe length; enables backward-shift delete (no tombstones) |
| StampedLock over ReentrantReadWriteLock | Optimistic reads avoid lock acquisition on GET hot path |
| Packed 64-bit handles | Avoids `AllocationHandle` object allocation; encodes offset + size class in one long |
| Slab + Buddy hybrid | Slabs for fast fixed-size alloc (O(1)), buddy for rare large allocs with coalescing |
| Thread-local key buffers | Eliminates `byte[]` allocation per operation; reused across calls |
| Coarse clock (100ms) | Avoids `System.currentTimeMillis()` syscall overhead; acceptable for TTL granularity |
| Sharded LRU | Eliminates single-lock bottleneck; round-robin victim selection distributes eviction |

---

## File Index

### Core
| File | Description |
|------|-------------|
| `OffHeapCacheImpl.java` | Main cache facade — orchestrates all components |
| `CacheBuilder.java` | Fluent builder API for cache construction |
| `CacheContext.java` | Thread-local context (value buffer + counters) |

### Memory Management
| File | Description |
|------|-------------|
| `SlabAllocator.java` | Slab-based allocator with 11 size classes |
| `BuddyAllocator.java` | Buddy allocator for large (>64KB) entries |
| `LockFreeSlabManager.java` | CAS-based bitmap for single slab management |
| `AllocationHandle.java` | Packed allocation handle (offset + capacity + SC) |
| `NativeMemory.java` | JNI `malloc`/`free` wrapper via FFM |

### Indexing
| File | Description |
|------|-------------|
| `OffHeapHashTable.java` | Striped Robin Hood hash table with StampedLock |
| `EntryPool.java` | Partitioned slot-to-offset mapping with free lists |
| `EntryBlockLayout.java` | Constants for entry memory layout |
| `OffHeapGhostCache.java` | Off-heap direct-mapped ghost cache |
| `GhostCache.java` | On-heap ghost cache (Entry[] with K/V references) |

### Eviction
| File | Description |
|------|-------------|
| `LRUPolicy.java` | Sharded W-TinyLFU with async buffer drain |
| `OffHeapCompactLRU.java` | Off-heap doubly-linked list for LRU segments |
| `OffHeapFrequencySketch.java` | Off-heap Count-Min Sketch (4-bit counters) |
| `TTLPolicy.java` | Time-to-live eviction with ConcurrentHashMap tracking |
| `TimingWheel.java` | Priority queue for TTL scheduling |
| `CompositePolicy.java` | Combiner for multiple eviction policies |

### Utilities
| File | Description |
|------|-------------|
| `CoarseClock.java` | Low-overhead ~100ms-granularity clock |
| `ThreadLocalKeyBuffer.java` | Zero-allocation string key encoding |
| `ThreadLocalValueBuffer.java` | Reusable value byte buffer |
| `Prefetch.java` | CPU cache prefetching via FFM |
