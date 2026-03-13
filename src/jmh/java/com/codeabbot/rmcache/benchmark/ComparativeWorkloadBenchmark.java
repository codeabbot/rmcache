package com.codeabbot.rmcache.benchmark;

import com.codeabbot.rmcache.CacheBuilder;
import com.codeabbot.rmcache.GhostCacheMode;
import com.codeabbot.rmcache.OffHeapCache;
import com.codeabbot.rmcache.Units;
import com.codeabbot.rmcache.eviction.NoEvictionPolicy;
import com.codeabbot.rmcache.serializer.BuiltInSerializers;
import com.codeabbot.rmcache.serializer.StringEncoding;
import net.openhft.chronicle.map.ChronicleMap;
import org.mapdb.DB;
import org.mapdb.HTreeMap;
import org.openjdk.jmh.annotations.*;

import java.io.IOException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Throughput benchmark for Read-Only, Write-Only, and Mixed (3R:1W) workloads.
 *
 * <p>Competitors: RMCache, MapDB, ChronicleMap.
 * Metric: operations per second (higher is better).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 2)
@Measurement(iterations = 3, time = 3)
@Fork(1)
@Threads(4)
public class ComparativeWorkloadBenchmark {

    @Param({"10000", "100000", "1000000"})
    public int entryCount = 10000;

    @Param("256")
    public int valueSize = 256;

    private OffHeapCache<String, byte[]> rmcache;
    private DB mapDB;
    private HTreeMap<String, byte[]> mapDBMap;
    private ChronicleMap<String, byte[]> chronicleMap;

    private String[] keys;
    private byte[][] values;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        System.out.println("\n--- Setup Comparative Workload for " + entryCount + " entries ---");

        keys = new String[entryCount];
        for (int i = 0; i < entryCount; i++) {
            keys[i] = "key-" + i;
        }
        values = new byte[1024][valueSize];
        for (int i = 0; i < 1024; i++) {
            values[i] = new byte[valueSize];
        }

        // ── RMCache ───────────────────────────────────────────────────────────
        rmcache = new CacheBuilder<String, byte[]>()
                .offHeapMemory(Units.gigabytes(8))
                .maxEntries(entryCount * 2)
                .stringKeyEncoding(StringEncoding.LATIN1)
                .valueSerializer(BuiltInSerializers.byteArray())
                .eviction(new NoEvictionPolicy())
                .hashTableLoadFactor(0.5d)
                .ghostCacheMode(GhostCacheMode.DISABLED)
                .ghostCacheSize(0)
                .build();

        // ── MapDB ─────────────────────────────────────────────────────────────
        mapDB = MapDBFactory.createDB();
        mapDBMap = MapDBFactory.createMap(mapDB);

        // ── ChronicleMap ──────────────────────────────────────────────────────
        chronicleMap = ChronicleMapFactory.createMap(entryCount * 2, valueSize);

        // ── Pre-populate ──────────────────────────────────────────────────────
        for (int i = 0; i < entryCount; i++) {
            byte[] v = values[i % 1024];
            rmcache.put(keys[i], v);
            mapDBMap.put(keys[i], v);
            chronicleMap.put(keys[i], v);
        }
        System.out.println("--- Setup Complete ---\n");
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (rmcache != null) rmcache.close();
        if (mapDB != null) mapDB.close();
        if (chronicleMap != null) chronicleMap.close();
    }

    // ── RMCache Workloads ─────────────────────────────────────────────────────

    @Benchmark
    public byte[] rmcache_ReadOnly() {
        int idx = ThreadLocalRandom.current().nextInt(entryCount);
        return rmcache.get(keys[idx]);
    }

    @Benchmark
    public void rmcache_WriteOnly() {
        int idx = ThreadLocalRandom.current().nextInt(entryCount);
        rmcache.put(keys[idx], values[idx % 1024]);
    }

    @Group("rmcacheMixed")
    @GroupThreads(3)
    @Benchmark
    public byte[] rmcache_Mixed_Read() {
        int idx = ThreadLocalRandom.current().nextInt(entryCount);
        return rmcache.get(keys[idx]);
    }

    @Group("rmcacheMixed")
    @GroupThreads(1)
    @Benchmark
    public void rmcache_Mixed_Write() {
        int idx = ThreadLocalRandom.current().nextInt(entryCount);
        rmcache.put(keys[idx], values[idx % 1024]);
    }

    // ── MapDB Workloads ───────────────────────────────────────────────────────

    @Benchmark
    public byte[] mapdb_ReadOnly() {
        int idx = ThreadLocalRandom.current().nextInt(entryCount);
        return mapDBMap.get(keys[idx]);
    }

    @Benchmark
    public void mapdb_WriteOnly() {
        int idx = ThreadLocalRandom.current().nextInt(entryCount);
        mapDBMap.put(keys[idx], values[idx % 1024]);
    }

    @Group("mapdbMixed")
    @GroupThreads(3)
    @Benchmark
    public byte[] mapdb_Mixed_Read() {
        int idx = ThreadLocalRandom.current().nextInt(entryCount);
        return mapDBMap.get(keys[idx]);
    }

    @Group("mapdbMixed")
    @GroupThreads(1)
    @Benchmark
    public void mapdb_Mixed_Write() {
        int idx = ThreadLocalRandom.current().nextInt(entryCount);
        mapDBMap.put(keys[idx], values[idx % 1024]);
    }

    // ── ChronicleMap Workloads ────────────────────────────────────────────────

    @Benchmark
    public byte[] chronicle_ReadOnly() {
        int idx = ThreadLocalRandom.current().nextInt(entryCount);
        return chronicleMap.get(keys[idx]);
    }

    @Benchmark
    public void chronicle_WriteOnly() {
        int idx = ThreadLocalRandom.current().nextInt(entryCount);
        chronicleMap.put(keys[idx], values[idx % 1024]);
    }

    @Group("chronicleMixed")
    @GroupThreads(3)
    @Benchmark
    public byte[] chronicle_Mixed_Read() {
        int idx = ThreadLocalRandom.current().nextInt(entryCount);
        return chronicleMap.get(keys[idx]);
    }

    @Group("chronicleMixed")
    @GroupThreads(1)
    @Benchmark
    public void chronicle_Mixed_Write() {
        int idx = ThreadLocalRandom.current().nextInt(entryCount);
        chronicleMap.put(keys[idx], values[idx % 1024]);
    }
}
