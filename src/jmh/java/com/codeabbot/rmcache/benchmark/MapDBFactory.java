package com.codeabbot.rmcache.benchmark;

import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.HTreeMap;
import org.mapdb.Serializer;

/**
 * Factory for MapDB off-heap HTreeMap instances used in benchmarks.
 *
 * <p>MapDB 3.x stores data in Java direct memory (off-heap ByteBuffers) when
 * created with {@code memoryDB()}. The {@code concurrencyScale} controls the
 * number of internal segments for concurrent access.
 */
public final class MapDBFactory {

    private MapDBFactory() {
    }

    public static DB createDB() {
        return DBMaker.memoryDB()
                .concurrencyScale(16)
                .make();
    }

    public static HTreeMap<String, byte[]> createMap(DB db) {
        return db.hashMap("cache", Serializer.STRING, Serializer.BYTE_ARRAY)
                .create();
    }
}
