# Custom Serialization

RMCache stores keys and values as raw bytes in native memory. Serializers convert between your types and bytes. Choosing the right serializer determines both correctness and performance.

---

## Built-In Serializers

```java
import com.codeabbot.rmcache.serializer.BuiltInSerializers;

// Key serializers
BuiltInSerializers.STRING_KEY          // UTF-8 string key (default)
BuiltInSerializers.STRING_KEY_LATIN1   // Latin-1 string key (faster for ASCII)
BuiltInSerializers.STRING_KEY_UTF8     // explicit UTF-8

// Value serializers
BuiltInSerializers.byteArray()         // byte[] passthrough (zero-copy)
BuiltInSerializers.string()            // UTF-8 string value
```

---

## Custom Value Serializer

Implement `ValueSerializer<V>` when your type needs custom byte layout:

```java
import com.codeabbot.rmcache.serializer.ValueSerializer;

public class UserSerializer implements ValueSerializer<User> {

    @Override
    public byte[] serialize(User user) {
        ByteBuffer buf = ByteBuffer.allocate(256);
        buf.putInt(user.id());
        byte[] nameBytes = user.name().getBytes(StandardCharsets.UTF_8);
        buf.putInt(nameBytes.length);
        buf.put(nameBytes);
        return Arrays.copyOf(buf.array(), buf.position());
    }

    @Override
    public User deserialize(byte[] bytes) {
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        int id = buf.getInt();
        int nameLen = buf.getInt();
        byte[] nameBytes = new byte[nameLen];
        buf.get(nameBytes);
        return new User(id, new String(nameBytes, StandardCharsets.UTF_8));
    }
}

// Use it
OffHeapCache<String, User> cache = new CacheBuilder<String, User>()
        .keySerializer(BuiltInSerializers.STRING_KEY)
        .valueSerializer(new UserSerializer())
        .build();
```

---

## Custom Key Serializer

Implement `KeySerializer<K>` for non-String keys:

```java
import com.codeabbot.rmcache.serializer.KeySerializer;

public class LongKeySerializer implements KeySerializer<Long> {

    @Override
    public byte[] serialize(Long key) {
        return ByteBuffer.allocate(8).putLong(key).array();
    }

    @Override
    public Long deserialize(byte[] bytes) {
        return ByteBuffer.wrap(bytes).getLong();
    }
}

OffHeapCache<Long, byte[]> cache = new CacheBuilder<Long, byte[]>()
        .keySerializer(new LongKeySerializer())
        .valueSerializer(BuiltInSerializers.byteArray())
        .build();
```

---

## Segment Value Serializer — Zero-Copy Writes

`SegmentValueSerializer` writes directly into the pre-allocated native slab block, eliminating the intermediate `byte[]` on `put()`. This is the highest-performance option for large values.

> **Trusted extension point.** For performance, `serializeTo` receives an *unbounded* native destination and runs with no per-write bounds check. Your implementation **must not write more than `maxLen` bytes** (and must return the exact count) — writing past it corrupts adjacent off-heap memory and can crash the JVM. While developing or running untrusted serializers, enable `CacheBuilder.strictSegmentSerializerBounds(true)`: custom serializers then write through a `maxLen`-bounded slice, so an over-write throws `IndexOutOfBoundsException` instead of silently corrupting memory. Built-in serializers are correct by construction and unaffected.

