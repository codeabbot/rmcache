# Contributing to RMCache

Thank you for your interest in contributing to RMCache! This guide will help you get started.

## Prerequisites

- **JDK 25+** (LTS — Foreign Function & Memory API stable since JDK 25)
- **Gradle 9.x** (wrapper included: `./gradlew`)

## Getting Started

```bash
# Clone the repository
git clone https://github.com/codeabbot/rmcache.git
cd rmcache

# Build
./gradlew build

# Run all tests
./gradlew test

# Run JMH benchmarks
./gradlew jmh -Pjmh.includes="FairComparisonScaleBenchmark"
```

> **Note:** All tasks automatically pass `--enable-native-access=ALL-UNNAMED` to the JVM.

## How to Contribute

### Reporting Issues

- Use [GitHub Issues](https://github.com/codeabbot/rmcache/issues) to report bugs or request features.
- Include: JDK version, OS, steps to reproduce, and expected vs actual behavior.
- For performance issues, include JMH benchmark results.

### Submitting Pull Requests

1. **Fork** the repository and create your branch from `main`.
2. **Write tests** for any new functionality or bug fix.
3. **Run the full test suite** (`./gradlew test`) and ensure all tests pass.
4. **Run benchmarks** if your change touches the hot path (`get`, `put`, `remove`, allocator, hash table, eviction). Ensure no regressions.
5. **Update documentation** if your change affects the public API.
6. **Submit a PR** with a clear title and description.

### Commit Message Format

Use descriptive commit messages:

```
<type>: <short summary>

<optional body explaining the rationale>
```

Types: `feat`, `fix`, `perf`, `refactor`, `docs`, `test`, `chore`

Examples:
- `feat: add per-entry TTL support via OffHeapTimingWheel`
- `fix: prevent native memory leak when slot allocation returns 0`
- `perf: eliminate redundant hash table re-validation from ghost cache GET`

## Code Style

- **Indentation:** 4 spaces (no tabs)
- **Line length:** 120 characters max
- **Naming:** Standard Java conventions (`camelCase` for methods/fields, `PascalCase` for classes)
- **Javadoc:** All public classes and methods must have Javadoc
- **Thread safety:** Document thread-safety guarantees in class-level Javadoc

## Benchmarking Policy

**Zero-regression rule:** Every change that touches the hot path (`get`, `put`, `remove`, allocator, hash table, eviction policy) must run `FairComparisonScaleBenchmark` before and after. A PR that regresses any benchmark metric will not be merged.

```bash
# Run the comparison benchmark (4 threads, 3 warmup + 5 measurement iterations)
./gradlew jmh -Pjmh.includes="FairComparisonScaleBenchmark"

# Run the RMCache-only benchmark (faster, for iterative tuning)
./gradlew jmh -Pjmh.includes="RMCacheOnlyBenchmark"
```

Include benchmark output in your PR description. Compare before/after at 10K, 100K, and 1M entry scales.

## PR Checklist

Before submitting:
- [ ] I have read and agree to the [Contributor License Agreement](CLA.md)
- [ ] `./gradlew test` passes (all tests green)
- [ ] If touching hot path: JMH before/after included, no regression
- [ ] Javadoc added/updated for all public API changes
- [ ] `CHANGELOG.md` updated under `[Unreleased]`
- [ ] No `Unsafe` usage — use `java.lang.foreign` APIs only

## Architecture Overview

See [ARCHITECTURE.md](ARCHITECTURE.md) for the full technical design, and [ARCHITECTURE-DEEP-DIVE.md](ARCHITECTURE-DEEP-DIVE.md) for the configuration reference and developer guide.

## Code of Conduct

This project follows the [Contributor Covenant Code of Conduct](CODE_OF_CONDUCT.md). By participating, you agree to uphold this code.

## Contributor License Agreement (CLA)

Before your first contribution can be merged, you must agree to the project's
[Contributor License Agreement](CLA.md). In short: **you keep ownership of your work**
and license it to the project under the Apache License 2.0, while also granting the
maintainer the right to offer the project under additional terms in the future (an
open-core model). You accept the CLA by checking the box in the pull-request template
on your first PR.

The open-source distribution of RMCache, including your contributions, remains licensed
under the [Apache License 2.0](LICENSE).
