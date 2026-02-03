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
