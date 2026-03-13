package com.codeabbot.rmcache.examples;

import com.codeabbot.rmcache.CacheBuilder;
import com.codeabbot.rmcache.GhostCacheMode;
import com.codeabbot.rmcache.OffHeapCache;
import com.codeabbot.rmcache.Units;
import com.codeabbot.rmcache.serializer.BuiltInSerializers;

import java.time.Duration;

/**
 * TTLExample — per-entry TTL, expiry observation, computeIfAbsent with loader.
 *
 * <p>CoarseClock has ±100ms granularity, so this example uses short but
 * observable TTL values (1–2 seconds).
 *
 * Run: {@code ./gradlew :examples:runTTLExample}
 */
public class TTLExample {

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== RMCache TTL Example ===\n");

        try (OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(10_000)
                .offHeapMemory(Units.megabytes(64))
                .keySerializer(BuiltInSerializers.STRING_KEY)
                .valueSerializer(BuiltInSerializers.string())
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .build()) {

            // ── Per-entry TTL ─────────────────────────────────────────────────
            cache.put("short-lived", "I will expire soon", Duration.ofSeconds(1));
            cache.put("long-lived", "I persist for a while", Duration.ofSeconds(10));
            cache.put("permanent", "No TTL set");

            System.out.println("Immediately after insertion:");
            System.out.println("  short-lived = " + cache.get("short-lived"));
            System.out.println("  long-lived  = " + cache.get("long-lived"));
            System.out.println("  permanent   = " + cache.get("permanent"));

            // Wait for short-lived to expire (CoarseClock ±100ms granularity)
            System.out.println("\nSleeping 1.5 seconds...");
            Thread.sleep(1500);

            System.out.println("\nAfter 1.5s:");
            System.out.println("  short-lived = " + cache.get("short-lived") + "  (null → expired)");
            System.out.println("  long-lived  = " + cache.get("long-lived") + "  (still alive)");
            System.out.println("  permanent   = " + cache.get("permanent"));

            // ── Session tokens with TTL ───────────────────────────────────────
            System.out.println("\n--- Session Token Example ---");
            for (int i = 1; i <= 5; i++) {
                cache.put("session:" + i, "token-" + i, Duration.ofSeconds(2));
            }
            System.out.println("Inserted 5 sessions with 2s TTL");
            System.out.println("Active sessions: " + cache.size());

            // ── computeIfAbsent with TTL ──────────────────────────────────────
            System.out.println("\n--- computeIfAbsent (cache-aside pattern) ---");

            // First call — miss → loader called
            long t0 = System.currentTimeMillis();
            String value = cache.computeIfAbsent("db:user:42", key -> {
                System.out.println("  [loader] fetching " + key + " from DB...");
                return "User-42-from-DB";
            });
            System.out.println("  First call  → " + value + " (" + (System.currentTimeMillis() - t0) + "ms)");

            // Second call — hit → loader NOT called
            t0 = System.currentTimeMillis();
            value = cache.computeIfAbsent("db:user:42", key -> {
                System.out.println("  [loader] This should NOT print (cache hit)");
                return "Should-Not-Appear";
            });
            System.out.println("  Second call → " + value + " (" + (System.currentTimeMillis() - t0) + "ms)  ← from cache, loader skipped");

            // ── putIfAbsent returns false on existing key ──────────────────────
            System.out.println("\n--- putIfAbsent ---");
            boolean r1 = cache.putIfAbsent("permanent", "NewValue");
            boolean r2 = cache.putIfAbsent("brand-new-key", "InitialValue");
            System.out.println("  putIfAbsent(permanent, ...)   = " + r1 + "  (false — key exists)");
            System.out.println("  putIfAbsent(brand-new-key, .) = " + r2 + "   (true — inserted)");

            System.out.println("\n=== Done ===");
        }
    }
}