```java
import com.codeabbot.rmcache.serializer.SegmentValueSerializer;
import com.codeabbot.rmcache.serializer.SerializerHelper;

SegmentValueSerializer<User> serializer = SerializerHelper.segment(
    // 1. Size estimator — must be >= actual serialized size
    user -> 8 + user.name().length() * 2,

    // 2. Write to native memory segment — no byte[] created
    (user, segment, offset, maxLen) -> {
        segment.set(ValueLayout.JAVA_INT, offset, user.id());
        byte[] nameBytes = user.name().getBytes(StandardCharsets.UTF_8);
        segment.set(ValueLayout.JAVA_INT, offset + 4, nameBytes.length);
        MemorySegment.copy(nameBytes, 0, segment, ValueLayout.JAVA_BYTE, offset + 8, nameBytes.length);
        return 8 + nameBytes.length;  // actual bytes written
    },

    // 3. Deserialize from byte[] (for get() return value)
    (bytes, off, len) -> {
        int id = ByteBuffer.wrap(bytes, off, 4).getInt();
        int nameLen = ByteBuffer.wrap(bytes, off + 4, 4).getInt();
        String name = new String(bytes, off + 8, nameLen, StandardCharsets.UTF_8);
        return new User(id, name);
    }
);

OffHeapCache<String, User> cache = new CacheBuilder<String, User>()
        .keySerializer(BuiltInSerializers.STRING_KEY)
        .valueSerializer(serializer)
        .build();
```

> **Size estimator:** If the size estimate is too small, the write may be truncated or throw `IndexOutOfBoundsException`. Over-estimate slightly (10-20% margin). The slab allocator rounds up to the next size class anyway.

---

## Latin-1 Key Encoding (Fastest for ASCII Keys)

For ASCII or Latin-1 string keys, enable the fast encoding path:

```java
import com.codeabbot.rmcache.StringEncoding;

OffHeapCache<String, byte[]> cache = new CacheBuilder<String, byte[]>()
        .stringKeyEncoding(StringEncoding.LATIN1)  // or use STRING_KEY_LATIN1 directly
        .valueSerializer(BuiltInSerializers.byteArray())
        .build();
```

Latin-1 encoding uses `ThreadLocalKeyBuffer` for zero-allocation key serialization. The buffer grows once (up to 256 KB) and is reused across calls.

> If keys contain non-Latin-1 characters (code points > 255), use `StringEncoding.UTF8`. Feeding non-Latin-1 keys to `LATIN1` encoding produces incorrect results silently.

---

## Serialization Best Practices

| Practice | Why |
|----------|-----|
| Pre-size `ByteBuffer` correctly | Avoids buffer copies during serialization |
| Use fixed-size layouts where possible | Predictable size estimation, easier zero-copy |
| For large values, use `SegmentValueSerializer` | Eliminates intermediate `byte[]` |
| For ASCII keys, use `LATIN1` encoding | ~2× faster key encoding, zero allocation |
| Keep serializers stateless | Thread-safe by default; no synchronization needed |
| Avoid calling `cache.get()` inside a serializer | Deadlock risk if the same key is locked |

---

## Framework Integration

### Jackson (JSON)

```java
import com.fasterxml.jackson.databind.ObjectMapper;

ObjectMapper mapper = new ObjectMapper();

ValueSerializer<MyType> serializer = new ValueSerializer<>() {
    public byte[] serialize(MyType v) throws IOException {
        return mapper.writeValueAsBytes(v);
    }
    public MyType deserialize(byte[] b) throws IOException {
        return mapper.readValue(b, MyType.class);
    }
};
```

### Protobuf

```java
ValueSerializer<MyProto.Message> serializer = new ValueSerializer<>() {
    public byte[] serialize(MyProto.Message v) { return v.toByteArray(); }
    public MyProto.Message deserialize(byte[] b) throws InvalidProtocolBufferException {
        return MyProto.Message.parseFrom(b);
    }
};
```

### Kryo

```java
// Kryo is not thread-safe — use ThreadLocal
ThreadLocal<Kryo> kryoLocal = ThreadLocal.withInitial(() -> {
    Kryo k = new Kryo(); k.register(MyType.class); return k;
});

ValueSerializer<MyType> serializer = new ValueSerializer<>() {
    public byte[] serialize(MyType v) {
        Output out = new Output(256, -1);
        kryoLocal.get().writeObject(out, v);
        return out.toBytes();
    }
    public MyType deserialize(byte[] b) {
        return kryoLocal.get().readObject(new Input(b), MyType.class);
    }
};
```
