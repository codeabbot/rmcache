# RMCache — Architecture Deep Dive

A high-performance, off-heap Java cache built on the Java FFM (Foreign Function & Memory) API. RMCache stores all key/value data in native memory, keeping the Java heap small and GC pressure negligible even at billion-entry scale.

**Requires JDK 25+ (LTS)** — the FFM API (`java.lang.foreign`) is stable and production-ready from JDK 25.

---

## Quick Start

```java
import com.codeabbot.rmcache.*;
import com.codeabbot.rmcache.serializer.*;

OffHeapCache<String, byte[]> cache = new CacheBuilder<String, byte[]>()
    .offHeapMemory(Units.gigabytes(2))            // 2 GB native memory
    .maxEntries(1_000_000)                        // Up to 1 M entries
    .stringKeyEncoding(StringEncoding.LATIN1)     // Fast Latin-1 key encoding
    .valueSerializer(BuiltInSerializers.byteArray())
    .build();

// Basic operations
cache.put("user:42", userData);
byte[] result = cache.get("user:42");
cache.remove("user:42");

// Always close when done (frees native memory)
cache.close();
```

---

## Building & Testing

```bash
# Build
./gradlew build

# Run all tests
./gradlew test

# Run JMH benchmarks
./gradlew jmh -Pjmh.includes="RMCacheOnlyBenchmark"
./gradlew jmh -Pjmh.includes="FairComparisonScaleBenchmark"
```

> All Gradle tasks automatically pass `--enable-native-access=ALL-UNNAMED`.

---

## CacheBuilder — Full Configuration Reference

| Method | Type | Default | Description |
|--------|------|---------|-------------|
| `.offHeapMemory(long)` | bytes | auto-calculated | Total native memory budget |
| `.maxEntries(int)` | int | 1,000,000 | Maximum number of entries (eviction target) |
| `.averageKeySize(int)` | bytes | 32 | Used for memory estimation |
| `.averageValueSize(int)` | bytes | 256 | Used for memory estimation |
| `.stringKeyEncoding(StringEncoding)` | enum | `UTF8` | `LATIN1` for fast zero-alloc key encoding |
| `.keySerializer(KeySerializer<K>)` | — | — | Custom key serializer |
| `.valueSerializer(ValueSerializer<V>)` | — | — | Custom value serializer |
| `.eviction(EvictionPolicy)` | — | `LRUPolicy` | Eviction policy |
| `.evictionListener(EvictionListener<K,V>)` | — | none | Called on each eviction |
| `.evictionFilter(EvictionFilter<K>)` | — | none | Veto eviction candidates |
| `.ghostCacheMode(GhostCacheMode)` | enum | `AUTO` | L1 ghost cache strategy |
| `.ghostCacheSize(int)` | int | auto | Ghost cache slot count |
| `.hashTableLoadFactor(double)` | double | 0.60 | Lower = faster, higher = less index memory |
| `.hashTableStripes(int)` | int | auto | Hash table stripe count (power of 2) |
| `.hashTableInitialCapacity(int)` | int | auto | Per-stripe initial capacity |
| `.indexMemoryBudgetBytes(long)` | bytes | — | Constrain index memory usage |
| `.indexMemoryBudgetPercent(double)` | 0–1 | — | Index budget as % of off-heap pool |
| `.entryPoolPartitions(int)` | int | auto | EntryPool partition count (power of 2) |
| `.prefetch(boolean)` | boolean | false | Enable CPU cache prefetching |
| `.backgroundEviction(boolean)` | boolean | true | Eviction on daemon thread |
| `.backgroundEvictionInterval(Duration)` | — | 10 ms | Eviction poll interval |
| `.evictionMemoryWatermarks(double,double)` | — | 0.95/0.90 | High/low eviction thresholds |
| `.asyncExecutor(Executor)` | — | ForkJoinPool | Executor for `putAsync`/`getAsync` |
| `.slabSize(int)` | bytes | 65536 | Slab chunk size (must be power of 2 ≥ 4096) |
| `.zeroHeapProfile()` | — | — | OFF_HEAP ghost cache + background eviction preset |
| `.withCacheName(String)` | — | "rmcache" | Cache name (for logging/metrics) |
| `.zeroMemoryOnStartup()` | — | false | Zero-fill native memory on allocation |

