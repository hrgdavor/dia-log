package hr.hrg.dialog.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ReusableByteArrayOutputStream}: write paths, growth beyond the
 * initial capacity, {@code reset()} reuse without shrinking, and bulk {@code writeTo}.
 */
class ReusableByteArrayOutputStreamTest {

    @Test
    void writesBytesAndTracksSize() {
        ReusableByteArrayOutputStream out = new ReusableByteArrayOutputStream(16);
        out.write('a');
        out.write(new byte[]{'b', 'c'}, 0, 2);
        assertEquals(3, out.size());
        assertArrayEquals("abc".getBytes(StandardCharsets.UTF_8),
                java.util.Arrays.copyOf(out.buffer(), out.size()));
    }

    @Test
    void growsWhenEventExceedsCapacity() {
        ReusableByteArrayOutputStream out = new ReusableByteArrayOutputStream(8);
        byte[] payload = "0123456789abcdefghijklmnopqrstuvwxyz".getBytes(StandardCharsets.UTF_8);
        out.write(payload, 0, payload.length);
        assertEquals(payload.length, out.size());
        assertArrayEquals(payload, java.util.Arrays.copyOf(out.buffer(), out.size()));
    }

    @Test
    void growsFromSingleByteWrites() {
        ReusableByteArrayOutputStream out = new ReusableByteArrayOutputStream(4);
        for (int i = 0; i < 1000; i++) {
            out.write(i & 0xFF);
        }
        assertEquals(1000, out.size());
        byte[] buf = out.buffer();
        for (int i = 0; i < 1000; i++) {
            assertEquals((byte) (i & 0xFF), buf[i]);
        }
    }

    @Test
    void resetReusesBufferWithoutShrinking() {
        ReusableByteArrayOutputStream out = new ReusableByteArrayOutputStream(8);
        byte[] big = new byte[100];
        java.util.Arrays.fill(big, (byte) 'x');
        out.write(big, 0, big.length);
        assertEquals(100, out.size());

        out.reset();
        assertEquals(0, out.size());

        // Same capacity must still hold the previous longest event without re-growing.
        out.write(big, 0, big.length);
        assertEquals(100, out.size());
        assertArrayEquals(big, java.util.Arrays.copyOf(out.buffer(), out.size()));
    }

    @Test
    void writeToFlushesWholeEventInOneCall() throws IOException {
        ReusableByteArrayOutputStream out = new ReusableByteArrayOutputStream(8);
        out.write("hello ".getBytes(StandardCharsets.UTF_8), 0, 6);
        out.write('w');
        out.write('o');
        out.write('r');
        out.write('l');
        out.write('d');

        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        out.writeTo(sink);
        assertEquals("hello world", sink.toString(StandardCharsets.UTF_8));
        // writeTo must not reset — caller decides when to reuse the buffer.
        assertEquals(11, out.size());
    }

    @Test
    void defaultCapacityIsOneMegabyte() {
        ReusableByteArrayOutputStream out = new ReusableByteArrayOutputStream();
        assertEquals(ReusableByteArrayOutputStream.DEFAULT_CAPACITY, out.buffer().length);
    }

    @Test
    void zeroLenWritesAreNoOps() {
        ReusableByteArrayOutputStream out = new ReusableByteArrayOutputStream(4);
        out.write(new byte[0], 0, 0);
        out.write(new byte[]{1, 2}, 0, 0);
        assertEquals(0, out.size());
    }
}
