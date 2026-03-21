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
package com.codeabbot.rmcache.util;

import org.junit.jupiter.api.Test;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.ArrayList;
import com.codeabbot.rmcache.util.ThreadLocalKeyBuffer;
import com.codeabbot.rmcache.util.ThreadLocalKeyBuffer.BufferResult;

import static org.junit.jupiter.api.Assertions.*;

public class ThreadLocalKeyBufferTest {

    @Test
    public void testEncodeStringBasic() {
        BufferResult res = ThreadLocalKeyBuffer.encodeString("hello");
        byte[] buffer = res.buffer();
        int length = res.length();

        assertEquals(5, length);
        assertEquals((byte) 'h', buffer[0]);
        assertEquals((byte) 'e', buffer[1]);
        assertEquals((byte) 'l', buffer[2]);
        assertEquals((byte) 'l', buffer[3]);
        assertEquals((byte) 'o', buffer[4]);
    }

    @Test
    public void testEncodeStringEmpty() {
        BufferResult res = ThreadLocalKeyBuffer.encodeString("");
        assertEquals(0, res.length());
    }

    @Test
    public void testGetBufferReturnsSameBuffer() {
        byte[] buffer1 = ThreadLocalKeyBuffer.getBuffer();
        byte[] buffer2 = ThreadLocalKeyBuffer.getBuffer();
        assertSame(buffer1, buffer2);
    }

    @Test
    public void testEncodeStringTo() {
        byte[] buffer = new byte[10];
        int length = ThreadLocalKeyBuffer.encodeStringTo("test", buffer);
        assertEquals(4, length);
        assertEquals((byte) 't', buffer[0]);
        assertEquals((byte) 'e', buffer[1]);
        assertEquals((byte) 's', buffer[2]);
        assertEquals((byte) 't', buffer[3]);
    }

    @Test
    public void testConcurrentAccess() throws InterruptedException {
        ConcurrentHashMap<Long, byte[]> buffers = new ConcurrentHashMap<>();
        List<Thread> threads = new ArrayList<>();

        for (int i = 0; i < 4; i++) {
            final int idx = i;
            Thread t = new Thread(() -> {
                BufferResult res = ThreadLocalKeyBuffer.encodeString("thread-" + idx);
                buffers.put(Thread.currentThread().threadId(), res.buffer());
                assertTrue(res.length() > 0);
            });
            threads.add(t);
        }

        for (Thread t : threads)
            t.start();
        for (Thread t : threads)
            t.join();

        // Each thread should have its own buffer
        assertEquals(4, buffers.size());
    }
}
