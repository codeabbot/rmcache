package com.codeabbot.rmcache;

import java.time.Duration;

/**
 * Utility class for memory and duration conversions.
 *
 * @author Rabindra Meher
 */
public final class Units {

    private Units() {
    }

    public static long gigabytes(long n) {
        return n * 1024 * 1024 * 1024;
    }

    public static long megabytes(long n) {
        return n * 1024 * 1024;
    }

    public static long kilobytes(long n) {
        return n * 1024;
    }

    public static long gigabytes(int n) {
        return (long) n * 1024 * 1024 * 1024;
    }

    public static long megabytes(int n) {
        return (long) n * 1024 * 1024;
    }

    public static long kilobytes(int n) {
        return (long) n * 1024;
    }

    public static Duration minutes(long n) {
        return Duration.ofMinutes(n);
    }

    public static Duration seconds(long n) {
        return Duration.ofSeconds(n);
    }

    public static Duration hours(long n) {
        return Duration.ofHours(n);
    }

    public static Duration minutes(int n) {
        return Duration.ofMinutes(n);
    }

    public static Duration seconds(int n) {
        return Duration.ofSeconds(n);
    }

    public static Duration hours(int n) {
        return Duration.ofHours(n);
    }
}
