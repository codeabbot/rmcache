## Summary

<!-- What does this PR change, and why? -->

## Related issues

<!-- e.g. Closes #123 -->

## Type of change

- [ ] Bug fix
- [ ] New feature
- [ ] Performance improvement
- [ ] Refactor / cleanup
- [ ] Documentation
- [ ] Build / CI / chore

## Checklist

- [ ] I have read and agree to the [Contributor License Agreement](CLA.md)
- [ ] `./gradlew test` passes (all tests green)
- [ ] My code uses only `java.lang.foreign` APIs (no `sun.misc.Unsafe`)
- [ ] Javadoc added/updated for any public API changes
- [ ] `CHANGELOG.md` updated under `[Unreleased]`

## Hot-path changes

If this PR touches `get`, `put`, `remove`, the allocator, hash table, or eviction
policy, the zero-regression rule applies — otherwise delete this section.

- [ ] I ran `FairComparisonScaleBenchmark` before and after
- [ ] No regression at 10K / 100K / 1M scales

<details>
<summary>Benchmark before / after</summary>

```
<!-- paste JMH results here -->
```

</details>
