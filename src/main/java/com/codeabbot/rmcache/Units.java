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
