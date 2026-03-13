package com.codeabbot.rmcache.examples;

import com.codeabbot.rmcache.CacheBuilder;
import com.codeabbot.rmcache.GhostCacheMode;
import com.codeabbot.rmcache.OffHeapCache;
import com.codeabbot.rmcache.Units;
import com.codeabbot.rmcache.serializer.BuiltInSerializers;
import com.codeabbot.rmcache.serializer.SerializerHelper;
import com.codeabbot.rmcache.serializer.SegmentValueSerializer;
import com.codeabbot.rmcache.serializer.StringEncoding;
import com.codeabbot.rmcache.serializer.ValueSerializer;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * CustomSerializerExample — three serialization approaches for a custom type:
 *
 * <ol>
 *   <li>Standard {@code ValueSerializer<V>} — serialize/deserialize via byte[]</li>
 *   <li>{@code SegmentValueSerializer<V>} — write directly into native memory (zero intermediate byte[])</li>
 *   <li>Latin-1 key encoding — fastest path for ASCII string keys</li>
 * </ol>
 *
 * Run: {@code ./gradlew :examples:runCustomSerializerExample}
 */
public class CustomSerializerExample {

    // ── Custom domain type ────────────────────────────────────────────────────

    record Product(int id, String name, double price, boolean inStock) {
        // Serialized layout: [id:4][nameLen:4][nameBytes:N][price:8][inStock:1]
        int serializedSize() {
            return 4 + 4 + name.getBytes(StandardCharsets.UTF_8).length + 8 + 1;
        }
    }

    // ── Approach 1: Standard ValueSerializer ─────────────────────────────────

    static final ValueSerializer<Product> STANDARD_SERIALIZER = new ValueSerializer<>() {

        @Override
        public byte[] serialize(Product p) {
            byte[] nameBytes = p.name().getBytes(StandardCharsets.UTF_8);
            ByteBuffer buf = ByteBuffer.allocate(4 + 4 + nameBytes.length + 8 + 1);
            buf.putInt(p.id());
            buf.putInt(nameBytes.length);
            buf.put(nameBytes);
            buf.putDouble(p.price());
            buf.put(p.inStock() ? (byte) 1 : (byte) 0);
            return buf.array();
        }

        @Override
        public Product deserialize(byte[] bytes) {
            ByteBuffer buf = ByteBuffer.wrap(bytes);
            int id = buf.getInt();
            int nameLen = buf.getInt();
            byte[] nameBytes = new byte[nameLen];
            buf.get(nameBytes);
            double price = buf.getDouble();
            boolean inStock = buf.get() == 1;
            return new Product(id, new String(nameBytes, StandardCharsets.UTF_8), price, inStock);
        }
    };

    // ── Approach 2: SegmentValueSerializer (zero intermediate byte[]) ─────────

    static final SegmentValueSerializer<Product> SEGMENT_SERIALIZER = SerializerHelper.segment(
            // Size estimator (must be >= actual)
            p -> p.serializedSize(),

            // Write directly into native memory — no byte[] created
            (p, seg, off, maxLen) -> {
                byte[] nameBytes = p.name().getBytes(StandardCharsets.UTF_8);
                long pos = off;
                seg.set(ValueLayout.JAVA_INT, pos, p.id()); pos += 4;
                seg.set(ValueLayout.JAVA_INT, pos, nameBytes.length); pos += 4;
                MemorySegment.copy(nameBytes, 0, seg, ValueLayout.JAVA_BYTE, pos, nameBytes.length);
                pos += nameBytes.length;
                // JAVA_DOUBLE_UNALIGNED: after a variable-length name field the
                // double's position is not guaranteed to be 8-byte aligned.
                seg.set(ValueLayout.JAVA_DOUBLE_UNALIGNED, pos, p.price()); pos += 8;
                seg.set(ValueLayout.JAVA_BYTE, pos, p.inStock() ? (byte) 1 : (byte) 0);
                return p.serializedSize();
            },

            // Deserialize from byte[] (for the get() return value).
            // Native byte order must match the ValueLayout writes above.
            (bytes, off, len) -> {
                ByteBuffer buf = ByteBuffer.wrap(bytes, off, len).order(ByteOrder.nativeOrder());
                int id = buf.getInt();
                int nameLen = buf.getInt();
                byte[] nb = new byte[nameLen];
                buf.get(nb);
                double price = buf.getDouble();
                boolean inStock = buf.get() == 1;
                return new Product(id, new String(nb, StandardCharsets.UTF_8), price, inStock);
            }
    );

