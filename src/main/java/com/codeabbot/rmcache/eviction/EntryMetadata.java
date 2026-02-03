package com.codeabbot.rmcache.eviction;

/**
 * Read-only view of entry metadata.
 */
public interface EntryMetadata {
    short getPriority();

    int getCreatedAtSeconds();

    int getExpiresAtSeconds();

    long getAgeMillis();

    boolean isExpired();
}
