/*
 * Copyright 2026 Rabindra Meher
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.codeabbot.rmcache.benchmark;

import com.codeabbot.rmcache.CacheBuilder;
import com.codeabbot.rmcache.GhostCacheMode;
import com.codeabbot.rmcache.OffHeapCache;
import com.codeabbot.rmcache.Units;
import com.codeabbot.rmcache.eviction.NoEvictionPolicy;
import com.codeabbot.rmcache.serializer.BuiltInSerializers;
import com.codeabbot.rmcache.serializer.StreamingSerializer;
import com.codeabbot.rmcache.serializer.StringEncoding;
import org.openjdk.jmh.annotations.*;

import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Exercises the StreamingSerializer code path (the per-thread scratch buffer +
 * deserializeFrom), which is exactly where the lazy-ctx change applies. Uses a
 * StreamingSerializer that is intentionally NOT a SegmentValueSerializer, so the
 * cache routes through cachedStreamSer (not cachedSegSer).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 1, time = 2)
@Measurement(iterations = 2, time = 3)
@Fork(1)
@Threads(4)
public class StreamingPathBenchmark {

    @Param({"10000", "100000", "1000000"})
    public int entryCount = 10000;

    @Param("256")
    public int valueSize = 256;

    private OffHeapCache<String, byte[]> cache;

    /** StreamingSerializer (NOT a SegmentValueSerializer) over raw byte[] values. */
    static final class StreamingByteSerializer implements StreamingSerializer<byte[]> {
        @Override
        public int estimateSize(byte[] value) {
            return value.length;
        }

        @Override
        public int serializeTo(byte[] value, byte[] dest, int offset) {
            System.arraycopy(value, 0, dest, offset, value.length);
            return value.length;
        }

        @Override
        public byte[] deserializeFrom(byte[] src, int offset, int length) {
            return Arrays.copyOfRange(src, offset, offset + length);
        }

        @Override
        public byte[] serialize(byte[] value) {
            return value;
        }

        @Override
        public byte[] deserialize(byte[] bytes) {
            return bytes;
        }
    }

    @Setup(Level.Trial)
    public void setup() {
        cache = new CacheBuilder<String, byte[]>()
                .offHeapMemory(Units.gigabytes(8))
                .maxEntries(entryCount * 2)
                .stringKeyEncoding(StringEncoding.LATIN1)
                .keySerializer(BuiltInSerializers.STRING_KEY_LATIN1)
                .valueSerializer(new StreamingByteSerializer())
                .eviction(new NoEvictionPolicy())
                .hashTableLoadFactor(0.5d)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .ghostCacheSize(0)
                .build();
        byte[] value = new byte[valueSize];
        for (int i = 0; i < entryCount; i++) {
            cache.put("key-" + i, value);
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (cache != null) {
            cache.close();
        }
    }

    @Benchmark
    public byte[] streamingGet() {
        int idx = ThreadLocalRandom.current().nextInt(entryCount);
        return cache.get("key-" + idx);
    }

    @Benchmark
    public void streamingPut() {
        int idx = ThreadLocalRandom.current().nextInt(entryCount);
        cache.put("key-" + idx, new byte[valueSize]);
    }
}
