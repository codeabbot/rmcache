# RMCache

**High-Performance, Billion-Scale Off-Heap Cache for Java 25+**

RMCache is a specialized caching library designed for ultra-low latency and massive scalability. By leveraging the **Java 25 Foreign Function & Memory (FFM) API**, it stores data entirely off-heap, eliminating Garbage Collection (GC) pauses even when managing billions of entries. It significantly outperforms standard on-heap solutions (like `ConcurrentHashMap`) and matches or beats specialized native alternatives in both throughput and latency.

---

## 🚀 Key Features

*   **Zero-GC Overhead**: All keys, values, and internal data structures are stored off-heap.
*   **Java 25 FFM API**: Built on the modern authorized memory access API, replacing unsafe `sun.misc.Unsafe`.
*   **Performance Optimized**:
    *   **64-bit Slot Packing**: Consolidates hash and pointer into a single 64-bit memory operation for atomic-like visibility and reduced memory bandwidth.
    *   **Cache-Line Friendly**: Linear probing utilizing CPU cache lines effectively.
    *   **SIMD-Ready**: Designed with alignment for potential Vector API optimizations.
*   **Massive Scalability**: Tested to scale linearly to **1 Billion+ entries** with a tiny fixed heap footprint (~50MB).
*   **Optional L1 Hot Cache**: Choose heap or **off-heap** L1 for hot-key acceleration.

---

## 🏗 Architecture & Design Principles

RMCache avoids the "object overhead" of Java by treating memory as a raw slab, similar to C++.

### 1. The GhostCache Pattern
RMCache uses a "Ghost" architecture where the Java heap only holds lightweight handles. The heavy lifting—the actual hash table and data—resides in native memory.
*   **Heap**: Small controller objects (`OffHeapCache`, `SlabAllocator`).
*   **Off-Heap**: The massive hash table array and all entry data blocks.

### 2. OffHeapHashTable (The Core)
A custom open-addressing hash table optimized for modern CPUs.
*   **64-bit Slot Optimization**: Each slot is exactly 8 bytes.
    *   **High 32 bits**: Pointer to the data (Slot ID).
    *   **Low 32 bits**: 32-bit Hash of the key.
    *   *Benefit*: A single 64-bit read loads both the check-hash and the data pointer. A single 64-bit write updates them atomically.
*   **Linear Probing**: accessing contiguous memory maximizes L1/L2 cache hits.

### 3. Slab Allocation
To prevent fragmentation and `malloc` overhead:
*   **SlabAllocator**: Requests large chunks (e.g., 2MB) from the OS.
*   **EntryPool**: Sub-allocates variable-sized entries (Key + Value + Metadata) from these slabs.
*   **Zero Copy**: Data is copied directly from efficient ByteBuffers/MemorySegments.

---

## 📊 Performance Benchmarks

Benchmarks verified on **macOS / Java 25 (OpenJDK)**.

### Latency (ns/op) - Lower is Better
*RMCache isolates the application from GC pauses, maintaining stable latency.*

| Operation | Scale (Entries) | RMCache | Reference (NMA/Map) | Status |
| :--- | :--- | :--- | :--- | :--- |
| **GET** (Read) | 10,000 | **~123 ns** | ~225 ns | ✅ **1.8x Faster** |
| **GET** (Read) | 100,000 | **~369 ns** | ~376 ns | ✅ Faster |
| **GET** (Read) | 1,000,000 | **~555 ns** | ~597 ns | ✅ Faster |
| **PUT** (Write) | 10,000 | **~178 ns** | ~181 ns | ✅ **Faster** |
| **PUT** (Write) | 100,000 | ~389 ns | ~320 ns | Competitive |
| **PUT** (Write) | 1,000,000 | ~540 ns | ~498 ns | Competitive |

### Throughput (ops/s) - Higher is Better
*   **Small Scale (10k)**: Sustains **~33 Million+** writes/sec and **~38 Million+** reads/sec.
*   **Large Scale (1M)**: Sustains **~7-9 Million** ops/sec depending on workload.

---

## 💾 Memory Usage

RMCache's primary advantage is its memory model. It decouples cache size from Java Heap size.

### Heap vs. Off-Heap Comparison (Projected for 1 Billion Entries)

