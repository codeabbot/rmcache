# RMCache — Developer Guide

A high-performance, off-heap Java cache built on the Java FFM (Foreign Function & Memory) API. RMCache stores all key/value data in native memory, keeping the Java heap small and GC pressure low.

---

## Quick Start

```java
import com.codeabbot.rmcache.*;
import com.codeabbot.rmcache.serializer.*;

OffHeapCache<String, byte[]> cache = new CacheBuilder<String, byte[]>()
    .offHeapMemory(Units.gigabytes(2))            // 2GB native memory
    .maxEntries(1_000_000)                         // Up to 1M entries
    .stringKeyEncoding(StringEncoding.LATIN1)      // Fast Latin-1 key encoding
    .valueSerializer(BuiltInSerializers.byteArray())
    .build();

// Basic operations
cache.put("user:42", userData);
byte[] result = cache.get("user:42");
cache.remove("user:42");

// Always close when done (frees native memory)
cache.close();
```

## Requirements

- **JDK 25+** (Java FFM API)
- **Gradle 9.x** for building

## Building & Testing

```bash
# Build
./gradlew build

# Run tests
./gradlew test

# Run JMH benchmarks
./gradlew jmh -Pjmh.includes="RMCacheOnlyBenchmark"
```

---

## Configuration Reference

### CacheBuilder Options

| Method | Type | Default | Description |
|--------|------|---------|-------------|
| `.offHeapMemory(long)` | bytes | — | **Required.** Total native memory budget |
| `.maxEntries(int)` | int | — | **Required.** Maximum number of entries |
| `.stringKeyEncoding(StringEncoding)` | enum | `NONE` | `LATIN1` for fast zero-alloc key encoding |
| `.keySerializer(KeySerializer<K>)` | — | — | Custom key serializer |
| `.valueSerializer(ValueSerializer<V>)` | — | — | Custom value serializer |
| `.eviction(EvictionPolicy)` | — | `NoEvictionPolicy` | Eviction policy (LRU, TTL, Composite) |
| `.ghostCacheMode(GhostCacheMode)` | enum | `ON_HEAP` | Ghost cache strategy |
| `.ghostCacheSize(int)` | int | `4096` | Ghost cache slot count |
| `.hashTableLoadFactor(double)` | double | `0.75` | Hash table load factor |
| `.hashTableStripes(int)` | int | auto | Number of hash table stripes (power of 2) |
| `.entryPoolPartitions(int)` | int | `128` | Number of entry pool partitions (power of 2) |
| `.enablePrefetch(boolean)` | boolean | `true` | Enable CPU cache prefetching |

### Eviction Policies

```java
// LRU with W-TinyLFU admission (recommended)
.eviction(new LRUPolicy(maxEntries))

// TTL-based expiry (requires EntryPool — passed internally by CacheBuilder)
.eviction(new TTLPolicy(Duration.ofMinutes(5).toMillis(), entryPool))

// Combined LRU + TTL
.eviction(new CompositePolicy(lruPolicy, ttlPolicy))

// No eviction (manual management)
.eviction(new NoEvictionPolicy())
```

### Serializers

```java
// Built-in serializers
BuiltInSerializers.byteArray()
BuiltInSerializers.string()

// Custom serializer
new ValueSerializer<MyType>() {
    public byte[] serialize(MyType value) { ... }
    public MyType deserialize(byte[] bytes) { ... }
}
```

---

## Memory Budgeting

### On-Heap Memory (Java Heap)

RMCache aims to minimize heap usage but still requires some Java objects. Here's the complete breakdown:

#### Per-Thread Allocations

| Component | Size | When |
|-----------|------|------|
| `CacheContext.valueBuffer` | **256 KB** | Per thread touching the cache |
| `ThreadLocalKeyBuffer` | 4 KB | Per thread (Latin-1 mode) |
| `ThreadLocalValueBuffer` | 256 KB | Per thread using value buffer |

**Total per thread: ~260 KB – 516 KB** depending on code path.

> **Formula:** `heapPerThread ≈ 260 KB × numActiveThreads`

#### Fixed Allocations (Per Cache Instance)

