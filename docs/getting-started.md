# Getting Started with RMCache

RMCache is an off-heap cache for JVM applications. It stores all key/value data in native memory using the Java FFM API, eliminating GC pressure even when managing gigabytes of data.

**Requirements:** JDK 25+ (LTS), Gradle 8+

---

## Installation

### Gradle

```groovy
dependencies {
    implementation 'com.codeabbot:rmcache:1.0.0'
}
```

### JVM Flags

Add to your JVM startup arguments:

```
--enable-native-access=ALL-UNNAMED
```

For module-path applications:

```
--enable-native-access=com.codeabbot.rmcache
```

---

## Your First Cache

```java
import com.codeabbot.rmcache.CacheBuilder;
import com.codeabbot.rmcache.OffHeapCache;
import com.codeabbot.rmcache.Units;
import com.codeabbot.rmcache.serializer.BuiltInSerializers;

// Build the cache
OffHeapCache<String, byte[]> cache = new CacheBuilder<String, byte[]>()
        .maxEntries(100_000)
        .offHeapMemory(Units.megabytes(512))
        .keySerializer(BuiltInSerializers.STRING_KEY)
        .valueSerializer(BuiltInSerializers.byteArray())
        .build();

// Put and get
cache.put("user:42", data);
byte[] value = cache.get("user:42");  // null if not found or expired

// Remove
boolean wasPresent = cache.remove("user:42");

// Check existence
boolean exists = cache.contains("user:42");

// Always close (releases native memory)
cache.close();
```

Use try-with-resources:

```java
try (OffHeapCache<String, byte[]> cache = new CacheBuilder<String, byte[]>()
        .maxEntries(100_000)
        .offHeapMemory(Units.megabytes(512))
        .keySerializer(BuiltInSerializers.STRING_KEY)
        .valueSerializer(BuiltInSerializers.byteArray())
        .build()) {

    cache.put("k", value);
    byte[] result = cache.get("k");
}
// native memory is freed automatically
```

---

## Common Patterns

### String Cache

```java
OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
        .maxEntries(1_000_000)
        .offHeapMemory(Units.gigabytes(2))
        .keySerializer(BuiltInSerializers.STRING_KEY_LATIN1)  // fastest for ASCII keys
        .valueSerializer(BuiltInSerializers.string())
        .build();
```

### TTL (Time-to-Live)

```java
import com.codeabbot.rmcache.eviction.TTLPolicy;
import java.time.Duration;

// Global TTL via eviction policy
OffHeapCache<String, byte[]> cache = new CacheBuilder<String, byte[]>()
        .maxEntries(100_000)
        .offHeapMemory(Units.megabytes(256))
        .keySerializer(BuiltInSerializers.STRING_KEY)
        .valueSerializer(BuiltInSerializers.byteArray())
        .build();

// Per-entry TTL on put
cache.put("session:abc", sessionData, Duration.ofMinutes(30));
cache.put("token:xyz", tokenBytes, Duration.ofHours(1));
```

### Compute If Absent (Cache-Aside)

```java
byte[] data = cache.computeIfAbsent("user:42", key -> loadFromDatabase(key));
```

> **Not atomic.** Under concurrent access, the loader may be called by multiple threads for the same key. The first result to win `putIfAbsent` is returned; others are discarded. Use external per-key locking if duplicate computation is expensive.

### Async Operations

```java
import java.util.concurrent.CompletableFuture;

// Non-blocking put
CompletableFuture<Void> put = cache.putAsync("key", value);

// Non-blocking get
CompletableFuture<byte[]> get = cache.getAsync("key");
get.thenAccept(result -> processResult(result));
```

Use a bounded executor to prevent unbounded task queueing at high throughput:

```java
import java.util.concurrent.Executors;

OffHeapCache<String, byte[]> cache = new CacheBuilder<String, byte[]>()
        .asyncExecutor(Executors.newFixedThreadPool(4))
        .build();
```

### Bulk Operations

```java
// Put multiple entries
Map<String, byte[]> batch = Map.of("k1", v1, "k2", v2, "k3", v3);
cache.putAll(batch);
cache.putAll(batch, Duration.ofMinutes(10));  // with TTL

// Get multiple entries (only found entries are returned)
Map<String, byte[]> results = cache.getAll(List.of("k1", "k2", "missing"));
```

---

## Sizing the Cache

### Quick Size Estimation

```java
MemoryEstimator.MemoryEstimate estimate = new CacheBuilder<String, byte[]>()
        .maxEntries(1_000_000)
        .averageKeySize(16)
        .averageValueSize(256)
        .estimateMemory();

System.out.printf("Total: %d MB%n", estimate.totalBytes() / 1_048_576);
System.out.printf("Per entry: %d bytes%n", estimate.bytesPerEntry());
```

### Memory Budget

```java
// Constrain index memory to 15% of off-heap pool
new CacheBuilder<String, byte[]>()
    .maxEntries(1_000_000)
    .offHeapMemory(Units.gigabytes(8))
    .indexMemoryBudgetPercent(0.15)
    .build();
```

### Rules of Thumb

| Metric | Typical Range |
|--------|---------------|
| Entry overhead (header + index) | 28–50 bytes |
| Hash table overhead | ~8 bytes/slot at 60% load factor |
| LRU metadata | 8 bytes/entry |
| Frequency sketch | ~1 MB fixed (capped at 16M entries) |

---

## JVM Configuration

```bash
# Minimal heap — native memory handles the data
java -Xmx256m \
     --enable-native-access=ALL-UNNAMED \
     -jar myapp.jar
```

For GC visibility of native memory allocations, add (optional):

```bash
-XX:+PrintGCDetails -Xlog:gc*
```

---

## Next Steps

- [Eviction Policies](eviction-policies.md) — LRU, TTL, composite policies, custom filters
- [Custom Serialization](custom-serialization.md) — zero-copy serialization for custom types
- [Zero-Copy Access](zero-copy-access.md) — large value access without heap allocation
- [Heap Profile](heap-profile.md) — understanding and minimizing heap usage
- [Architecture Deep Dive](../ARCHITECTURE-DEEP-DIVE.md) — full configuration reference
