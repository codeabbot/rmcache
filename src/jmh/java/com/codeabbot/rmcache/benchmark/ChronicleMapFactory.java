package com.codeabbot.rmcache.benchmark;

import net.openhft.chronicle.map.ChronicleMap;

import java.io.IOException;

/**
 * Factory for ChronicleMap off-heap instances used in benchmarks.
 *
 * <p>ChronicleMap stores data in native memory via memory-mapped regions.
 * {@code create()} (without a file argument) uses an anonymous in-memory
 * mapping — no disk I/O, fully off-heap.
 *
 * <p>The {@code averageKey} and {@code averageValueSize} hints must reflect
 * actual benchmark key/value sizes; under-sizing them causes internal
 * reallocation overhead during the benchmark warm-up.
 */
public final class ChronicleMapFactory {

    private ChronicleMapFactory() {
    }

    /**
     * Creates an in-memory ChronicleMap sized for the given entry count and value size.
     *
     * @param entryCount  expected number of entries (used for initial allocation)
     * @param valueSize   average value size in bytes
     * @return a ready-to-use ChronicleMap
     */
    public static ChronicleMap<String, byte[]> createMap(int entryCount, int valueSize) throws IOException {
        // Sample key matching the benchmark pattern "key-NNNNN" — length drives allocation sizing
        int digits = String.valueOf(entryCount - 1).length();
        String sampleKey = "key-" + "0".repeat(digits);

        return ChronicleMap
                .of(String.class, byte[].class)
                .name("benchmark-chronicle")
                .averageKey(sampleKey)
                .averageValueSize(valueSize)
                .entries(entryCount)
                .create();
    }
}