---

## Eviction Policies

```java
// LRU with W-TinyLFU admission filter (recommended default)
.eviction(new LRUPolicy(maxEntries))

// TTL-based expiry (use CompositePolicy to combine with LRU)
.eviction(new TTLPolicy(Duration.ofMinutes(5).toMillis(), entryPool))

// Combined LRU + TTL
.eviction(new CompositePolicy(lruPolicy, ttlPolicy))

// No eviction (manual management / bounded by off-heap memory only)
.eviction(new NoEvictionPolicy())
```

See [docs/eviction-policies.md](docs/eviction-policies.md) for detailed policy tuning.

---

## Serializers

```java
// Built-in
BuiltInSerializers.byteArray()
BuiltInSerializers.string()        // UTF-8
BuiltInSerializers.STRING_KEY      // Key serializer, UTF-8
BuiltInSerializers.STRING_KEY_LATIN1  // Key serializer, Latin-1 (fastest)

// Custom value serializer
new ValueSerializer<MyType>() {
    public byte[] serialize(MyType value) { ... }
    public MyType deserialize(byte[] bytes) { ... }
}

// Segment serializer — writes directly into native memory, zero intermediate byte[]
SegmentValueSerializer<MyType> serializer = SerializerHelper.segment(
    MyType::estimatedSize,
    (value, segment, offset, maxLen) -> value.writeTo(segment, offset, maxLen),
    (bytes, off, len) -> MyType.from(bytes, off, len));
```

See [docs/custom-serialization.md](docs/custom-serialization.md).

---

## Memory Budgeting

### Heap Usage Breakdown

**Fixed overhead per cache instance** (~1.5 MB total):

| Component | Size |
|-----------|------|
| `StampedLock[]` | `numStripes × ~80 B` |
| Stripe metadata (`int[]` × 3, `MemorySegment[]`) | `numStripes × ~28 B` |
| `ReentrantLock[]` (LRU shards) | `shardCount × ~80 B` |
| LRU access buffers | `numStripes × 4096 × 4 B` (~1 MB at 64 stripes) |
| `AtomicInteger[]` (buffer indices) | `numStripes × ~16 B` |
| `LongAdder` × 6 | ~500 B |
| Executor threads × 2–3 | ~5 KB |
| `SubPool[]` × 128 | `128 × ~100 B` ≈ 12 KB |

**Per-thread** (allocated on first cache access):

| Component | Size |
|-----------|------|
| `ThreadLocalKeyBuffer` | 4 KB (Latin-1 mode) |
| Value buffer (in `CacheContext`) | up to 256 KB |

> Formula: `heapPerThread ≈ 260 KB × numActiveThreads`

**Grows with usage:**

| Component | When | Size |
|-----------|------|------|
| `GhostCache.Entry[]` | `HEAP` mode only | `~40 B × ghostCacheSize` |
| `SlabAllocator` large-alloc map | One entry per >64 KB alloc | ~80 B each |
| `LockFreeSlabManager` bitmaps | Per slab created | 8 B per 64 blocks |

### Example: 1 M Entries, 8 Threads, TTL

| Component | Heap | Native |
|-----------|------|--------|
| Fixed overhead | ~1.5 MB | — |
| Thread-local buffers (8 threads) | ~2.0 MB | — |
| Key + value data | 0 | ~488 MB |
| Hash table | 0 | ~13 MB |
| LRU structures | 0 | ~8 MB |
| TTL timing wheel | 0 | ~4 MB |
| **Total** | **~3.5 MB** | **~513 MB** |

### Memory Estimation API

