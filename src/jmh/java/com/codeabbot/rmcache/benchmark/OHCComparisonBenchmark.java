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

import org.caffinitas.ohc.CacheSerializer;
import org.caffinitas.ohc.OHCache;
import org.caffinitas.ohc.OHCacheBuilder;
import org.openjdk.jmh.annotations.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * OHC (org.caffinitas.ohc) GET/PUT, isolated in its own class so a setup failure
 * (OHC is Unsafe-heavy and last released in 2021) cannot break the main
 * FairComparisonScaleBenchmark trial. Same shape/scales as that benchmark so the
 * numbers are directly comparable.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 1, time = 2)
@Measurement(iterations = 2, time = 3)
@Fork(1)
@Threads(4)
public class OHCComparisonBenchmark {

    @Param({"10000", "100000", "1000000"})
    public int entryCount = 10000;

    @Param("256")
    public int valueSize = 256;

    private OHCache<String, byte[]> ohc;

    static final class StringSer implements CacheSerializer<String> {
        @Override public void serialize(String s, ByteBuffer buf) { buf.put(s.getBytes(StandardCharsets.UTF_8)); }
        @Override public String deserialize(ByteBuffer buf) { byte[] b = new byte[buf.remaining()]; buf.get(b); return new String(b, StandardCharsets.UTF_8); }
        @Override public int serializedSize(String s) { return s.getBytes(StandardCharsets.UTF_8).length; }
    }

    static final class BytesSer implements CacheSerializer<byte[]> {
        @Override public void serialize(byte[] v, ByteBuffer buf) { buf.put(v); }
        @Override public byte[] deserialize(ByteBuffer buf) { byte[] b = new byte[buf.remaining()]; buf.get(b); return b; }
        @Override public int serializedSize(byte[] v) { return v.length; }
    }

    @Setup(Level.Trial)
    public void setup() {
        ohc = OHCacheBuilder.<String, byte[]>newBuilder()
                .keySerializer(new StringSer())
                .valueSerializer(new BytesSer())
                .capacity(8L << 30)
                .build();
        byte[] value = new byte[valueSize];
        for (int i = 0; i < entryCount; i++) {
            ohc.put("key-" + i, value);
        }
        System.out.println("OHC setup complete for " + entryCount + " entries.");
    }

    @TearDown(Level.Trial)
    public void tearDown() throws IOException {
        if (ohc != null) {
            ohc.close();
        }
    }

    @Benchmark
    public byte[] ohcGet() {
        int idx = ThreadLocalRandom.current().nextInt(entryCount);
        return ohc.get("key-" + idx);
    }

    @Benchmark
    public void ohcPut() {
        int idx = ThreadLocalRandom.current().nextInt(entryCount);
        ohc.put("key-" + idx, new byte[valueSize]);
    }
}
