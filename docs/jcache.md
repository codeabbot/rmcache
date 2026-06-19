# JCache (JSR-107) Provider

`rmcache-jcache` is a standard [JSR-107](https://github.com/jsr107/jsr107spec) (`javax.cache`)
provider backed by RMCache's off-heap store. It lets RMCache slot into anything that targets the
JCache API — **Spring Cache** (`JCacheCacheManager`), **Hibernate** second-level cache
(`hibernate-jcache`), or direct `javax.cache` use — while keeping keys and values off-heap.

```gradle
implementation 'com.codeabbot:rmcache-jcache:0.0.2'
```

The provider is auto-discovered via `META-INF/services`, so `Caching.getCachingProvider()` finds it.

## Quick start

```java
import javax.cache.*;
import com.codeabbot.rmcache.jcache.RMCacheConfiguration;

CachingProvider provider = Caching.getCachingProvider();
CacheManager manager = provider.getCacheManager();

Cache<String, byte[]> cache = manager.createCache("users",
        new RMCacheConfiguration<String, byte[]>()
                .setTypes(String.class, byte[].class)
                .setOffHeapMemoryBytes(4L << 30)   // 4 GB off-heap
                .setMaxEntries(20_000_000));

cache.put("user:1", data);
byte[] v = cache.get("user:1");
```

## Sizing — `RMCacheConfiguration`

JSR-107 has no vocabulary for off-heap sizing, so use `RMCacheConfiguration` (extends
`MutableConfiguration`) to set it:

| Setter | Default | Notes |
| :--- | :--- | :--- |
| `setOffHeapMemoryBytes(long)` | 64 MB | Native memory the cache reserves |
| `setMaxEntries(int)` | 100 000 | Hard entry cap |
| `setGhostCacheMode(GhostCacheMode)` | `DISABLED` | Admission history (`OFF_HEAP` keeps it off-heap) |

The defaults are deliberately modest so a bare `createCache` never silently commits gigabytes —
size up explicitly for large caches. A plain `MutableConfiguration` also works (it gets the
defaults above).

## Store-by-value & serializers

RMCache always serializes keys/values off-heap, so the provider is **store-by-value** by nature
(`isSupported(STORE_BY_REFERENCE)` is `false`). Keys and values must be `Serializable`. Serializers
are chosen by type: `String`, `Integer`, `Long`, and `byte[]` use RMCache's canonical fast
serializers; anything else falls back to JDK serialization (keys must serialize deterministically —
true for the standard types and well-behaved value objects).

## Operations & atomicity

The common surface is supported: `get`/`getAll`, `put`/`putAll`/`getAndPut`, `putIfAbsent`,
`remove`/`remove(k,v)`/`getAndRemove`, `replace`/`replace(k,old,new)`/`getAndReplace`,
`containsKey`, `clear`/`removeAll`, and `invoke`/`invokeAll`.

All mutating operations and the read-modify-write ops (including `invoke`) execute under a **per-key
striped lock** (256 stripes), so an `EntryProcessor` in `invoke` runs **atomically** with respect to
other writers of the same key. Plain `get` is lock-free.

```java
// Atomic read-modify-write
cache.invoke("counter", (entry, args) -> {
    int n = entry.exists() ? entry.getValue() : 0;
    entry.setValue(n + 1);
    return null;
});
```

`unwrap(OffHeapCache.class)` returns the underlying RMCache for off-heap-specific features.

## Expiry

`ExpiryPolicy` maps to RMCache TTL: `EternalExpiryPolicy` (default) and `ModifiedExpiryPolicy`
map exactly; `CreatedExpiryPolicy`/`AccessedExpiryPolicy` apply the creation TTL on insert (their
"no change on update" / access-extension semantics are not preserved in Phase 1).

## Statistics & management (JMX)

```java
new RMCacheConfiguration<String, byte[]>()
    .setTypes(String.class, byte[].class)
    .setStatisticsEnabled(true)
    .setManagementEnabled(true);
```

Registers the JSR-107 `CacheStatisticsMXBean` (hits/misses/puts/removals/evictions, backed by
`getStats()`) and `CacheMXBean` under the standard `javax.cache:type=Cache*` object names. Average
get/put/remove times report `0` in Phase 1.

## Phase-1 limitations

This is the pragmatic-but-correct first phase. The following throw `UnsupportedOperationException`
(targeted for Phase 2) — none of them are needed by Spring Cache or Hibernate L2:

- `iterator()` — the off-heap core exposes no entry iterator.
- `registerCacheEntryListener` / entry-event listeners.
- Read-through / write-through `CacheLoader` / `CacheWriter` (`loadAll` is a no-op when no loader is configured).

`removeAll()` (no args) maps to `clear()` (no per-entry listener events, since listeners are Phase 2).
