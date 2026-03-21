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

import com.codeabbot.rmcache.memory.SlabAllocator;
import com.codeabbot.rmcache.memory.AllocationHandle;
import com.codeabbot.rmcache.Units;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

/**
 * JMH benchmarks for SlabAllocator performance.
 */
@State(Scope.Benchmark)
@BenchmarkMode({ Mode.Throughput, Mode.AverageTime })
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 2, time = 2)
@Measurement(iterations = 3, time = 3)
@Fork(1)
public class AllocatorBenchmark {

    @Param({ "64", "1024", "8192" })
    public int blockSize = 1024;

    private SlabAllocator allocator;

    @Setup(Level.Trial)
    public void setup() {
        allocator = new SlabAllocator(Units.megabytes(256));
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        allocator.close();
    }

    @Benchmark
    public void allocateFree() {
        AllocationHandle handle = allocator.allocate(blockSize);
        allocator.free(handle);
    }

    // Removed allocateOnly as it inevitably OOMs a fixed allocator in a tight loop
}
