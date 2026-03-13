package com.codeabbot.rmcache.examples;

import com.codeabbot.rmcache.CacheBuilder;
import com.codeabbot.rmcache.CacheValueView;
import com.codeabbot.rmcache.GhostCacheMode;
import com.codeabbot.rmcache.OffHeapCache;
import com.codeabbot.rmcache.Units;
import com.codeabbot.rmcache.serializer.BuiltInSerializers;
import com.codeabbot.rmcache.serializer.SerializerHelper;
import com.codeabbot.rmcache.serializer.SegmentValueSerializer;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * ZeroCopyExample — demonstrates {@code getZeroCopy} and {@code getView}
 * for large value reads without intermediate heap allocation.
 *
 * <p>This example stores a fixed-layout binary record and reads individual
 * fields directly from native memory without copying the full value.
 *
 * Run: {@code ./gradlew :examples:runZeroCopyExample}
 */
public class ZeroCopyExample {

    /**
     * Fixed-layout binary record stored in native memory:
     * [id:4B][timestamp:8B][score:8B][flags:1B] = 21 bytes
     */
    record Metric(int id, long timestamp, double score, byte flags) {
        static final int SIZE = Integer.BYTES + Long.BYTES + Double.BYTES + Byte.BYTES; // 21 bytes

        byte[] toBytes() {
            ByteBuffer buf = ByteBuffer.allocate(SIZE);
            buf.putInt(id);
            buf.putLong(timestamp);
            buf.putDouble(score);
            buf.put(flags);
            return buf.array();
        }

        static Metric fromBytes(byte[] b, int off, int len) {
            ByteBuffer buf = ByteBuffer.wrap(b, off, len);
            return new Metric(buf.getInt(), buf.getLong(), buf.getDouble(), buf.get());
        }

        static final int OFFSET_ID        = 0;
        static final int OFFSET_TIMESTAMP = Integer.BYTES;
        static final int OFFSET_SCORE     = Integer.BYTES + Long.BYTES;
        static final int OFFSET_FLAGS     = Integer.BYTES + Long.BYTES + Double.BYTES;
    }

    public static void main(String[] args) {
        System.out.println("=== RMCache Zero-Copy Example ===\n");

        // ── Build cache with SegmentValueSerializer (zero-copy writes) ────────
        SegmentValueSerializer<Metric> serializer = SerializerHelper.segment(
                m -> Metric.SIZE,
                (m, seg, off, max) -> {
                    seg.set(ValueLayout.JAVA_INT,    off + Metric.OFFSET_ID,        m.id());
                    seg.set(ValueLayout.JAVA_LONG,   off + Metric.OFFSET_TIMESTAMP, m.timestamp());
                    seg.set(ValueLayout.JAVA_DOUBLE, off + Metric.OFFSET_SCORE,     m.score());
                    seg.set(ValueLayout.JAVA_BYTE,   off + Metric.OFFSET_FLAGS,     m.flags());
                    return Metric.SIZE;
                },
                Metric::fromBytes
        );

        try (OffHeapCache<String, Metric> cache = new CacheBuilder<String, Metric>()
                .maxEntries(10_000)
                .offHeapMemory(Units.megabytes(64))
                .keySerializer(BuiltInSerializers.STRING_KEY)
                .valueSerializer(serializer)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .build()) {

            // ── Insert records ────────────────────────────────────────────────
            for (int i = 1; i <= 5; i++) {
                cache.put("metric:" + i, new Metric(i, System.currentTimeMillis(), i * 3.14, (byte) i));
            }
            System.out.println("Inserted 5 Metric records via SegmentValueSerializer (writes directly to native memory)\n");

            // ── Standard get (copies value to heap) ───────────────────────────
            Metric m = cache.get("metric:1");
            System.out.println("Standard get(metric:1) → " + m + "  (allocates Metric on heap)");

            // ── getZeroCopy: read a single field without copying the record ───
            System.out.println("\n--- getZeroCopy: read individual fields from native memory ---");

            int id = cache.getZeroCopy("metric:2", seg ->
                    seg.get(ValueLayout.JAVA_INT, Metric.OFFSET_ID));
            System.out.println("  id field only    = " + id + "  (4-byte read, no full record copy)");

            double score = cache.getZeroCopy("metric:3", seg ->
                    seg.get(ValueLayout.JAVA_DOUBLE, Metric.OFFSET_SCORE));
            System.out.printf("  score field only = %.2f%n", score);

            long ts = cache.getZeroCopy("metric:4", seg ->
                    seg.get(ValueLayout.JAVA_LONG, Metric.OFFSET_TIMESTAMP));
            System.out.println("  timestamp only   = " + ts);

            // ── getView: typed field accessor ─────────────────────────────────
            System.out.println("\n--- getView: CacheValueView typed accessor ---");
            try (CacheValueView view = cache.getView("metric:5")) {
                if (view != null) {
                    int    viewId    = view.getInt(Metric.OFFSET_ID);
                    long   viewTs    = view.getLong(Metric.OFFSET_TIMESTAMP);
                    // note: getView exposes getInt/getLong/getByte — no getDouble;
                    // read the raw long bits and convert
                    long   scoreBits = view.getLong(Metric.OFFSET_SCORE);
                    double viewScore = Double.longBitsToDouble(scoreBits);
                    byte   viewFlags = view.getByte(Metric.OFFSET_FLAGS);
                    System.out.println("  view → id=" + viewId + " ts=" + viewTs
                            + " score=" + String.format("%.2f", viewScore) + " flags=" + viewFlags);
                }
            }
            // view is closed here; underlying native memory is NOT freed — only the view wrapper is released

            // ── getZeroCopy on missing key ────────────────────────────────────
            System.out.println("\n--- Missing key ---");
            Integer nullResult = cache.getZeroCopy("metric:missing", seg -> 42);
            System.out.println("  getZeroCopy(metric:missing) = " + nullResult + "  (null — not found)");

            // ── Latency comparison ────────────────────────────────────────────
            System.out.println("\n--- Latency comparison: get vs getZeroCopy ---");
            int rounds = 200_000;

            long t0 = System.nanoTime();
            for (int i = 0; i < rounds; i++) {
                Metric ignored = cache.get("metric:" + (i % 5 + 1));
            }
            long stdGet = System.nanoTime() - t0;

            t0 = System.nanoTime();
            for (int i = 0; i < rounds; i++) {
                int ignored = cache.getZeroCopy("metric:" + (i % 5 + 1),
                        seg -> seg.get(ValueLayout.JAVA_INT, Metric.OFFSET_ID));
            }
            long zcGet = System.nanoTime() - t0;

            System.out.printf("  Avg standard get:     %.0f ns/op%n", (double) stdGet / rounds);
            System.out.printf("  Avg getZeroCopy:      %.0f ns/op  (reads 4B, skips full deserialization)%n", (double) zcGet / rounds);

            System.out.println("\n=== Done ===");
        }
    }
}
