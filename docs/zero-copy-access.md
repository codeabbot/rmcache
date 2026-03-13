# Zero-Copy Access in RMCache

RMCache stores all values in native memory. Normally, `get()` copies the bytes into a new `byte[]` before returning. Zero-copy APIs eliminate that allocation, letting you access value data directly from native memory.

---

## When to Use Zero-Copy

Use zero-copy when:
- Values are large (>1 KB) and you don't need the full byte array
- You're doing structural reads (e.g., reading specific fields at known offsets)
- You're on a high-throughput hot path and need to eliminate allocations

Use regular `get()` when:
- You need the byte array for passing to another API
- The value is small (< a few hundred bytes) — the allocation cost is negligible
- You need the value to outlive the current stack frame

---

## API 1: `getZeroCopy(key, processor)`

Passes a `MemorySegment` pointing directly into native memory to a lambda. The result is computed in-place and returned — no intermediate `byte[]` created.

```java
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

// Read a UTF-8 string without allocating a byte[]
String result = cache.getZeroCopy("user:42", segment -> {
    if (segment == null) return null;
    byte[] bytes = segment.toArray(ValueLayout.JAVA_BYTE);  // one copy, into the string
    return new String(bytes, StandardCharsets.UTF_8);
});

// Parse a fixed-layout binary record at known offsets
int userId = cache.getZeroCopy("session:abc", segment -> {
    return segment.get(ValueLayout.JAVA_INT, 0);  // read int at offset 0 — zero copy
});
```

Returns `null` if the key is not found or has expired.

---

## API 2: `getView(key)`

Returns a `CacheValueView` wrapping the native segment. Use it for structured reads via typed accessors.

```java
try (CacheValueView view = cache.getView("record:99")) {
    if (view != null) {
        int type   = view.getInt(0);    // 4 bytes at offset 0
        long ts    = view.getLong(4);   // 8 bytes at offset 4
        byte flags = view.getByte(12);  // 1 byte at offset 12
    }
}
// view.close() is called automatically by try-with-resources
```

`CacheValueView` accessors throw `IndexOutOfBoundsException` for out-of-bounds access.

---

## Safety Constraints — Read This First

Both APIs expose a `MemorySegment` that points directly into live native memory. This creates a **TOCTOU (time-of-check to time-of-use) hazard**:

> If another thread evicts or updates the same entry while the processor or view is active, the segment may reference freed or reallocated memory.

### Safe usage rules

1. **Do not store the `MemorySegment` reference.** Use it only within the lambda (for `getZeroCopy`) or within the try-with-resources block (for `getView`).

2. **Do not pass the segment to another thread.** The processor executes synchronously; the segment is only valid during that call.

3. **Read only.** Do not write through the segment — writes to the underlying slab block are not coordinated with the cache.

4. **Close `CacheValueView` promptly.** Call `close()` or use try-with-resources. A `CacheValueView` does not pin the entry; eviction can occur at any point.

### When TOCTOU is acceptable

In many applications, a torn read (getting partial stale data) during a concurrent eviction is harmless:
- The key will miss on the next `get()`, triggering a reload
- The partially-read data is discarded because the processor returns null or a sentinel

In these cases, zero-copy is safe without external synchronization.

### When TOCTOU is not acceptable

If reading partial stale data causes incorrect application behavior (e.g., financial calculations, consensus protocols), either:
- Use the standard `get()` which returns a defensive copy
- Use external per-key read/write synchronization (e.g., `Striped<ReadWriteLock>`)
- Wait for a future slot-pinning API (planned; see ARCHITECTURE.md known limits)

---

## Segment Value Serializer — Zero-Copy Writes

To avoid an intermediate `byte[]` on `put()`, implement `SegmentValueSerializer`:

```java
import com.codeabbot.rmcache.serializer.SegmentValueSerializer;
import com.codeabbot.rmcache.serializer.SerializerHelper;

SegmentValueSerializer<MyRecord> serializer = SerializerHelper.segment(
    record -> record.serializedSize(),                          // size estimate
    (record, segment, offset, maxLen) -> record.writeTo(segment, offset, maxLen),  // write
    (bytes, off, len) -> MyRecord.from(bytes, off, len)         // read
);

OffHeapCache<String, MyRecord> cache = new CacheBuilder<String, MyRecord>()
        .valueSerializer(serializer)
        .build();

cache.put("key", myRecord);   // writes directly into native slab — no intermediate byte[]
MyRecord r = cache.get("key"); // still allocates MyRecord object, but no intermediate byte[]
```

The `writeTo` callback receives a `MemorySegment` pointing to the pre-allocated slab block. Write your record layout directly into it.

---

## Combining Zero-Copy Read + Write

For maximum throughput (no intermediate allocations):

```java
// Write directly into native memory
SegmentValueSerializer<MyRecord> serializer = SerializerHelper.segment(
    r -> MyRecord.FIXED_SIZE,
    (r, seg, off, max) -> r.encodeTo(seg, off),
    (bytes, off, len) -> MyRecord.decode(bytes, off));

OffHeapCache<String, MyRecord> cache = new CacheBuilder<String, MyRecord>()
        .valueSerializer(serializer)
        .build();

// Read directly from native memory — no byte[] anywhere
int field = cache.getZeroCopy("key", seg -> seg.get(ValueLayout.JAVA_INT, MyRecord.FIELD_OFFSET));
```

---

## Performance

| Access Pattern | Allocation | Notes |
|----------------|-----------|-------|
| `get()` | `byte[]` + deserialized object | Safe, GC pressure |
| `getZeroCopy()` | deserialized result only | No intermediate `byte[]` |
| `getView()` | `CacheValueView` wrapper (~32B) | Near-zero; wrapper is tiny |
| `getZeroCopy` with struct read | 0 | If result is a primitive |