    public static void main(String[] args) {
        System.out.println("=== RMCache Custom Serializer Example ===\n");

        Product[] products = {
            new Product(101, "Widget",     9.99,  true),
            new Product(102, "Gadget",    24.50,  true),
            new Product(103, "Doohickey",  4.75, false),
        };

        // ── Approach 1: Standard ValueSerializer ─────────────────────────────
        System.out.println("--- Approach 1: Standard ValueSerializer ---");
        try (OffHeapCache<String, Product> cache = new CacheBuilder<String, Product>()
                .maxEntries(1_000)
                .offHeapMemory(Units.megabytes(32))
                .keySerializer(BuiltInSerializers.STRING_KEY)
                .valueSerializer(STANDARD_SERIALIZER)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .build()) {

            for (Product p : products) {
                cache.put("product:" + p.id(), p);
            }

            for (Product p : products) {
                Product retrieved = cache.get("product:" + p.id());
                System.out.printf("  Stored/Retrieved: %s  →  equals=%b%n", retrieved, p.equals(retrieved));
            }
        }

        // ── Approach 2: SegmentValueSerializer ───────────────────────────────
        System.out.println("\n--- Approach 2: SegmentValueSerializer (zero intermediate byte[]) ---");
        try (OffHeapCache<String, Product> cache = new CacheBuilder<String, Product>()
                .maxEntries(1_000)
                .offHeapMemory(Units.megabytes(32))
                .keySerializer(BuiltInSerializers.STRING_KEY)
                .valueSerializer(SEGMENT_SERIALIZER)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .build()) {

            for (Product p : products) {
                cache.put("product:" + p.id(), p);
            }

            for (Product p : products) {
                Product retrieved = cache.get("product:" + p.id());
                System.out.printf("  Stored/Retrieved: %s  →  equals=%b%n", retrieved, p.equals(retrieved));
            }

            // Latency comparison
            int rounds = 100_000;
            long t0 = System.nanoTime();
            for (int i = 0; i < rounds; i++) {
                cache.put("product:" + products[i % 3].id(), products[i % 3]);
            }
            System.out.printf("  Avg PUT latency (SegmentSerializer): %.0f ns/op%n",
                    (double) (System.nanoTime() - t0) / rounds);
        }

        // ── Approach 3: Latin-1 key encoding ─────────────────────────────────
        System.out.println("\n--- Approach 3: Latin-1 key encoding (fastest for ASCII keys) ---");
        try (OffHeapCache<String, String> cache = new CacheBuilder<String, String>()
                .maxEntries(1_000)
                .offHeapMemory(Units.megabytes(32))
                // LATIN1 uses ThreadLocalKeyBuffer — zero-allocation key encoding
                .stringKeyEncoding(StringEncoding.LATIN1)
                .valueSerializer(BuiltInSerializers.string())
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .build()) {

            for (int i = 0; i < 100; i++) {
                cache.put("item:" + i, "value-" + i);
            }

            int rounds = 200_000;
            long t0 = System.nanoTime();
            for (int i = 0; i < rounds; i++) {
                cache.get("item:" + (i % 100));
            }
            System.out.printf("  Avg GET latency (Latin-1 keys): %.0f ns/op%n",
                    (double) (System.nanoTime() - t0) / rounds);

            System.out.println("\n  Note: Latin-1 encoding uses ThreadLocalKeyBuffer for zero-allocation");
            System.out.println("  key serialization. Ideal for ASCII string keys (user IDs, product codes).");
            System.out.println("  Do NOT use for keys with code points > 255 (use UTF-8 instead).");
        }

        System.out.println("\n=== Done ===");
    }
}
