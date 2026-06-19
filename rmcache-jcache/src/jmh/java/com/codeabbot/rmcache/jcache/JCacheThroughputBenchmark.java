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
package com.codeabbot.rmcache.jcache;

import org.openjdk.jmh.annotations.*;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Throughput (ops/sec, higher is better) of RMCache accessed directly ({@code backend=direct})
 * vs. through the JSR-107 wrapper ({@code backend=jcache}), across 10K–10M entries. The off-heap
 * core is a separate module from rmcache-jcache, so {@code direct} reproduces main's numbers and
 * the direct↔jcache gap is purely the adapter tax.
 *
 * <p>Run: {@code ./gradlew :rmcache-jcache:jmh -Pjmh.includes="JCacheThroughputBenchmark"}
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(1)
@Threads(4)
public class JCacheThroughputBenchmark {

    @Param({"direct", "jcache"})
    public String backend;

    @Param({"10000", "100000", "1000000", "10000000"})
    public int entryCount;

    @Param("256")
    public int valueSize;

    private Backends.Backend impl;

    @Setup(Level.Trial)
    public void setup() {
        impl = Backends.create(backend, entryCount, valueSize);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (impl != null) {
            impl.close();
        }
    }

    @Benchmark
    public byte[] get() {
        return impl.get("key-" + ThreadLocalRandom.current().nextInt(entryCount));
    }

    @Benchmark
    public void put() {
        impl.put("key-" + ThreadLocalRandom.current().nextInt(entryCount), new byte[valueSize]);
    }
}
