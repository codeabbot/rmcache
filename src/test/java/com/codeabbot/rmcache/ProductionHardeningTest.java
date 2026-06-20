package com.codeabbot.rmcache;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.codeabbot.rmcache.eviction.NoEvictionPolicy;
import com.codeabbot.rmcache.serializer.BuiltInSerializers;
import com.codeabbot.rmcache.serializer.SegmentValueSerializer;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for the 0.0.2 production-hardening fixes:
 * <ul>
 *   <li>#1 — a put that cannot be allocated is counted as a rejection (not a
 *       silent success), the {@code puts} counter stays honest, and no exception
 *       is thrown (lossy-cache semantics).</li>
 *   <li>#2 — explicit {@code HEAP} ghost mode never serves a stale value after an
 *       in-place update.</li>
 *   <li>#9 — a misbehaving custom {@link SegmentValueSerializer} that writes past
 *       {@code maxLen} throws (bounded slice) instead of corrupting memory.</li>
 * </ul>
 */
public class ProductionHardeningTest {

    // ── #1: failed put is observable, not silent; no throw ───────────────────
    @Test
    void put_underMemoryPressure_isRejectedNotSilentlyDropped() {
        try (OffHeapCache<String, byte[]> cache = new CacheBuilder<String, byte[]>()
                .maxEntries(500)
                .averageValueSize(256)
                .offHeapMemory(1L << 20) // 1 MB — far too small for 500 × 8 KB
                .keySerializer(BuiltInSerializers.STRING_KEY)
                .valueSerializer(BuiltInSerializers.byteArray())
                .eviction(new NoEvictionPolicy()) // nothing is evicted → allocations will fail
                .build()) {

            byte[] big = new byte[8192];
            int attempts = 500;
            // Must NOT throw — a bounded cache is allowed to decline entries.
            assertDoesNotThrow(() -> {
                for (int i = 0; i < attempts; i++) {
                    cache.put("k-" + i, big);
                }
            });

            OffHeapCache.CacheStats s = cache.getStats();
            assertTrue(s.rejectedPuts() > 0, "expected some rejected puts under memory pressure");
            assertTrue(s.puts() > 0, "expected some successful puts");
            // puts counter is honest: every attempt is either a success or a rejection.
            assertEquals(attempts, s.puts() + s.rejectedPuts(),
                    "puts + rejectedPuts must equal total attempts");
        }
    }

    // ── #2: HEAP ghost must not serve a stale value after update ─────────────
    @Test
    void heapGhost_returnsFreshValueAfterUpdate() {
        try (OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1000)
                .offHeapMemory(8L << 20)
                .ghostCacheMode(GhostCacheMode.HEAP) // explicit opt-in HEAP L1
                .keySerializer(BuiltInSerializers.STRING_KEY)
                .valueSerializer(BuiltInSerializers.string())
                .build()) {

            cache.put("user:1", "v1");
            assertEquals("v1", cache.get("user:1")); // warms the HEAP ghost with v1
            cache.put("user:1", "v2"); // in-place update
            assertEquals("v2", cache.get("user:1"), "HEAP ghost must not serve the stale pre-update value");
        }
    }

    // ── #9: custom segment serializer cannot overrun its bound ───────────────
    @Test
    void customSegmentSerializer_writingPastMaxLen_throwsInsteadOfCorrupting() {
        // Misbehaving serializer: claims estimateSize() bytes but writes more.
        SegmentValueSerializer<byte[]> overrunning = new SegmentValueSerializer<>() {
            @Override
            public int estimateSize(byte[] value) {
                return value.length;
            }

            @Override
            public int serializeTo(byte[] value, MemorySegment dest, long offset, int maxLen) {
                // BUG on purpose: write maxLen + 64 bytes (past the allocated region).
                for (int i = 0; i < maxLen + 64; i++) {
                    dest.set(ValueLayout.JAVA_BYTE, offset + i, (byte) 1);
                }
                return maxLen + 64;
            }

            @Override
            public int serializeTo(byte[] value, byte[] dest, int offset) {
                System.arraycopy(value, 0, dest, offset, value.length);
                return value.length;
            }

            @Override
            public byte[] deserializeFrom(byte[] src, int offset, int length) {
                byte[] out = new byte[length];
                System.arraycopy(src, offset, out, 0, length);
                return out;
            }

            @Override
            public byte[] serialize(byte[] value) {
                return value;
            }

            @Override
            public byte[] deserialize(byte[] bytes) {
                return bytes;
            }
        };

        try (OffHeapCache<String, byte[]> cache = new CacheBuilder<String, byte[]>()
                .maxEntries(1000)
                .offHeapMemory(8L << 20)
                .keySerializer(BuiltInSerializers.STRING_KEY)
                .valueSerializer(overrunning)
                .strictSegmentSerializerBounds(true) // opt into bounds enforcement
                .build()) {

            // In strict mode the bounded slice must reject the over-write rather than
            // silently corrupting the adjacent off-heap entry. (Without strict mode the
            // serializer is trusted/fast and an over-write is the caller's contract violation.)
            assertThrows(IndexOutOfBoundsException.class,
                    () -> cache.put("k", new byte[128]));
        }
    }

    // ── opt #1: no-TTL GET skip must not break per-entry TTL on a plain cache ─
    @Test
    void perEntryTtl_onPlainCache_stillExpires_afterNoTtlSkipOptimization() throws Exception {
        try (OffHeapCache<String, byte[]> cache = new CacheBuilder<String, byte[]>()
                .maxEntries(1000)
                .offHeapMemory(8L << 20)
                .keySerializer(BuiltInSerializers.STRING_KEY)
                .valueSerializer(BuiltInSerializers.byteArray())
                .build()) { // no TTL policy → the GET expiry check starts disabled

            // Non-TTL entry: returned while the cache has never seen a TTL.
            cache.put("permanent", "p".getBytes());
            assertNotNull(cache.get("permanent"));

            // A per-entry TTL must flip the cache into TTL-aware mode so GET honors expiry.
            cache.put("ephemeral", "e".getBytes(), Duration.ofMillis(50));
            assertNotNull(cache.get("ephemeral")); // not yet expired
            Thread.sleep(250); // past TTL + CoarseClock granularity
            assertNull(cache.get("ephemeral"), "per-entry TTL entry must expire even on a plain cache");
            assertNotNull(cache.get("permanent"), "non-TTL entry must remain");
        }
    }
}
