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
package com.codeabbot.rmcache.memory;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

public final class NativeMemory {
    private NativeMemory() {
    }

    private static final Linker LINKER = Linker.nativeLinker();
    private static final Linker.Option[] EMPTY_OPTIONS = new Linker.Option[0];

    private static final MethodHandle MALLOC_HANDLE = LINKER.downcallHandle(
            LINKER.defaultLookup().find("malloc").get(),
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));

    private static final MethodHandle FREE_HANDLE = LINKER.downcallHandle(
            LINKER.defaultLookup().find("free").get(),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

    private static final MethodHandle CALLOC_HANDLE = LINKER.downcallHandle(
            LINKER.defaultLookup().find("calloc").get(),
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));

    /**
     * Global "Unlimited" segment for raw off-heap access.
     *
     * <p>This is a {@code MemorySegment.ofAddress(0L).reinterpret(Long.MAX_VALUE)},
     * giving unrestricted read/write access to the entire process address space.
     * Any offset arithmetic bug will result in <b>silent memory corruption</b> or a
     * <b>JVM crash (SIGSEGV)</b>. This is a conscious design choice: bounds-checked
     * segments add 2-5ns per access, which at billion-entry scale is a measurable
     * overhead on the critical GET/PUT path.
     *
     * <p><b>Internal use only.</b> All user-facing surfaces ({@code CacheValueView},
     * {@code getZeroCopy}) perform their own bounds validation before delegating here.
     */
    public static final MemorySegment UNLIMITED = MemorySegment.ofAddress(0L).reinterpret(Long.MAX_VALUE);

    public static MemorySegment malloc(long size) {
        try {
            MemorySegment addr = (MemorySegment) MALLOC_HANDLE.invokeExact(size);
            if (addr.address() == 0L) {
                throw new OutOfMemoryError("Native malloc failed for size " + size);
            }
            return addr.reinterpret(size);
        } catch (Throwable t) {
            throw new RuntimeException("Native malloc failed", t);
        }
    }

    public static MemorySegment calloc(long num, long size) {
        try {
            MemorySegment addr = (MemorySegment) CALLOC_HANDLE.invokeExact(num, size);
            if (addr.address() == 0L) {
                throw new OutOfMemoryError("Native calloc failed");
            }
            return addr.reinterpret(num * size);
        } catch (Throwable t) {
            throw new RuntimeException("Native calloc failed", t);
        }
    }

    public static void free(MemorySegment segment) {
        try {
            FREE_HANDLE.invokeExact(segment);
        } catch (Throwable t) {
            throw new RuntimeException("Native free failed", t);
        }
    }

    // Primitive access helpers
    public static byte getByte(long addr) {
        return UNLIMITED.get(ValueLayout.JAVA_BYTE, addr);
    }

    public static int getInt(long addr) {
        return UNLIMITED.get(ValueLayout.JAVA_INT, addr);
    }

    public static long getLong(long addr) {
        return UNLIMITED.get(ValueLayout.JAVA_LONG, addr);
    }

    public static void putLong(long addr, long value) {
        UNLIMITED.set(ValueLayout.JAVA_LONG, addr, value);
    }

    public static void putInt(long addr, int value) {
        UNLIMITED.set(ValueLayout.JAVA_INT, addr, value);
    }
}
