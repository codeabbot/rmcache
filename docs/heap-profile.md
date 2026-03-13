# Heap Profile — Understanding RMCache Memory Usage

RMCache's primary design goal is to keep JVM heap usage negligible regardless of cache size. This document explains exactly what lives on-heap, what lives off-heap, and how to minimize heap impact in production.

---

## The Near-Zero Heap Guarantee

All **data** — key bytes, value bytes, hash table slot arrays, LRU linked list nodes, frequency sketch counters — lives in native memory allocated via `malloc`. The Java heap holds only control-plane objects whose size is independent of entry count.

**Steady-state heap formula:**

```
Heap ≈ FixedOverhead (1.5 MB)
     + PerThread (260 KB × activeThreads)
     + GhostCache (0 in OFF_HEAP / DISABLED mode)
```

---

## What Lives Off-Heap

| Component | Native Memory Usage | Notes |
|-----------|-------------------|-------|
| Key + value bytes | `Σ entrySize` | All data, all metadata headers |
| Hash table slot arrays | `8 B × tableSlots` | One `long` per slot |
| EntryPool offset arrays | `8 B × slotCapacity` | Slot→offset mapping |
| LRU linked lists | `8 B × slotCapacity` | next + prev per entry |
| Frequency sketch | ≤ 8 MB (capped) | Count-Min Sketch, 4-bit counters |
| Timing wheel | `4 B × wheelSlots` | TTL bucket slot IDs |
| Ghost cache (OFF_HEAP) | `8 B × ghostCacheSize` | L1 hash + slot packed long |
| Slab bitmap arrays | `8 B per 64 blocks` | `AtomicLongArray` per slab |

---

## What Lives On-Heap

### Fixed Overhead (~1.5 MB per cache instance)

These do not grow with entry count:

| Component | Size | Description |
|-----------|------|-------------|
| `StampedLock[]` | `numStripes × ~80 B` | Hash table stripe locks |
| `int[]` × 3, `MemorySegment[]` | `numStripes × ~28 B` | Stripe metadata |
| `ReentrantLock[]` | `shardCount × ~80 B` | LRU shard locks |
| LRU access buffers | `numStripes × 16 KB` | ~1 MB at 64 stripes |
| `AtomicInteger[]` | `numStripes × 16 B` | Buffer fill indices |
| `LongAdder` × 6 | ~500 B | Hit/miss/eviction counters |
| `ScheduledExecutorService` × 2 | ~5 KB | Maintenance + eviction threads |
| `SubPool[]` | `128 × ~100 B = 12 KB` | EntryPool partition metadata |
| `SizeClassState[]` × 11 | ~2 KB | Slab allocator state |

### Per-Thread (~260 KB per thread, on first access)

| Component | Size | Mode |
|-----------|------|------|
| `ThreadLocalKeyBuffer` | 4 KB initial, up to 256 KB | Latin-1 key encoding only |
| `CacheContext.valueBuffer` | up to 256 KB | Value deserialization buffer |

> **Important:** These buffers are allocated on first cache access per thread and are not returned to the heap until the thread terminates — or until `OffHeapCache.cleanupThreadLocals()` is called.

### Variable (Depends on Usage)

| Component | When Allocated | Size |
|-----------|---------------|------|
| `GhostCache.Entry[]` | `HEAP` ghost cache mode | `~40 B × ghostCacheSize` |
| Retired segment queue entries | During hash table resize | ~48 B × concurrent resizes |
| Large alloc metadata | One per `>64 KB` allocation | ~80 B per entry |

---

## The Two Unavoidable Heap Allocations

Even in `zeroHeapProfile()` mode, two small heap allocations occur per operation:

### 1. Key Serialization `byte[]` (per `put`/`get`)

The key must be serialized to bytes for hash computation and comparison. This `byte[]` is created transiently and becomes eligible for GC immediately after the operation. It is not retained.

**Eliminate with Latin-1 encoding:**
```java
.stringKeyEncoding(StringEncoding.LATIN1)
// Uses ThreadLocalKeyBuffer — zero-allocation key encoding
```

