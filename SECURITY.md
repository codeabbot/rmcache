# Security Policy

## Supported Versions

| Version | Supported |
|---------|-----------|
| 0.0.x (latest) | ✅ Active |

Only the latest release receives security fixes. Update to the current release before reporting an issue.

---

## Reporting a Vulnerability

**Please do not file a public GitHub issue for security vulnerabilities.**

Report security issues privately via GitHub's [Security Advisory](https://github.com/codeabbot/rmcache/security/advisories/new) feature, or email the maintainer at **maintainers@codeabbot.com**.

Include:
- A description of the vulnerability and its potential impact
- Steps to reproduce or a proof-of-concept
- The version of RMCache affected
- Your JDK version and OS

You will receive an acknowledgement within 72 hours. Confirmed vulnerabilities will be patched and disclosed publicly after a fix is available.

---

## Security Model

RMCache operates as a **library within a trusted JVM process**. It is not a network service and does not open ports or accept external input. The threat model is:

### In-Scope

- **Memory safety bugs** — incorrect off-heap reads/writes could corrupt data or cause JVM crashes. RMCache uses the Java FFM API exclusively (no `Unsafe`) and bounds-checks `CacheValueView` access.
- **Double-free vulnerabilities** — concurrent eviction and explicit `remove()` could free the same native block twice. Guarded by CAS in `SubPool.free()`.
- **Use-after-free** — retired hash table segments reused during grace period. Guarded by `RetiredSegment` 2-second grace period.
- **Integer overflow** — `slotCapacity` overflow into OffHeapCompactLRU's 30-bit limit. Guarded by `CacheBuilder` fail-fast.

### Out-of-Scope

- **Denial of service** via crafted keys — RMCache is a library in a trusted process; the caller controls key space.
- **Authentication / authorization** — RMCache has no access control; rely on your application's security boundary.
- **Network-level attacks** — RMCache has no network surface.

### `NativeMemory.UNLIMITED`

The `NativeMemory.UNLIMITED` segment (`MemorySegment.ofAddress(0L).reinterpret(Long.MAX_VALUE)`) is an internal implementation detail. It is used **only** within the slab allocator for performance (eliminating per-access bounds checks). It is never exposed through the public API. All public API entry points (`getView`, `getZeroCopy`) have explicit bounds checking.

---

## Dependency Security

RMCache's only runtime dependency is SLF4J (logging facade). Monitor your chosen SLF4J binding (Logback, Log4j2) for CVEs using your dependency scanner.

---

## JDK Requirements

RMCache requires JDK 25+ and uses the stable (non-preview) FFM API. JDK 25 is an LTS release with long-term security patch support from Oracle and OpenJDK vendors.