| Metric | RMCache | Standard JVM Map |
| :--- | :--- | :--- |
| **On-Heap Footprint** | **~50 MB** (Fixed) | **~80 GB+** (GC Hell) |
| **Off-Heap Usage** | ~74 GB | ~0 GB |
| **GC Impact** | **None** | Massive Full GC Pauses |

### Memory Calculation Formula
To estimate the off-heap memory required:

```text
Total Memory = (Internal Overhead) + (Data Storage)

1. Index Overhead:
   Capacity = NextPowerOf2(TargetEntries / LoadFactor)
   IndexMemory = Capacity * 8 bytes

2. Data Storage:
   EntrySize = 12 bytes (Header) + KeyLength + ValueLength + Padding
   DataMemory = TargetEntries * EntrySize
```

*Example*: 1M entries, 16-byte keys, 64-byte values.
*   Index: ~1.4M slots * 8 bytes ≈ 11MB
*   Data: 1M * (12 + 16 + 64) ≈ 92MB
*   **Total**: ~103 MB Off-Heap.

---

## 💻 Usage

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
import com.codeabbot.rmcache.OffHeapCache;

public class Example {
    public static void main(String[] args) {
        // 1. Configure and Build
        try (OffHeapCache cache = new CacheBuilder()
                .initialCapacity(1_000_000)      // Estimated entry count
                .offHeapMemoryBytes(1024 * 1024 * 512) // 512 MB Pool
                .concurrencyLevel(16)            // Number of stripes
                .build()) {

            // 2. Put Data
            String key = "user:123";
            String value = "{\"name\": \"Alice\", \"role\": \"admin\"}";
            cache.put(key, value);

            // 3. Get Data
            String result = cache.get(key);
            System.out.println("Cached: " + result);
            
            // 4. Custom key/value types (requires Serializer)
            // cache.put(100L, myObject);
        } // Auto-closes and frees native memory
    }
}
```

---

## ⚙️ Configuration

| Parameter | Default | Description |
| :--- | :--- | :--- |
| `initialCapacity` | 100,000 | Initial number of entries to size the hash table. |
| `offHeapMemoryBytes`| 128 MB | Total size of the native memory slab for data. |
| `concurrencyLevel` | 16 | Number of internal lock stripes (adjust for high thread counts). |
| `enablePrefetch` | true | Enables CPU prefetch instructions for hash table probing. |
| `ghostCacheMode` | HEAP | `HEAP`, `OFF_HEAP`, or `DISABLED` (off-heap mode keeps zero-heap semantics). |
| `ghostCacheSize` | auto | L1 cache capacity for hot keys. |
| `stringKeyEncoding` | UTF8 | `UTF8` (default) or `LATIN1` for faster ASCII/Latin1 keys. |
| `backgroundEviction` | false | Enables background eviction to keep hot path low-latency. |
| `backgroundEvictionInterval` | 10ms | How often the background eviction thread runs. |
| `evictionMemoryWatermarks` | 0.95 / 0.90 | High/low memory watermarks for background eviction. |
| `evictionPolicy` | LRU | *Planned feature*. Currently blocks/rejects on full. |

---

## 🛠 Technical Details

### 64-bit Slot Packing (The Secret Sauce)
Instead of struct-like objects, the hash table is a raw `long[]` array in native memory.

```java
// Logic for combining Hash and SlotID
long entry = (keyHash & 0xFFFFFFFFL) | ((long) slotId << 32);
table.setAtIndex(ValueLayout.JAVA_LONG, pos, entry);
```
This enables extremely efficient "check-and-fetch" operations in the hot path, effectively halving the memory latency for lookups.

### Large Values ( > 256 KB )
For large values, prefer a `SegmentValueSerializer` so data is written directly into off-heap memory without intermediate heap buffers. The built-in byte[] serializer already supports this. Implementations should ensure `estimateSize()` is an upper bound.

### String Key Encoding
The default key encoding is UTF-8. For ASCII/Latin1-only keys, you can enable a faster Latin1 path via `stringKeyEncoding(LATIN1)`.

### Native Interop Safety
RMCache uses `Arena` (from Java FFM) to manage lifecycles. When the cache is closed, the Arena is closed, ensuring deterministic deallocation and preventing native memory leaks.

---

**© 2026 CodeAbbot Team**
