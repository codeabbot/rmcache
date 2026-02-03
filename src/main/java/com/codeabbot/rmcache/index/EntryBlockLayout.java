package com.codeabbot.rmcache.index;

/**
 * Memory layout for entries in the EntryPool.
 */
public class EntryBlockLayout {
    private EntryBlockLayout() {
    }

    public static final int HEADER_SIZE = 24;

    public static int pad(int len) {
        return (len + 3) & ~3;
    }

    public static int computeSize(int keyLen, int valLen) {
        return HEADER_SIZE + 4 + pad(keyLen) + 4 + valLen;
    }
}
