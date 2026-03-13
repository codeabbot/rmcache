package com.codeabbot.rmcache.examples;

import com.codeabbot.rmcache.CacheBuilder;
import com.codeabbot.rmcache.GhostCacheMode;
import com.codeabbot.rmcache.OffHeapCache;
import com.codeabbot.rmcache.Units;
import com.codeabbot.rmcache.eviction.EvictionCause;
import com.codeabbot.rmcache.eviction.LRUPolicy;
import com.codeabbot.rmcache.serializer.BuiltInSerializers;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * EvictionExample — LRU eviction policy, eviction listener callbacks,
 * eviction filter (veto), and per-cause eviction stats.
 *
 * Run: {@code ./gradlew :examples:runEvictionExample}
 */
public class EvictionExample {

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== RMCache Eviction Example ===\n");

        AtomicInteger sizeEvictions    = new AtomicInteger();
        AtomicInteger ttlEvictions     = new AtomicInteger();
        AtomicInteger explicitRemovals = new AtomicInteger();

        int maxEntries = 50;

        try (OffHeapCache<String, byte[]> cache = new CacheBuilder<String, byte[]>()
                .maxEntries(maxEntries)
                .offHeapMemory(Units.megabytes(32))
                .keySerializer(BuiltInSerializers.STRING_KEY)
                .valueSerializer(BuiltInSerializers.byteArray())
                .ghostCacheMode(GhostCacheMode.DISABLED)
                // LRU with W-TinyLFU admission filter
                .eviction(new LRUPolicy(maxEntries))
                // background eviction checks every 20ms
                .backgroundEviction(true)
                .backgroundEvictionInterval(Duration.ofMillis(20))
                // listener: counts per-cause evictions and prints first 5
                .evictionListener((key, value, cause) -> {
                    switch (cause) {
                        case SIZE     -> { if (sizeEvictions.incrementAndGet() <= 5)
                                              System.out.printf("  [evict] %-20s cause=SIZE%n", key); }
                        case EXPIRED  -> { if (ttlEvictions.incrementAndGet() <= 5)
                                              System.out.printf("  [evict] %-20s cause=EXPIRED%n", key); }
                        case EXPLICIT -> explicitRemovals.incrementAndGet();
                    }
                })
                .build()) {

            // ── Fill past capacity to trigger size-based eviction ────────────
            System.out.println("--- Filling cache to " + (maxEntries * 2) + " entries (max=" + maxEntries + ") ---");
            byte[] value = new byte[128];
            for (int i = 0; i < maxEntries * 2; i++) {
                cache.put("entry-" + i, value);
            }

            // Wait for background eviction to run
            Thread.sleep(300);

            System.out.println("Size after overflow + eviction: " + cache.size() + "  (should be ≤ " + maxEntries + ")");

            // ── TTL-based eviction ────────────────────────────────────────────
            System.out.println("\n--- TTL eviction (1s TTL) ---");
            for (int i = 0; i < 10; i++) {
                cache.put("ttl-entry-" + i, value, Duration.ofSeconds(1));
            }
            System.out.println("Inserted 10 entries with 1s TTL");

            Thread.sleep(1500);  // wait for TTL to expire + background sweep

            long expired = 0;
            for (int i = 0; i < 10; i++) {
                if (cache.get("ttl-entry-" + i) == null) expired++;
            }
            System.out.println("Expired entries after 1.5s: " + expired + "/10");

            // ── Explicit remove ───────────────────────────────────────────────
            System.out.println("\n--- Explicit remove ---");
            cache.put("manual-key", value);
            boolean removed = cache.remove("manual-key");
            System.out.println("remove(manual-key) = " + removed);

            // ── Eviction filter (veto) ────────────────────────────────────────
            System.out.println("\n--- Eviction filter (pin critical entries) ---");
            try (OffHeapCache<String, byte[]> filteredCache = new CacheBuilder<String, byte[]>()
                    .maxEntries(10)
                    .offHeapMemory(Units.megabytes(16))
                    .keySerializer(BuiltInSerializers.STRING_KEY)
                    .valueSerializer(BuiltInSerializers.byteArray())
                    .ghostCacheMode(GhostCacheMode.DISABLED)
                    .eviction(new LRUPolicy(10))
                    .evictionFilter((key, meta) -> !key.startsWith("critical:"))  // veto critical keys
                    .backgroundEviction(true)
                    .backgroundEvictionInterval(Duration.ofMillis(20))
                    .build()) {

                filteredCache.put("critical:config", value);
                filteredCache.put("critical:schema", value);

                // Flood with regular entries to pressure eviction
                for (int i = 0; i < 30; i++) {
                    filteredCache.put("regular-" + i, value);
                    Thread.sleep(5);
                }
                Thread.sleep(200);

                boolean configAlive  = filteredCache.get("critical:config") != null;
                boolean schemaAlive  = filteredCache.get("critical:schema") != null;
                System.out.println("critical:config survived eviction: " + configAlive);
                System.out.println("critical:schema survived eviction: " + schemaAlive);
            }

            // ── Stats ─────────────────────────────────────────────────────────
            System.out.println("\n--- Eviction Stats ---");
            OffHeapCache.CacheStats stats = cache.getStats();
            System.out.println("Total evictions:    " + stats.evictions());
            System.out.println("  By size:          " + stats.evictionsBySize());
            System.out.println("  By TTL:           " + stats.evictionsByTtl());
            System.out.println("  By explicit:      " + stats.evictionsByExplicit());
            System.out.printf( "Hit rate:           %.1f%%%n", stats.hitRate() * 100);
            System.out.printf( "Memory usage:       %.1f%%%n", stats.memoryUsagePercent());

            System.out.println("\n=== Done ===");
        }
    }
}
