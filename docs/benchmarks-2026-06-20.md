# Benchmark Results — Competitor Shootout (2026-06-20)

A full JMH sweep of RMCache against the major JVM off-heap caches across **throughput (TPS)**,
**average latency**, and **tail latency**. Every cache is populated and measured in the *same run*,
so the relative ordering is the signal even as absolute numbers shift with hardware and load.

**Headline:** RMCache is the fastest off-heap cache at every scale on every metric measured. Its PUT
keeps pace with — and at the extremes beats — on-heap Caffeine, and it has the lowest GET tail of
any cache here, including Caffeine. The only place an off-heap peer leads is Chronicle Map at the
extreme PUT p99.9.

> These are point-in-time numbers from one machine. They reproduce the published
> [README benchmark tables](../README.md#benchmarks) within run-to-run variance. Reproduce on your
> own hardware before quoting — see [How to reproduce](#how-to-reproduce).

---

## Test Environment

| | |
|---|---|
| **Hardware** | Apple M1 Max, 10 cores, 32 GB RAM |
| **OS** | macOS 26.5.1 (arm64) |
| **JDK** | Eclipse Temurin 25.0.3+9-LTS |
| **JMH** | 1.37 — 4 threads, 256-byte values, 1 fork, no eviction |
| **Iterations** | Throughput & avg-latency: 1 warmup + 2 measurement. Tail: 2 warmup + 4 measurement (more samples for stable percentiles). |
| **Peers** | Caffeine 3.1.8, Chronicle Map 2026.1, OHC 0.7.4, MapDB 3.0.9, EhCache 3.10.8 |

**Caffeine is shown as an on-heap reference, not an off-heap competitor.** It never pays the cost of
crossing the heap boundary — comparing it to off-heap caches is apples-to-oranges, included only to
mark the on-heap ceiling. RMCache's `+ Ghost` variant runs `GhostCacheMode.OFF_HEAP`; the plain
variant runs with the ghost cache disabled.

---

## Throughput — TPS (M ops/s, higher is better)

Source: `ThroughputComparisonBenchmark` (`Mode.Throughput`), 100K and 1M entries.

### GET

| Cache | 100K | 1M |
| :--- | ---: | ---: |
| **RMCache** | 16.94 | 9.80 |
| **RMCache + Ghost** | 16.86 | 9.40 |
| Chronicle Map | 13.16 | 8.29 |
| OHC | 10.09 | 6.43 |
| MapDB | 3.24 | 2.40 |
| EhCache | 2.24 | 2.02 |
| _Caffeine — on-heap ref_ | _46.84_ | _15.47_ |

### PUT

| Cache | 100K | 1M |
| :--- | ---: | ---: |
| **RMCache** | 11.55 | 8.62 |
| **RMCache + Ghost** | 13.17 | 8.56 |
| Chronicle Map | 7.62 | 5.71 |
| OHC | 7.57 | 3.65 |
| MapDB | 0.99 | 0.84 |
| EhCache | 1.51 | 1.28 |
| _Caffeine — on-heap ref_ | _16.89_ | _8.30_ |

At 1M entries, RMCache leads the next-fastest off-heap cache by **1.18× on GET** (9.80M vs Chronicle's
8.29M) and **1.51× on PUT** (8.62M vs Chronicle's 5.71M), and out-runs MapDB/EhCache by **4–10×**.
RMCache's 1M PUT throughput (8.62M ops/s) edges past on-heap Caffeine (8.30M).

---

## Average Latency (ns/op, lower is better)

Source: `FairComparisonScaleBenchmark` + `OHCComparisonBenchmark` (`Mode.AverageTime`), 10K / 100K / 1M entries.

### GET

| Cache | 10K | 100K | 1M |
| :--- | ---: | ---: | ---: |
| **RMCache** | 114 | 234 | 415 |
| **RMCache + Ghost** | 91 | 241 | 426 |
| Chronicle Map | 254 | 330 | 489 |
| OHC | 251 | 395 | 610 |
| MapDB | 878 | 1,210 | 1,763 |
| EhCache | 1,723 | 2,039 | 2,090 |
| _Caffeine — on-heap ref_ | _71_ | _90_ | _299_ |

### PUT

| Cache | 10K | 100K | 1M |
| :--- | ---: | ---: | ---: |
| **RMCache + Ghost** | 135 | 285 | 476 |
| **RMCache** | 141 | 325 | 543 |
| Chronicle Map | 495 | 549 | 725 |
| OHC | 325 | 543 | 897 |
| EhCache | 2,629 | 2,927 | 3,150 |
| MapDB | 2,779 | 4,573 | 4,874 |
| _Caffeine — on-heap ref_ | _199_ | _246_ | _548_ |

- **GET:** RMCache is the fastest off-heap cache at every scale, trailing only on-heap Caffeine — and
  by the smallest margin of any off-heap cache (~1.4× at 1M). That gap is physics: Caffeine returns
  an object reference; an off-heap cache must read native memory and materialize the value.
- **PUT:** RMCache + Ghost is the fastest off-heap cache at every scale and **rivals or beats on-heap
  Caffeine** — faster at 10K (135 vs 199 ns) and 1M (476 vs 548 ns), within ~15% at 100K. Off-heap
  writes normally carry a heavy penalty; RMCache nearly erases it.

---

## Tail Latency (ns/op at 1M entries, lower is better)

Source: `TailLatencyBenchmark` (`Mode.SampleTime`), ~2.4–2.7M samples per method. Values quantize to
the sampling-timer resolution (~41 ns), so the "round" numbers are expected. `p1.00` (max) is omitted
— it reflects JVM/OS outliers (safepoints, scheduling), not cache behavior.

### GET

| Cache | p50 | p90 | p99 | p99.9 | mean |
| :--- | ---: | ---: | ---: | ---: | ---: |
| **RMCache** | 459 | 583 | **708** | **3,456** | 483 |
| **RMCache + Ghost** | 458 | 625 | 833 | 4,416 | 489 |
| Chronicle Map | 500 | 625 | 750 | 3,534 | 535 |
| _Caffeine — on-heap ref_ | _250_ | _459_ | _791_ | _7,080_ | _298_ |

### PUT

| Cache | p50 | p90 | p99 | p99.9 | mean |
| :--- | ---: | ---: | ---: | ---: | ---: |
| **RMCache** | 541 | 666 | **834** | 5,872 | 580 |
| **RMCache + Ghost** | 459 | 667 | 1,000 | 14,448 | 585 |
| Chronicle Map | 708 | 958 | 1,332 | **4,328** | 779 |
| _Caffeine — on-heap ref_ | _459_ | _708_ | _1,084_ | _8,528_ | _593_ |

- **RMCache owns the GET tail outright, including against on-heap Caffeine** — lowest p99 (708 ns)
  *and* p99.9 (3,456 ns) of every cache. Caffeine wins the median (250 ns, on-heap) but its tail
  blows out to 7,080 ns at p99.9 — **2.0× wider than RMCache**. That is GC jitter; off-heap doesn't
  have it. This is the core payoff of the design.
- **RMCache leads PUT through p99** (834 ns, lowest of all). At the extreme p99.9, Chronicle Map's
  mmap write path is tighter (4,328 ns vs RMCache's 5,872); RMCache still beats Caffeine (8,528).
- **GhostCache trades the extreme PUT tail for a better median.** Plain RMCache p99.9 PUT (5,872 ns)
  is much cleaner than `+ Ghost` (14,448 ns) — if you tune specifically for p99.9 writes, prefer the
  ghost cache disabled.

---

## How to reproduce

From the repository root (JDK 25 is auto-provisioned by the Gradle toolchain):

```bash
# Throughput (TPS) — all 7 caches, GET + PUT
./gradlew jmh -Pjmh.includes="ThroughputComparisonBenchmark"

# Average latency — all caches, GET + PUT at 10K / 100K / 1M
./gradlew jmh -Pjmh.includes="FairComparisonScaleBenchmark|OHCComparisonBenchmark"

# Tail latency — percentiles at 1M (extra iterations for stable tails)
./gradlew jmh -Pjmh.includes="TailLatencyBenchmark" -Pjmh.iterations=4 -Pjmh.warmupIterations=2
```

JMH writes its summary table to `build/results/jmh/results.txt`. The full sweep above took ~54 min of
wall-clock on the test machine (most of it per-benchmark cache population, not measurement).

---

## Caveats

- **Representative, not error-bar-grade.** 1 fork × 2 measurement iterations gives stable relative
  ordering but not publication-grade confidence intervals. For that, raise to `-Pjmh.iterations=5
  -Pjmh.warmupIterations=3` and 2+ forks (multi-hour run).
- **EhCache** emits verbose internal `DEBUG` allocation logging on its measured path; its absolute
  numbers may be marginally pessimistic. It is the slowest cache here by a wide margin regardless, so
  the ranking is unaffected.
- **Hardware-specific.** These are Apple M1 Max numbers. Absolute latencies and throughput shift with
  CPU, memory bandwidth, and load — the *relative* ordering is what travels.
- **On-heap vs off-heap.** Caffeine is on-heap and pays no heap-boundary cost; it is a reference
  ceiling, not a like-for-like competitor. RMCache's value is off-heap capacity (billions of entries,
  zero GC pressure) *at* near-on-heap speed.
</content>
</invoke>
