package com.codeabbot.rmcache.benchmark;

import com.target.nativememoryallocator.allocator.NativeMemoryAllocator;
import com.target.nativememoryallocator.map.NativeMemoryMap;
import com.target.nativememoryallocator.map.NativeMemoryMapBackend;
import com.target.nativememoryallocator.map.NativeMemoryMapBuilder;
import com.target.nativememoryallocator.map.NativeMemoryMapSerializer;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;

public class NMAFactory {
    public static <K, V> NativeMemoryMap<K, V> createMap(
            NativeMemoryMapSerializer<V> serializer,
            NativeMemoryAllocator allocator,
            NativeMemoryMapBackend backend) {

        // Passing arguments matching the constructor signature:
        // serializer, allocator, booleans, int, backend, function
        return new NativeMemoryMapBuilder<K, V>(
                serializer,
                allocator,
                false, // statsEnabled?
                false, // isAsync?
                16, // initialCapacity?
                backend,
                new Function1<Object, Unit>() {
                    @Override
                    public Unit invoke(Object o) {
                        return Unit.INSTANCE;
                    }
                }).build();
    }
}
