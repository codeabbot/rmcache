package com.codeabbot.rmcache.examples;

import com.codeabbot.rmcache.CacheBuilder;
import com.codeabbot.rmcache.GhostCacheMode;
import com.codeabbot.rmcache.OffHeapCache;
import com.codeabbot.rmcache.Units;
import com.codeabbot.rmcache.serializer.BuiltInSerializers;

import java.nio.charset.StandardCharsets;

/**
 * BasicCacheExample — put, get, remove, contains, size, stats.
 *
 * Run: {@code ./gradlew :examples:runBasicCacheExample}
 */
public class BasicCacheExample {

    public static void main(String[] args) {
        System.out.println("=== RMCache Basic Example ===\n");

        try (OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(10_000)
                .offHeapMemory(Units.megabytes(64))
                .keySerializer(BuiltInSerializers.STRING_KEY)
                .valueSerializer(BuiltInSerializers.string())
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .build()) {

            // ── put ──────────────────────────────────────────────────────────
            cache.put("user:1", "Alice");
            cache.put("user:2", "Bob");
            cache.put("user:3", "Charlie");
            System.out.println("Inserted 3 entries");
            System.out.println("Size: " + cache.size());

            // ── get ──────────────────────────────────────────────────────────
            String alice = cache.get("user:1");
            String missing = cache.get("user:99");
            System.out.println("\nget(user:1)  = " + alice);
            System.out.println("get(user:99) = " + missing + "  (not found → null)");

            // ── contains ─────────────────────────────────────────────────────
            System.out.println("\ncontains(user:2)  = " + cache.contains("user:2"));
            System.out.println("contains(user:99) = " + cache.contains("user:99"));

            // ── remove ───────────────────────────────────────────────────────
            boolean removed = cache.remove("user:2");
            System.out.println("\nremove(user:2) = " + removed);
            System.out.println("Size after remove: " + cache.size());

            // ── putIfAbsent ───────────────────────────────────────────────────
            boolean inserted = cache.putIfAbsent("user:1", "AliceNew");  // already exists
            boolean newInsert = cache.putIfAbsent("user:4", "Dave");     // new key
            System.out.println("\nputIfAbsent(user:1, AliceNew) = " + inserted + "  (false — already present)");
            System.out.println("putIfAbsent(user:4, Dave)     = " + newInsert + "   (true — inserted)");
            System.out.println("get(user:1) still = " + cache.get("user:1"));
            System.out.println("get(user:4)       = " + cache.get("user:4"));

            // ── computeIfAbsent ───────────────────────────────────────────────
            String computed = cache.computeIfAbsent("user:5", k -> "Computed-" + k.toUpperCase());
            System.out.println("\ncomputeIfAbsent(user:5) = " + computed);

            // ── putAll / getAll ───────────────────────────────────────────────
            java.util.Map<String, String> batch = java.util.Map.of(
                    "product:1", "Widget", "product:2", "Gadget", "product:3", "Doohickey");
            cache.putAll(batch);
            java.util.Map<String, String> results = cache.getAll(
                    java.util.List.of("product:1", "product:2", "product:missing"));
            System.out.println("\nputAll + getAll (3 keys, 1 missing): " + results);

            // ── stats ─────────────────────────────────────────────────────────
            // Force some hits and misses
            for (int i = 0; i < 100; i++) {
                cache.get("user:" + (i % 6));  // some hits, some misses
            }
            OffHeapCache.CacheStats stats = cache.getStats();
            System.out.printf("%n--- Stats ---%n");
            System.out.printf("Hits:           %d%n", stats.hits());
            System.out.printf("Misses:         %d%n", stats.misses());
            System.out.printf("Hit rate:       %.1f%%%n", stats.hitRate() * 100);
            System.out.printf("Size:           %d%n", stats.size());
            System.out.printf("Memory used:    %.2f KB%n", stats.memoryUsedBytes() / 1024.0);
            System.out.printf("Memory total:   %.2f MB%n", stats.memoryTotalBytes() / 1_048_576.0);
            System.out.printf("Memory usage:   %.1f%%%n", stats.memoryUsagePercent());

            // ── latency sample ────────────────────────────────────────────────
            int rounds = 100_000;
            long start = System.nanoTime();
            for (int i = 0; i < rounds; i++) {
                cache.get("user:" + (i % 5));
            }
            long elapsed = System.nanoTime() - start;
            System.out.printf("%nAvg GET latency over %,d calls: %.0f ns%n", rounds, (double) elapsed / rounds);

            System.out.println("\n=== Done. Cache closed automatically via try-with-resources. ===");
        }
    }
}
