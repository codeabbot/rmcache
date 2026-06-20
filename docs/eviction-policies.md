# Eviction Policies

RMCache supports several eviction strategies. Policies can be combined using `CompositePolicy`.

---

## LRU Policy (Default)

`LRUPolicy` implements **Window TinyLFU (W-TinyLFU)** — a scan-resistant, frequency-aware eviction algorithm.

```java
import com.codeabbot.rmcache.eviction.LRUPolicy;

OffHeapCache<String, byte[]> cache = new CacheBuilder<String, byte[]>()
        .maxEntries(1_000_000)
        .offHeapMemory(Units.gigabytes(4))
        .eviction(new LRUPolicy(maxEntries))
        .forByteArrayValues()
        .build();
```

If no policy is set, `CacheBuilder` automatically creates an `LRUPolicy`.

### How W-TinyLFU Works

New entries start in the **Window** segment (1% of capacity). When the Window is full, entries are demoted to **Probation** (19%). On second access, Probation entries are promoted to **Protected** (80%), where they stay until eviction pressure demotes them back.

A **Count-Min Sketch** (off-heap) tracks estimated access frequency. When the cache is full, the candidate for eviction (Probation tail) is compared against the incoming entry. The one with lower estimated frequency is discarded. This prevents one-hit-wonder scans from evicting frequently-accessed entries.

### Tuning

```java
// Custom window and protected segment sizes
new LRUPolicy(maxEntries, maxSlots, 0.01f, 0.80f)
//                                  ^window ^protected
```

| Parameter | Default | Effect |
|-----------|---------|--------|
| `windowPercent` | 0.01 (1%) | Larger window = more room for new entries to prove frequency |
| `protectedPercent` | 0.80 (80%) | Larger protected = more stable "hot" entries |

---

## TTL Policy

`TTLPolicy` expires entries after a fixed time-to-live. Uses an off-heap hierarchical timing wheel — no heap per-entry overhead.

### Global / default TTL

**Use per-entry TTL (below) for application-level expiry** — it is the supported, zero-config
path. A *global* default-TTL `TTLPolicy` exists, but its proactive `OffHeapTimingWheel` binds
to the cache's internal `EntryPool`, which application code does not hold at build time. Direct
construction (`new TTLPolicy(ttlMs, entryPool)`) is therefore an internal/advanced concern, not
a public recipe; for normal use, set TTL per entry on `put`.

### Per-Entry TTL

TTL can be set per-entry without a global `TTLPolicy`. The TTL is stored directly in the entry's off-heap header (zero heap cost):

```java
cache.put("session:abc", data, Duration.ofMinutes(30));
cache.put("token:xyz", data, Duration.ofHours(1));
cache.put("permanent", data);  // no TTL
```

### TTL Precision

TTL expiration has ±50 ms granularity due to `CoarseClock`. This is by design — checking `System.currentTimeMillis()` on every `get()` is 5-10× more expensive than a volatile read of the coarse clock. If you need millisecond-precise TTL, use a lower-overhead external scheduler.

---

## Composite Policy (LRU + TTL)

Combine LRU size-based eviction with TTL expiration:

```java
import com.codeabbot.rmcache.eviction.CompositePolicy;

LRUPolicy lru = new LRUPolicy(maxEntries);
TTLPolicy ttl = new TTLPolicy(Duration.ofMinutes(30).toMillis(), entryPool);
CompositePolicy policy = new CompositePolicy(List.of(lru, ttl));

OffHeapCache<String, byte[]> cache = new CacheBuilder<String, byte[]>()
        .eviction(policy)
        .forByteArrayValues()
        .build();
```

> **Note:** the TTL leg above requires the cache's internal `EntryPool` (not available to
> application code at build time), so this composition is an advanced/internal pattern. For
> almost all use cases the default SLRU (size-based) eviction plus **per-entry TTL** on `put`
> is sufficient and needs no manual policy construction.

Both policies run independently. An entry is evicted if either policy selects it as a victim.

---

## No Eviction Policy

For caches where you manage capacity externally (e.g., you know exact entry counts and memory):

```java
import com.codeabbot.rmcache.eviction.NoEvictionPolicy;

OffHeapCache<String, byte[]> cache = new CacheBuilder<String, byte[]>()
        .eviction(new NoEvictionPolicy())
        .forByteArrayValues()
        .build();
```

`put()` will fail if off-heap memory is exhausted. Monitor `getStats().memoryUsagePercent()`.

---

## Background Eviction

By default, eviction runs on a dedicated daemon thread rather than on the `put()` hot path:

```java
cache = new CacheBuilder<...>()
        .backgroundEviction(true)                        // default: true
        .backgroundEvictionInterval(Duration.ofMillis(10))  // default: 10ms
        .evictionMemoryWatermarks(0.95, 0.90)            // default: 95% / 90%
        .build();
```

When `memoryUsed / memoryTotal` crosses the **high watermark** (95%), the background thread evicts entries until usage falls below the **low watermark** (90%).

Disable background eviction for synchronous eviction on the `put()` path (lower latency predictability, lower daemon overhead):

```java
.backgroundEviction(false)
```

---

## Eviction Listener

Receive a callback when an entry is evicted:

```java
import com.codeabbot.rmcache.eviction.EvictionCause;
import com.codeabbot.rmcache.eviction.EvictionListener;

OffHeapCache<String, byte[]> cache = new CacheBuilder<String, byte[]>()
        .evictionListener((key, value, cause) -> {
            switch (cause) {
                case SIZE    -> log.info("Evicted {} (capacity)", key);
                case EXPIRED -> log.info("Evicted {} (TTL)", key);
                case EXPLICIT -> log.debug("Removed {} (explicit)", key);
            }
        })
        .forByteArrayValues()
        .build();
```

**`EvictionCause` values:**
- `SIZE` — entry evicted due to capacity pressure
- `EXPIRED` — entry evicted due to TTL expiration
- `EXPLICIT` — entry removed via `remove()` or `clear()`

The listener is called on the evicting thread (either the background thread or the `put()` caller). Keep the callback fast.

---

## Eviction Filter

Veto specific entries from eviction:

```java
import com.codeabbot.rmcache.eviction.EvictionFilter;

// Prevent eviction of "pinned" keys
Set<String> pinnedKeys = ConcurrentHashMap.newKeySet();
pinnedKeys.add("config:global");

OffHeapCache<String, byte[]> cache = new CacheBuilder<String, byte[]>()
        .evictionFilter(key -> !pinnedKeys.contains(key))  // true = allow eviction
        .forByteArrayValues()
        .build();
```

If the filter rejects a candidate, the eviction algorithm tries the next victim. Under heavy eviction pressure with many vetoed entries, throughput may degrade. Keep the pinned set small.

---

## Monitoring Evictions

```java
OffHeapCache.CacheStats stats = cache.getStats();

System.out.printf("Hit rate:         %.1f%%%n", stats.hitRate() * 100);
System.out.printf("Evictions total:  %d%n",    stats.evictions());
System.out.printf("  By size:        %d%n",    stats.evictionsBySize());
System.out.printf("  By TTL:         %d%n",    stats.evictionsByTtl());
System.out.printf("  By explicit:    %d%n",    stats.evictionsByExplicit());
System.out.printf("Memory usage:     %.1f%%%n", stats.memoryUsagePercent());
```

A high `evictionsBySize` rate with a low `hitRate` suggests the cache is too small for the working set — increase `offHeapMemory` or `maxEntries`.