```java
MemoryEstimator.MemoryEstimate est = new CacheBuilder<String, byte[]>()
    .maxEntries(1_000_000)
    .averageKeySize(16)
    .averageValueSize(256)
    .estimateMemory();

System.out.println("Total bytes: " + est.totalBytes());
System.out.println("Bytes/entry: " + est.bytesPerEntry());
```

---

## Performance

Benchmarks run on macOS, JDK 25, 4 threads (`FairComparisonScaleBenchmark`, JMH).

| Scale | GET | PUT | vs NMA GET | vs EhCache GET |
|-------|-----|-----|-----------|---------------|
| 10 K | **124 ns** | **199 ns** | 34% faster | 4× faster |
| 100 K | **307 ns** | **358 ns** | 15% faster | 2× faster |
| 1 M | **503 ns** | **471 ns** | — | 1.5× faster |

### Performance Tips

1. **Use `StringEncoding.LATIN1`** for string keys — avoids per-key heap allocation
2. **Use `GhostCacheMode.OFF_HEAP`** or `zeroHeapProfile()` — removes `Entry[]` heap pressure
3. **Use `SegmentValueSerializer`** for large values — writes directly into native memory
4. **Use `getZeroCopy` / `getView`** for large reads — avoids the value copy `byte[]`
5. **Set `-Xmx` small** — RMCache uses native memory; heap only needs ~few hundred MB for control plane
6. **Call `cleanupThreadLocals()`** from thread retirement hooks to reclaim per-thread buffers

---

## Zero-Copy Reads

```java
// Lambda-based — no heap allocation, result computed in-place
String result = cache.getZeroCopy("key", segment -> {
    // segment points to live off-heap memory — do not store it
    int len = (int) segment.byteSize();
    return new String(segment.toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
});

// View-based — thin wrapper around the native segment
try (CacheValueView view = cache.getView("key")) {
    if (view != null) {
        int firstInt = view.getInt(0);
        long firstLong = view.getLong(0);
    }
}
```

> See [docs/zero-copy-access.md](docs/zero-copy-access.md) for thread-safety constraints.

---

## Thread Safety

RMCache is fully thread-safe for concurrent `put`, `get`, `remove`, and `computeIfAbsent`.

| Operation | Mechanism |
|-----------|-----------|
| GET | Optimistic read (no lock on success) |
| PUT | Write lock per stripe |
| remove | Write lock per stripe |
| LRU access recording | Lock-free buffer write |
| TTL check | Volatile clock read |

**`computeIfAbsent` is not atomic.** There is a TOCTOU window between the miss and the `putIfAbsent`. Use external per-key synchronization (e.g., Guava `Striped<Lock>`) if duplicate computation is unacceptable.

---

## Thread-Local Cleanup

RMCache allocates per-thread buffers for zero-allocation serialization. In long-lived thread pools (Tomcat, Netty), call this from thread retirement hooks:

```java
// In servlet filter destroy() or framework shutdown hook
OffHeapCache.cleanupThreadLocals();
```

Safe to call from any thread, even if it never accessed the cache.

---

## Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| `OutOfMemoryError: Java heap space` | Too many threads × 260 KB per-thread buffers | Increase `-Xmx` or call `cleanupThreadLocals()` after request |
| Long GC pauses | Large on-heap ghost cache (`HEAP` mode) | Use `GhostCacheMode.OFF_HEAP` or `zeroHeapProfile()` |
| `IllegalArgumentException` at build | Invalid configuration (negative sizes, non-power-of-2 partitions) | Check `maxEntries > 0`, partitions are power-of-2 |
| Stale reads after TTL expiry | `CoarseClock` has ±100 ms granularity by design | TTL precision is ±100 ms; set TTL accordingly |
| Memory not freed after `clear()` | Background eviction still running | Call `close()` to release all native memory |
| Slow `clear()` at scale | O(slotCapacity) iteration | Call from maintenance thread, not hot path |
