# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

_Nothing yet._

## [0.0.2] - 2026-06-17

### Added
- **Peer benchmark suite** — `FairComparisonScaleBenchmark` now compares RMCache (plain + OFF_HEAP GhostCache) against Caffeine (on-heap reference), Chronicle Map, OHC, MapDB, and EhCache in one internally-consistent run; new `OHCComparisonBenchmark` isolates OHC's `Unsafe`-based allocator. Results published in the README.
- **Tail-latency benchmark** — `TailLatencyBenchmark` (JMH `SampleTime`) reports p50/p90/p99/p99.9 for GET and PUT at 1M entries; the README shows RMCache's GET tail beating even on-heap Caffeine (off-heap means no GC jitter).
- **Open-source governance** — `NOTICE`, `CLA.md` (Contributor License Agreement enabling the open-core model), GitHub issue/PR templates, `CODEOWNERS`, and Dependabot configuration.
- **`examples/` subproject** — 5 runnable examples: `BasicCacheExample`, `TTLExample`, `ZeroCopyExample`, `EvictionExample`, `CustomSerializerExample`. Run via `./gradlew :examples:run<Name>`.
- **JaCoCo coverage reporting** — `jacocoTestReport` task (HTML + XML) with 65% instruction coverage minimum (`jacocoTestCoverageVerification`). Baseline: 66.6% instruction, 67.9% line.
- **MapDB and ChronicleMap as benchmark competitors** — replace NMA in `FairComparisonScaleBenchmark`, `ComparativeWorkloadBenchmark`, `ThroughputBenchmark`, and `MemoryScalabilitySuite`.
- **`asyncExecutor(Executor)`** builder option — custom bounded executor for `putAsync`/`getAsync` (replaces unbounded `ForkJoinPool.commonPool()`)
- **Per-cause eviction counters** — `evictionsBySize`, `evictionsByTtl`, `evictionsByExplicit` in `CacheStats` record, backed by `LongAdder`
- **`module-info.java`** — explicit Java module declaration (`com.codeabbot.rmcache`) with documented exports, unexported internals, and test reflection contract
- **`zeroHeapProfile()` Javadoc** — precise documentation of what the preset eliminates and what it does not eliminate from the heap
- **`SECURITY.md`** — vulnerability reporting process and security model
- **`ARCHITECTURE.md`** rewrite — merged TECHNICAL.md content, corrected entry header layout (ISSUE-017), updated file index, added design decisions table
- **`ARCHITECTURE-DEEP-DIVE.md`** — complete configuration reference and developer guide (replaces `DEVELOPER.md`)
- **`docs/getting-started.md`** — quickstart, patterns, sizing guide
- **`docs/zero-copy-access.md`** — `getZeroCopy`/`getView` safety constraints and usage patterns
- **`docs/eviction-policies.md`** — LRU, TTL, composite, eviction listener/filter reference
- **`docs/custom-serialization.md`** — custom serializer implementations, segment serializer, framework integrations
- **`docs/heap-profile.md`** — heap breakdown, zero-heap profiles, 1B-entry scale projection

### Changed
- **Build dependency management** — every dependency now declared through the Gradle version catalog (`gradle/libs.versions.toml`); JUnit (Jupiter + Platform) versions aligned via the JUnit BOM to prevent drift.
- **JDK requirement raised to 25+ (LTS)** — build config, CI matrix, all documentation updated
- **`entryCount` rename** — `_size` field renamed to `entryCount` in `LRUPolicy` and `TTLPolicy` for readability
- **`BackgroundEvictionTest`** — replaced busy-sleep (20×10ms polling) with deadline-based polling (2s window, 5ms sleep)

### Fixed
- **ISSUE-017: Entry header alignment** — swapped `slotId` and `expiresAt` positions so `expiresAt` is at offset 8 (8-byte aligned). Replaced `UNALIGNED_LONG` with `ValueLayout.JAVA_LONG`. **17–37% latency improvement** across all benchmark scales.
- **ISSUE-014: `OffHeapFrequencySketch` thread safety** — replaced plain read/write with `VarHandle.getVolatile` + single-attempt `compareAndExchange`. Eliminates lost increments under concurrent access.
- **ISSUE-018: CAS double-free guard** — replaced volatile-read + free in `SubPool.free()` with a compare-and-swap. Prevents concurrent double-free corrupting the slab allocator.
- **ISSUE-015: Duplicate LRU insert guard** — added `getSegment(slot) != NONE` guard in all three `addTo*()` methods of `OffHeapCompactLRU`. Prevents list corruption from duplicate inserts during concurrent access.
- **ISSUE-019: `RetiredSegment` typed record** — replaced `Object[]` retired segment entries with a `private record RetiredSegment(long retiredAtNanos, MemorySegment segment)`. Eliminates unsafe casts and clarifies intent.
- **ISSUE-020: `RETIRED_GRACE_NANOS` constant** — extracted hardcoded `500_000_000L` to named constant `RETIRED_GRACE_NANOS = 2_000_000_000L` (2 seconds). Safer grace period for concurrent optimistic readers during resize.
- **ISSUE-011: Murmur-style hash spread** — applied `h ^= h>>>16; h *= 0x85ebca6b; h ^= h>>>13` at all 5 `keyHash` computation sites. Prevents clustering on low-entropy keys (e.g., sequential integers).
- **Deleted dead code** — removed `CacheContext.java` (outer public class, never used — shadowed by private inner class), `CacheStatistics.java` (public record, never used — superseded by `OffHeapCache.CacheStats`), and `TimingWheel.java` (package-private, never instantiated — superseded by `OffHeapTimingWheel`)
- **Removed NMA dependency** — removed `com.target:native-memory-allocator` from all benchmark code; replaced with MapDB and ChronicleMap
- **Fixed Javadoc errors** — `<=`, `<<` HTML escaping and heading hierarchy in `AllocationHandle`, `OffHeapGhostCache`, `SegmentValueSerializer`, `ValueWriter`, `OffHeapTimingWheel`, `CacheBuilder`