### 2. Value Copy `byte[]` (per `get()`)

`get()` copies value bytes from native memory into a `byte[]` before returning. This is required by Java's type system — you cannot return a pointer to native memory as a `byte[]`.

**Eliminate with zero-copy APIs:**
```java
// Zero allocation — result computed directly from native memory
String s = cache.getZeroCopy("key", seg -> new String(seg.toArray(JAVA_BYTE), UTF_8));

// Minimal allocation — CacheValueView wrapper (~32 B)
try (CacheValueView v = cache.getView("key")) { int x = v.getInt(0); }
```

---

## Profiles and Configurations

### Default Profile

Ghost cache in `HEAP` mode, background eviction on:

```java
new CacheBuilder<String, byte[]>()
    .maxEntries(1_000_000)
    .offHeapMemory(Units.gigabytes(4))
    .build();
```

**Heap:** ~1.5 MB fixed + ~260 KB × threads + ~40 B × ghostCacheSize

### Zero-Heap Profile

Best for maximum heap efficiency:

```java
new CacheBuilder<String, byte[]>()
    .zeroHeapProfile()           // OFF_HEAP ghost cache + background eviction
    .maxEntries(1_000_000)
    .offHeapMemory(Units.gigabytes(4))
    .keySerializer(BuiltInSerializers.STRING_KEY_LATIN1)  // zero-alloc key encoding
    .build();
```

**Heap:** ~1.5 MB fixed + ~260 KB × threads (no ghost cache heap)

### Minimal Daemon Threads

```java
new CacheBuilder<String, byte[]>()
    .backgroundEviction(false)   // eviction on put() caller thread
    .ghostCacheMode(GhostCacheMode.DISABLED)
    .build();
```

---

## Measuring Heap Usage

### With JVM Heap Metrics

```java
Runtime rt = Runtime.getRuntime();
long before = rt.totalMemory() - rt.freeMemory();
// ... run workload ...
long after = rt.totalMemory() - rt.freeMemory();
System.out.printf("Heap delta: %d KB%n", (after - before) / 1024);
```

### With JFR (Java Flight Recorder)

```bash
java -XX:+FlightRecorder \
     -XX:StartFlightRecording=duration=60s,filename=profile.jfr \
     --enable-native-access=ALL-UNNAMED \
     -jar myapp.jar
```

Then inspect `jfr print --events jdk.ObjectAllocationInNewTLAB profile.jfr` to identify allocation sites.

### With `getStats()`

```java
OffHeapCache.CacheStats stats = cache.getStats();
long nativeUsed  = stats.memoryUsedBytes();
long nativeTotal = stats.memoryTotalBytes();
double pct       = stats.memoryUsagePercent();
System.out.printf("Native: %d / %d MB (%.1f%%)%n",
    nativeUsed / 1_048_576, nativeTotal / 1_048_576, pct);
```

---

## Thread-Local Cleanup

In application servers with long-lived thread pools (Tomcat, Netty), per-thread buffers accumulate and are not released until the thread dies. Call this on thread retirement:

```java
// In a servlet filter
public void doFilter(request, response, chain) {
    try {
        chain.doFilter(request, response);
    } finally {
        OffHeapCache.cleanupThreadLocals();
    }
}

// Or in a Spring RequestDestroyedEvent listener
@EventListener
public void onRequestDestroyed(RequestDestroyedEvent event) {
    OffHeapCache.cleanupThreadLocals();
}
```

Safe to call even if the thread never touched the cache.

---

## 1-Billion-Entry Scale Projection

With 16 B average key, 256 B average value, 4-thread application:

| Component | Heap | Native |
|-----------|------|--------|
| Fixed overhead | 1.5 MB | — |
| Thread-local buffers | 1.0 MB | — |
| Key + value data | 0 | ~256 GB |
| Hash table (60% LF) | 0 | ~13 GB |
| LRU metadata | 0 | ~8 GB |
| Frequency sketch | 0 | ~8 MB |
| **Total** | **~2.5 MB** | **~277 GB** |

The 2.5 MB heap overhead is constant regardless of entry count. This is the "near-zero heap" guarantee.