| Component | Size | Notes |
|-----------|------|-------|
| `StampedLock[]` | `numStripes × ~80B` | Hash table stripe locks |
| `int[]` × 3 | `numStripes × 12B` | tableMasks, capacities, counts |
| `MemorySegment[]` | `numStripes × 8B` | Stripe segment references |
| `ReentrantLock[]` | `shardCount × ~80B` | LRU shard locks |
| `LRUPolicy` access buffers | `numStripes × 4096 × 4B` | ~1 MB typical (64 stripes) |
| `AtomicInteger[]` | `numStripes × ~16B` | Buffer indices |
| `LongAdder` × 6+ | ~500 B | Hit/miss/eviction counters |
| `ScheduledExecutor` × 2–3 | ~5 KB | Maintenance + eviction threads |
| `SizeClassState` × 11 | ~2 KB | Slab allocator per-class state |
| `SubPool[]` × 128 | `128 × ~100B ≈ 12 KB` | EntryPool partition metadata |

#### Variable Allocations (Grows with Usage)

| Component | Size | Notes |
|-----------|------|-------|
| `GhostCache` Entry[] | `ghostCacheSize × ~40B` | Only if `ON_HEAP` mode |
| `SlabAllocator` largeAllocations | ~80B per large (>64KB) entry | ConcurrentHashMap |
| `LockFreeSlabManager` bitmaps | 8B per 64 blocks per slab | AtomicLongArray |
| `OffHeapHashTable` cleanupQueue | varies | Retired table segments |

> [!TIP]
> **TTL is now zero-heap.** The `OffHeapTimingWheel` stores slot IDs in a native `MemorySegment` (4 bytes/entry). Expiration times are read directly from the `EntryPool` entry block — no on-heap duplication.

### Summary Formula

```
Heap ≈ FixedOverhead (~1.5 MB)
     + PerThread      (~260 KB × threads)
     + Ghost cache    (~40B × ghostSize)  [if ON_HEAP mode]
```

### Example: 1M Entries, 8 Threads, TTL Enabled

| Component | Heap Usage |
|-----------|-----------|
| Fixed overhead | ~1.5 MB |
| Thread-local buffers (8 threads) | ~2.0 MB |
| TTL (off-heap wheel) | **0 MB on heap** (~4 MB native) |
| **Total** | **~3.5 MB** |

> [!NOTE]
> TTL no longer affects heap usage. The `OffHeapTimingWheel` stores all data in native memory.

### Example: 1M Entries, No TTL, Ghost Cache Disabled

| Component | Heap Usage |
|-----------|-----------|
| Fixed overhead | ~1.5 MB |
| Thread-local buffers (8 threads) | ~2.0 MB |
| **Total** | **~3.5 MB** |

---

## Performance Characteristics

Measured on a development machine, 4 threads, JDK 25 (1 warmup + 2 measurement JMH iterations):

| Scale | GET Latency | PUT Latency |
|-------|------------|------------|
| 10K entries | ~151 ns | ~170 ns |
| 100K entries | ~308 ns | ~350 ns |
| 1M entries | ~488 ns | ~487 ns |

### Performance Tips

1. **Use `StringEncoding.LATIN1`** for string keys — avoids heap allocation on the hot path
2. **Disable ghost cache** (`GhostCacheMode.DISABLED`) if not needed — removes on-heap Entry[] array
3. **Set `-Xmx` appropriately** — RMCache uses native memory, so Java heap can be small (e.g., `-Xmx256m`)
4. **Always call `.close()`** — native memory is not garbage-collected

---

## Thread Safety

RMCache is fully thread-safe. Concurrent access is handled via:
- **Striped `StampedLock`** on the hash table (optimistic reads, write locks per stripe)
- **Partitioned `EntryPool`** with per-partition `ReentrantLock`
- **Lock-free slab allocation** with CAS-based bitmap operations
- **Striped access buffers** in the LRU policy (batched async drain)

---

## Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| `OutOfMemoryError: Java heap space` | Too many threads × 256KB buffers | Increase `-Xmx` or reduce thread count |
| Long GC pauses | Large on-heap ghost cache | Use `GhostCacheMode.OFF_HEAP` or `DISABLED` |
| `IllegalArgumentException` at build | Invalid configuration | Ensure `maxEntries > 0`, partitions are power-of-2 |
| Stale reads after TTL | `CoarseClock` has ~100ms granularity | TTL precision is ±100ms by design |
