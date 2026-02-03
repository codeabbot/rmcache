package com.codeabbot.rmcache;

/**
 * High-performance Cache Context to unify ThreadLocals.
 */
public final class CacheContext {
    public final byte[] keyBuffer = new byte[1024];
    public final byte[] valueBuffer = new byte[256 * 1024];
    public long hits = 0;
    public long misses = 0;
    public int opCounter = 0;
}