### Performance

4 threads, JDK 25, macOS, 256 B values (`FairComparisonScaleBenchmark` + `OHCComparisonBenchmark`, JMH `AverageTime`, all caches measured in one run). Lower is better.

GET (ns/op):

| Cache | 10K | 100K | 1M |
|-------|----:|-----:|---:|
| RMCache | 107 | 257 | 424 |
| RMCache+Ghost | 126 | 236 | 413 |
| Chronicle Map | 251 | 305 | 445 |
| OHC | 284 | 430 | 629 |
| MapDB | 1,098 | 1,731 | 2,214 |
| EhCache | 1,639 | 1,765 | 2,003 |
| _Caffeine (on-heap ref.)_ | _65_ | _105_ | _254_ |

PUT (ns/op):

| Cache | 10K | 100K | 1M |
|-------|----:|-----:|---:|
| RMCache+Ghost | 130 | 278 | 474 |
| RMCache | 169 | 320 | 488 |
| Chronicle Map | 603 | 622 | 715 |
| OHC | 426 | 595 | 1,044 |
| EhCache | 2,460 | 2,740 | 3,095 |
| MapDB | 2,621 | 4,205 | 4,660 |
| _Caffeine (on-heap ref.)_ | _152_ | _248_ | _511_ |

RMCache is the fastest off-heap cache measured — faster than Chronicle Map, OHC, MapDB, and EhCache at every scale on both GET and PUT. On PUT it stays within range of on-heap Caffeine despite living entirely off-heap. Caffeine is listed only as an on-heap reference point, not a direct competitor.

---

## [0.0.1] - 2026-03-04

### Added
- **Off-heap cache engine** built on Java Foreign Function & Memory (FFM) API — zero `Unsafe` dependency
- **Slab allocator** with lock-free bitmap allocation (CAS-based `AtomicLongArray`), 11 size classes (64 B – 64 KB)
- **Buddy allocator** for large values exceeding slab size (>64 KB)
- **Robin Hood hash table** with `StampedLock` optimistic reads and graceful resizing
- **EntryPool** with packed slot metadata and partitioned locking (128 partitions default)
- **W-TinyLFU eviction** (3-segment SLRU + Count-Min Sketch frequency filter, fully off-heap)
- **Off-heap timing wheel** for TTL-based expiration with per-entry TTL support
- **Ghost Cache L1** — direct-mapped off-heap shortcut bypassing hash table probes
- **Zero-copy access** via `CacheValueView` / `getZeroCopy()` API
- **Background eviction** with configurable high/low watermarks (95%/90% default)
- **Memory estimator** for capacity planning (`CacheBuilder.estimateMemory()`)
- **Index memory budgeting** to constrain hash table memory usage
- **Built-in serializers** for String (UTF-8/Latin-1) and byte arrays
- **Segment value serializer** for direct native memory writes without intermediate heap buffers
- **Latin-1 fast path** for ASCII string keys (zero-allocation key encoding via `ThreadLocalKeyBuffer`)
- **Eviction listeners and filters** for custom eviction control
- **Composite eviction policy** combining LRU + TTL
- **JMH benchmarks** comparing against NMA and Ehcache
- **`CacheStats`** record with hit rate, miss rate, memory usage, and eviction counts
- **`computeIfAbsent`** with non-atomicity contract documented
- **`putIfAbsent`**, **`putAll`**, **`getAll`**, **`putAsync`**, **`getAsync`** bulk and async APIs
- **`cleanupThreadLocals()`** static method for thread-pool thread retirement

### Performance (initial 0.0.1 baseline)

4 threads, JDK 25, macOS:

| Operation | Scale | Latency |
|-----------|-------|---------|
| GET | 10 K | 179 ns |
| GET | 100 K | 360 ns |
| GET | 1 M | 590 ns |
| PUT | 10 K | 281 ns |
| PUT | 100 K | 403 ns |
| PUT | 1 M | 695 ns |

### Fixed (6 review passes, 34 fixes, zero regressions)
- **Memory leaks**: Native slab blocks freed on failed allocation
- **Overflow guard**: Fail-fast when slot capacity exceeds `Integer.MAX_VALUE`
- **LRU 30-bit guard**: Prevent slot corruption for >1.07B entries
- **CoarseClock lifecycle**: Reference-counted start/stop with synchronized release
- **Frequency sketch thread safety**: Atomic sample counter + CAS-guarded reset
- **Hash table resize safety**: Grace-period retired segment cleanup
- **Ghost cache performance**: Eliminated redundant hash table re-validation on ghost hits
- **Zero-heap profile**: Conditional key deserialization in eviction path
- **Allocation-free value reads**: Replaced `getValuePosition()` with offset/length primitives
