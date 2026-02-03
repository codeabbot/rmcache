package com.codeabbot.rmcache.index;

/**
 * Result of putOrGet operation: slot and whether it was newly inserted.
 */
public record PutOrGetResult(int slot, boolean inserted) {
}
