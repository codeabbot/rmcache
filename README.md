# RMCache

High-Performance, Billion-Scale Off-Heap Cache for Java 25+

RMCache is a specialized caching library designed for ultra-low latency and massive scalability. By leveraging the Java 25 Foreign Function & Memory (FFM) API, it stores data off-heap and avoids GC pauses even when managing very large data sets.

---

## Key Features

- Zero-heap data path: keys, values, and index structures live off-heap.
- Java 25 FFM API: no Unsafe dependency.
- 64-bit slot packing: one 64-bit read for hash + slot.
- Key-match fast path + fingerprint check to reduce unnecessary comparisons.
- Zero-copy reads and large-value streaming support.
- GhostCache L1: HEAP, OFF_HEAP, DISABLED, or AUTO selection.
- Memory estimator and index memory budgeting for predictable capacity planning.
- Background eviction to keep hot path latency low.

---

## Architecture

```mermaid
flowchart LR
  A["Client API"] --> B["CacheBuilder"]
  B --> C["OffHeapCacheImpl"]
  C --> D["OffHeapHashTable"]
  C --> E["EntryPool"]
  E --> F["SlabAllocator"]
  F --> G["BuddyAllocator for large blocks"]
  C --> H["GhostCache (heap)"]
  C --> I["OffHeapGhostCache"]
  D --> J["Native Memory"]
  E --> J
  F --> J
  G --> J
```

---

## Performance Benchmarks

Benchmarks verified on macOS / Java 25 (OpenJDK). Latest results (Feb 2026) include all optimization phases plus enterprise architecture review fixes (C1-C4, H1-H6, M1-M7, L3).

### Latency (ns/op) - FairComparisonScaleBenchmark - Lower is Better

| Operation | Scale (Entries) | RMCache | RMCache + GhostCache | Reference (NMA) | EhCache | Status vs NMA |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **GET** | 10,000 | **151 ns** | 183 ns | 222 ns | 548 ns | ✅ **32% faster** |
| **GET** | 100,000 | **308 ns** | 354 ns | 360 ns | 655 ns | ✅ **14% faster** |
| **GET** | 1,000,000 | **488 ns** | 502 ns | 573 ns | 800 ns | ✅ **15% faster** |
| **PUT** | 10,000 | **170 ns** | 165 ns | 191 ns | 1070 ns | ✅ **11% faster** |
| **PUT** | 100,000 | **350 ns** | 333 ns | 303 ns | 1234 ns | ⚠️ 15% slower |
| **PUT** | 1,000,000 | **487 ns** | 521 ns | 522 ns | 1392 ns | ✅ **7% faster** |

**Key Observations** (Feb 2026, post architecture review fixes):
- GET latency leads NMA by **14-32%** across all scales.
- PUT now **beats NMA at 10K and 1M** (170ns vs 191ns, 487ns vs 522ns).
- Compared to EhCache: **3-4× faster GET**, **3-6× faster PUT** across all scales.

---

## Optimizations for 1B Scale

RMCache includes 9 optimizations designed to minimize memory overhead, reduce hot-path latency, and improve concurrency at billion-entry scale.

| Category | Optimization | Impact |
| :--- | :--- | :--- |
| **Memory** | Compact Entry Header (24B → 20B) | –4 GB @ 1B entries |
| **Memory** | Compact LRU Metadata (9B → 8B) | –1 GB @ 1B entries |
| **Memory** | Capped FrequencySketch Table (16M max) | –7.9 GB @ 1B entries |
| **Latency** | Vectorized Key Comparison (8B/compare) | 20-40% faster GET |
| **Latency** | O(1) Size Class Lookup | Faster allocation |
| **Latency** | Packed AllocationHandle (zero object GC) | No GC on hot path |
| **Concurrency** | Striped LRU Lock (up to 64 shards) | N× less lock contention |
| **Concurrency** | Larger Async Access Buffers (4096) | Better eviction accuracy |
| **GC** | Off-Heap Buddy Allocator | Eliminated heap data structures |

Projected memory at 1B entries: **~326 GB** (down from ~339 GB baseline).

---

## Memory Estimator

RMCache exposes a memory estimator to size off-heap allocations accurately.

**Entry size formula** (approx):

```
EntrySize = HEADER(20) + 4 + pad(keyLen) + 4 + valueLen
```

**Index size formula** (approx):

```
IndexBytes = HashTable(8 * slots) + Offsets(8 * slots) + FreeList(4 * slots)
```

**Total bytes** (approx):

```
Total = DataBytes + IndexBytes + AllocatorOverhead(~10% default)
```

### Example

```java
MemoryEstimator.MemoryEstimate estimate = new CacheBuilder<String, byte[]>()
        .maxEntries(1_000_000)
        .averageKeySize(16)
        .averageValueSize(256)
        .estimateMemory();

System.out.println("Total bytes: " + estimate.totalBytes());
System.out.println("Bytes/entry: " + estimate.bytesPerEntry());
```

---

## Usage

### Dependency

Requires Java 25+ with `--enable-native-access=ALL-UNNAMED`.

```gradle
dependencies {
    implementation 'com.codeabbot:rmcache:1.0.0'
}
```

### Basic Example

