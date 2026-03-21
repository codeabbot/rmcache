# RMCache User Manual

RMCache is a high-performance, strictly off-heap caching library for Java 25+. It is designed for billion-scale entry sets, providing sub-microsecond latency while keeping the GC completely unburdened. 

This manual covers everything from basic usage to advanced tuning.

---

## 1. Quick Start

### Installation

Add the RMCache dependency to your `pom.xml` or `build.gradle` (assuming it has been published or you've built it locally).
Since RMCache uses the Project Panama Foreign Function & Memory (FFM) API, you must run your application with:
```bash
--enable-native-access=ALL-UNNAMED
```

### Basic Example

```java
import com.codeabbot.rmcache.*;

public class RMCacheExample {
    public static void main(String[] args) {
        // Build a cache for 10 million entries with 8GB native memory
        OffHeapCache<String, byte[]> cache = new CacheBuilder<String, byte[]>()
            .maxEntries(10_000_000)
            .offHeapMemory(8L * 1024 * 1024 * 1024) // 8 GB
            .withCacheName("my-cache")
            .build();

        // Put an entry
        cache.put("user:123", "data".getBytes());

        // Get an entry
        byte[] value = cache.get("user:123");
        
        // Always close to free native memory
        cache.close();
    }
}
```

---

## 2. Configuration & Tuning

The `CacheBuilder` provides extensive options to tune memory and concurrency.

### Memory & Sizing

- `maxEntries(int)`: The absolute maximum number of items the cache will track before evicting.
- `offHeapMemory(long)`: Total byte capacity of the native slab region. Ensure this is larger than your expected `maxEntries * (avgKey + avgVal + overhead)`.
- `slabSize(int)`: Size of memory pages (default: 64KB). Values > 64KB use the Buddy Allocator.
- `averageKeySize(int)` and `averageValueSize(int)`: Used by the internal memory estimator.

### Concurrency

- `hashTableStripes(int)`: Number of locks protecting the hash table. Must be a power of 2 (e.g., 1024, 4096). Higher values reduce write contention.
- `entryPoolPartitions(int)`: Shards the entry pool lock space.

### Ghost Cache Overview 👻

The **Ghost Cache L1** shortcut provides an ultra-fast, direct-mapped lookup that bypasses the hash table.
It holds only `(hash, slot)` pairs.
- **`ghostCacheMode(GhostCacheMode.OFF_HEAP)`**: Enables the off-heap ghost cache.
- **`ghostCacheSize(int)`**: How many slots the L1 cache holds. A good rule of thumb is `2 * maxEntries`.

```java
.ghostCacheMode(GhostCacheMode.OFF_HEAP)
.ghostCacheSize(20_000_000) // 2x maxEntries for optimal hit rate
```

---

## 3. Serialization

RMCache stores only bytes. It provides built-in serializers (`BuiltInSerializers`) for common types:
- `BuiltInSerializers.STRING_KEY_UTF8`
- `BuiltInSerializers.STRING_KEY_LATIN1`
- `BuiltInSerializers.STRING_VALUE`
- `BuiltInSerializers.byteArray()`

### Fast-String Paths

If your keys are ASCII/Latin1 strings, use the Latin1 fast path to skip array allocations during GET operations:

```java
.stringKeyEncoding(StringEncoding.LATIN1)
```

### Custom Serializers

You can implement `KeySerializer<K>` and `ValueSerializer<V>` for POJOs:

```java
public class MyUserSerializer implements ValueSerializer<User> {
    public byte[] serialize(User u) { ... }
    public User deserialize(byte[] b) { ... }
}
```

---

## 4. Zero-Copy Access

RMCache provides zero-copy access to values to prevent throwing `byte[]` arrays onto the heap. 

### The `CacheValueView`

```java
// Instead of cache.get(), use cache.getView()
try (CacheValueView view = cache.getView("large-video-chunk")) {
    if (view != null) {
        // Read directly from native memory
        int size = view.size();
        byte firstByte = view.getByte(0);
        
        // Or stream directly to a network socket without heap allocation
        view.copyTo(outputStream, 8192);
    }
}
```

> [!WARNING]
> A `CacheValueView` is a direct pointer to native memory. It is **inherently vulnerable to Time-Of-Check to Time-Of-Use (TOCTOU)** if the entry is concurrently evicted. Use the view quickly and do not store it across threads.

---

## 5. Eviction & Expiration Policies

By default, RMCache uses an SLRU (Segmented LRU) protected by TinyLFU.

### Setting the Eviction Policy

```java
// 10M entries, 1% window, 80% protected
EvictionPolicy slru = new LRUPolicy(10_000_000, 10_000_000, 0.01f, 0.80f);
.eviction(slru)
```

### Time-to-Live (TTL)

You can construct a `TTLPolicy` for time-based expiration. The `OffHeapTimingWheel` processes expirations efficiently off-heap.

```java
EvictionPolicy ttl = new TTLPolicy(10_000_000); // 10M slots
.eviction(ttl)
```

You can set TTL on a per-entry basis:
```java
cache.put("session:1", tokenBytes, Duration.ofMinutes(30));
```

### Composite Policies

You can mix LRU and TTL using `CompositePolicy`.

```java
.eviction(new CompositePolicy(List.of(slru, ttl)))
```

### Eviction Listeners & Filters

- **EvictionListener**: Fired when an entry is evicted. Useful for logging or updating secondary stores.
- **EvictionFilter**: Permits you to return `false` to block eviction for a specific entry. The entry is then re-admitted to the eviction queues.

```java
.evictionFilter(key -> {
    return !key.startsWith("pinned:"); // Block eviction of pinned keys
})
```

---

## 6. Closing the Cache

Because all memory is allocated off-heap via FFM, it is completely invisible to the Java Garbage Collector. You **must** call `close()` when shutting down your application.

```java
Runtime.getRuntime().addShutdownHook(new Thread(() -> {
    if (cache != null) cache.close();
}));
```
Failure to call `close()` will result in native memory leaks.
