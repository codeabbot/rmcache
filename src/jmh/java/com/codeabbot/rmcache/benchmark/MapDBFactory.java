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
