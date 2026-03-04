# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0] - 2026-03-04

### Added
- **Off-heap cache engine** built on Java Foreign Function & Memory (FFM) API — zero `Unsafe` dependency
- **Slab allocator** with lock-free bitmap allocation (CAS-based `AtomicLongArray`)
- **Buddy allocator** for large values exceeding slab size
- **Robin Hood hash table** with StampedLock optimistic reads and graceful resizing
- **EntryPool** with packed slot metadata and partitioned locking
- **W-TinyLFU eviction** (3-segment SLRU + Count-Min Sketch frequency filter)
- **Off-heap timing wheel** for TTL-based expiration with per-entry TTL support
- **Ghost Cache L1** — direct-mapped off-heap shortcut bypassing hash table probes
- **Zero-copy access** via `CacheValueView` / `getZeroCopy()` API
- **Background eviction** with configurable high/low watermarks
- **Memory estimator** for capacity planning (`CacheBuilder.estimateMemory()`)
- **Index memory budgeting** to constrain hash table memory usage
- **Built-in serializers** for String (UTF-8/Latin1) and byte arrays
- **Segment value serializer** for direct native memory writes without intermediate heap buffers
- **Latin1 fast path** for ASCII string keys (zero-allocation key encoding)
- **Eviction listeners and filters** for custom eviction control
- **Composite eviction policy** combining LRU + TTL
- **JMH benchmarks** comparing against NMA and Ehcache

### Performance (4 threads, JDK 25, macOS)
| Operation | 10K | 100K | 1M |
|-----------|-----|------|----|
| GET | 124 ns | 307 ns | 503 ns |
| PUT | 199 ns | 358 ns | 471 ns |
| Ghost GET | 148 ns | 300 ns | 527 ns |
| Ghost PUT | 250 ns | 307 ns | 490 ns |

### Fixed (6 review passes, 34 fixes total)
- **Memory leaks**: Native slab blocks freed on failed allocation (E2E-C2)
- **Overflow guard**: Fail-fast when slot capacity exceeds `Integer.MAX_VALUE` (E2E-C1)
- **LRU 30-bit guard**: Prevent slot corruption for >1B entries (C2)
- **CoarseClock lifecycle**: Reference-counted start/stop with synchronized release (C5, H6)
- **Frequency sketch thread safety**: Atomic sample counter + CAS-guarded reset (H2)
- **Hash table resize safety**: Grace-period retired segment cleanup (M3)
- **Ghost cache performance**: Eliminated redundant hash table re-validation on ghost hits
- **Zero-heap profile**: Conditional key deserialization in eviction path (E2E-H3)
- **Allocation-free value reads**: Replaced `getValuePosition()` with offset/length primitives (E2E-E2)
