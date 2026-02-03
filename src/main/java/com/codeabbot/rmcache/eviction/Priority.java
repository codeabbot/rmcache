package com.codeabbot.rmcache.eviction;

/**
 * Priority levels for entries.
 * Higher priority = less likely to be evicted.
 */
public final class Priority {
    private Priority() {
    }

    public static final short LOW = -1;
    public static final short NORMAL = 0;
    public static final short HIGH = 1;
    public static final short CRITICAL = 2;
}