```java
import com.codeabbot.rmcache.CacheBuilder;
import com.codeabbot.rmcache.GhostCacheMode;
import com.codeabbot.rmcache.OffHeapCache;
import com.codeabbot.rmcache.Units;

try (OffHeapCache<String, byte[]> cache = new CacheBuilder<String, byte[]>()
        .maxEntries(1_000_000)
        .averageKeySize(16)
        .averageValueSize(256)
        .offHeapMemory(Units.gigabytes(4))
        .ghostCacheMode(GhostCacheMode.AUTO)
        .build()) {

    cache.put("user:123", new byte[256]);
    byte[] value = cache.get("user:123");
}
```

### Zero-Heap Profile

```java
try (OffHeapCache<String, byte[]> cache = new CacheBuilder<String, byte[]>()
        .zeroHeapProfile() // off-heap ghost cache + background eviction
        .ghostCacheMode(GhostCacheMode.AUTO)
        .maxEntries(5_000_000)
        .offHeapMemory(Units.gigabytes(16))
        .build()) {

    // zero-heap hot-path
}
```

---

## Serializer Helper

For custom value types, use `SerializerHelper` to build a `SegmentValueSerializer` without boilerplate.

```java
SegmentValueSerializer<MyType> serializer = SerializerHelper.segment(
        MyType::estimatedSize,
        (value, segment, offset, maxLen) -> value.writeTo(segment, offset, maxLen),
        (bytes, off, len) -> MyType.from(bytes, off, len));

try (OffHeapCache<String, MyType> cache = new CacheBuilder<String, MyType>()
        .valueSerializer(serializer)
        .build()) {
    cache.put("k", new MyType());
}
```

---

## Configuration

| Parameter | Default | Description |
| :--- | :--- | :--- |
| `maxEntries` | 1,000,000 | Target entry count (capacity planning). |
| `averageKeySize` | 32 | Used for memory estimation. |
| `averageValueSize` | 256 | Used for memory estimation. |
| `offHeapMemory` | auto | Total off-heap pool size. |
| `hashTableStripes` | auto | Stripe count (power of 2). Auto: 256 (\u003c100k), 1024 (100k-1M), 4096 (1M-10M), 16384 (10M+). |
| `entryPoolPartitions` | auto | Entry pool partitions (power of 2). |
| `hashTableLoadFactor` | 0.60 | Lower = faster probes, higher = lower index memory. |
| `hashTableInitialCapacity` | auto | Per-stripe hash table capacity. |
| `indexMemoryBudgetBytes` | unset | Budget index memory and auto-adjust load factor. |
| `indexMemoryBudgetPercent` | unset | Budget index memory as % of off-heap pool. |
| `ghostCacheMode` | AUTO | AUTO, HEAP, OFF_HEAP, DISABLED. |
| `ghostCacheSize` | auto | L1 cache capacity. |
| `stringKeyEncoding` | UTF8 | UTF8 or LATIN1 (faster for ASCII). |
| `backgroundEviction` | true | Enable background eviction. |
| `backgroundEvictionInterval` | 10ms | Eviction poll interval. |
| `evictionMemoryWatermarks` | 0.95 / 0.90 | High/low thresholds. |
| `prefetch` | false | Hash-table prefetching. |
| `slabSize` | 64KB | Slab allocator chunk size. |

---

## Index Memory Budgeting

If you want predictable index memory usage, you can set a budget and let the builder derive the hash table size and load factor.

```java
new CacheBuilder<String, byte[]>()
    .maxEntries(1_000_000)
    .offHeapMemory(Units.gigabytes(8))
    .indexMemoryBudgetPercent(0.15) // 15% of off-heap pool
    .build();
```

---

## Memory Comparison (RMCache vs NMA)

Use the built-in suite:

```
./gradlew runMemoryScalability
```

This prints 10k/100k/1M measurements and 1B extrapolations (RMCache uses the new estimator; NMA uses page-size estimation). Results depend on key/value sizes and page size.

Latest run (macOS, Java 25, 16B keys / 256B values, 4GB off-heap, NMA page size 4096):

| Scale | Provider | Heap (MB) | Off-Heap (MB) | Bytes/Entry |
| :--- | :--- | :--- | :--- | :--- |
| 10k | RMCache | 1.50 | 4.88 | 669.8 |
| 10k | NMA | 14.56 | 39.06 | 5623.2 |
| 100k | RMCache | 0.85 | 48.83 | 520.9 |
| 100k | NMA | 32.26 | 390.63 | 4434.3 |
| 1M | RMCache | 1.28 | 488.28 | 513.3 |
| 1M | NMA | 209.86 | 3906.25 | 4316.1 |

1B extrapolation:

| Provider | Heap Usage | Off-Heap Usage | Total RAM |
| :--- | :--- | :--- | :--- |
| RMCache | < 50 MB | 339.4 GB | 339.4 GB |
| NMA | High (CHM) | 3814.7 GB | 3814.7 GB |

---

## Large Values

Large values (4KB, 8KB, 128KB, 512KB and above) are supported. Values larger than slab size are served by the buddy allocator. For large values, always prefer a `SegmentValueSerializer` to avoid heap buffers.

---

## Notes on Load Factor

Lower load factor:
- Fewer probes
- Lower tail latency
- Higher index memory usage

Higher load factor:
- Lower index memory usage
- Longer probes and higher latency at scale

---

## License

Apache License 2.0
