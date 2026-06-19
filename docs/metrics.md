# Metrics

RMCache exposes cache statistics with **zero hot-path cost** — nothing on the `get`/`put` path
emits a metric. Counters live in the core as contention-free `LongAdder`s, surfaced through
`OffHeapCache.getStats()`, and the integration modules read that snapshot only when the metrics
backend collects (the scrape/export interval).

## Core statistics — `getStats()`

Every cache exposes a `CacheStats` snapshot directly, with no extra dependency:

```java
OffHeapCache.CacheStats s = cache.getStats();
s.hits(); s.misses(); s.puts(); s.removes(); s.evictions();
s.size(); s.memoryUsedBytes(); s.memoryTotalBytes();
s.evictionsBySize(); s.evictionsByTtl(); s.evictionsByExplicit();
s.hitRate(); s.missRate(); s.memoryUsagePercent();
```

The counters that feed this (hits/misses on `get`, puts on `put`/`putIfAbsent`, removes on
`remove`, evictions by cause) are always-on and effectively free — see the design notes in
[Architecture](../ARCHITECTURE.md).

## Micrometer — `rmcache-micrometer`

```gradle
implementation 'com.codeabbot:rmcache-micrometer:0.0.2'
```

```java
import com.codeabbot.rmcache.micrometer.RMCacheMicrometerMetrics;

// Binds the cache to a MeterRegistry under the given name (+ optional tags).
RMCacheMicrometerMetrics.monitor(meterRegistry, cache, "users", "region", "eu");
```

It is a standard Micrometer `CacheMeterBinder`, so you get the conventional cache meters plus
RMCache-specific ones:

| Meter | Tags | Meaning |
| :--- | :--- | :--- |
| `cache.gets` | `result=hit\|miss` | get hits / misses |
| `cache.puts` | — | put count |
| `cache.removes` | — | remove count |
| `cache.evictions` | `cause=size\|ttl\|explicit` | evictions by cause |
| `cache.size` | — | live entry count |
| `cache.memory.used` / `cache.memory.max` | — | off-heap bytes used / budget |

Meters are **pull-based**: each reads `cache.getStats()` on the registry's collection interval,
never on the cache's hot path.

## OpenTelemetry — `rmcache-opentelemetry`

```gradle
implementation 'com.codeabbot:rmcache-opentelemetry:0.0.2'
```

```java
import com.codeabbot.rmcache.opentelemetry.RMCacheOpenTelemetryMetrics;

AutoCloseable handle = RMCacheOpenTelemetryMetrics.register(meter, cache, "users");
// ... on shutdown:
handle.close();
```

Every instrument is **observable** (async): its callback reads `getStats()` only when the OTel SDK
collects (the export interval). Emits `cache.gets` (`result=hit|miss`), `cache.puts`,
`cache.removes`, `cache.evictions` (`cause=size|ttl|explicit`), `cache.size`, `cache.memory.used`,
`cache.memory.max`, each tagged with the `cache` name.

## Per-operation latency — `rmcache-metrics`

When you want **latency** (not just counts), the `rmcache-metrics` module provides
`MeteredOffHeapCache`, a thin decorator that samples a configurable fraction of operations and
records their latency off the hot path:

```gradle
implementation 'com.codeabbot:rmcache-metrics:0.0.2'
```

```java
import com.codeabbot.rmcache.metrics.MeteredOffHeapCache;
import com.codeabbot.rmcache.metrics.CacheMetricsSnapshot;

// Sample 1 in N operations (N=0 disables sampling = pure pass-through).
MeteredOffHeapCache<String, byte[]> metered = new MeteredOffHeapCache<>(core, 128);
...
CacheMetricsSnapshot snap = metered.snapshot();
snap.sampledOps(); snap.meanSampledLatencyNanos(); snap.maxLatencyNanos();
```

Sampling uses `ThreadLocalRandom` for the decision and a `LongAdder` for the accumulator — no
per-op allocation, no contention, no `ThreadLocal` leak. Counts still come from the core's
`getStats()`, surfaced through the decorator.
