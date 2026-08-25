package hr.hrg.dialog.core;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the T3/T4 direct-buffer API on
 * {@link ReusableByteArrayOutputStream} (ported from Apache Fory commit
 * 585eb16f, "feat(java): optimize json perf", PR #3871): packed little-endian
 * word stores, the direct cursor, and inlined capacity checks. The buffer is
 * fixed-capacity: writes beyond it throw {@link BufferFullException} (no-grow).
 */
class ReusableByteArrayOutputStreamDirectApiTest {

    @Test
    void writeLongPrefixLE_writesLowBytesLittleEndian() {
        ReusableByteArrayOutputStream out = new ReusableByteArrayOutputStream(16);
        out.writeLongPrefixLE(0x0102030405060708L, 8);
        assertEquals(8, out.size());
        byte[] buf = out.buffer();
        for (int i = 0; i < 8; i++) {
            assertEquals((byte) (0x0102030405060708L >>> (i << 3)), buf[i], "byte " + i);
        }
    }

    @Test
    void writeLongPrefixLE_partialWidths() {
        for (int n = 1; n <= 8; n++) {
            ReusableByteArrayOutputStream out = new ReusableByteArrayOutputStream(16);
            out.writeLongPrefixLE(0x1122334455667788L, n);
            assertEquals(n, out.size());
            byte[] buf = out.buffer();
            for (int i = 0; i < n; i++) {
                assertEquals((byte) (0x1122334455667788L >>> (i << 3)), buf[i], "n=" + n + " byte " + i);
            }
        }
        ReusableByteArrayOutputStream zero = new ReusableByteArrayOutputStream(4);
        zero.writeLongPrefixLE(0x1122334455667788L, 0);
        assertEquals(0, zero.size());
    }

    @Test
    void writeIntPrefixLE_partialWidths() {
        for (int n = 1; n <= 4; n++) {
            ReusableByteArrayOutputStream out = new ReusableByteArrayOutputStream(8);
            out.writeIntPrefixLE(0x11223344, n);
            assertEquals(n, out.size());
            byte[] buf = out.buffer();
            for (int i = 0; i < n; i++) {
                assertEquals((byte) (0x11223344 >>> (i << 3)), buf[i], "n=" + n + " byte " + i);
            }
        }
    }

    @Test
    void writeRaw_appendsWithInlineCheck() {
        ReusableByteArrayOutputStream out = new ReusableByteArrayOutputStream(16);
        byte[] data = "hello world".getBytes(StandardCharsets.UTF_8);
        out.writeRaw(data, 0, data.length);
        assertEquals(data.length, out.size());
        assertArrayEquals(data, java.util.Arrays.copyOf(out.buffer(), out.size()));
        // append again while capacity allows (5 + 11 == 16: exact fit)
        out.writeRaw(data, 6, 5);
        assertEquals("hello worldworld", new String(out.buffer(), 0, out.size(), StandardCharsets.UTF_8));
    }

    @Test
    void writeRaw_beyondCapacity_throwsWithoutPartialWrite() {
        ReusableByteArrayOutputStream out = new ReusableByteArrayOutputStream(4);
        byte[] data = "hello world".getBytes(StandardCharsets.UTF_8);
        assertThrows(BufferFullException.class, () -> out.writeRaw(data, 0, data.length));
        // All-or-nothing: no partial copy, no growth.
        assertEquals(0, out.size());
        assertEquals(4, out.buffer().length);
    }

    @Test
    void directCursor_publishesStoredBytes() {
        ReusableByteArrayOutputStream out = new ReusableByteArrayOutputStream(16);
        int pos = out.position();
        assertEquals(0, pos);
        byte[] buf = out.buffer();
        buf[pos] = 'a';
        buf[pos + 1] = 'b';
        out.setPosition(pos + 2);
        assertEquals(2, out.size());
        assertEquals("ab", new String(out.buffer(), 0, out.size(), StandardCharsets.UTF_8));
        // setPosition must refuse out-of-range cursors
        assertThrows(IndexOutOfBoundsException.class, () -> out.setPosition(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> out.setPosition(out.buffer().length + 1));
    }

    @Test
    void packedStoresThrowWhenCapacityExceeded() {
        ReusableByteArrayOutputStream out = new ReusableByteArrayOutputStream(8);
        out.writeLongPrefixLE(0x0102030405060708L, 8);
        assertEquals(8, out.size());
        assertThrows(BufferFullException.class, () -> out.writeLongPrefixLE(0x0102030405060708L, 1));
        assertEquals(8, out.size());
        assertEquals(8, out.buffer().length);
    }

    @Test
    void allWriteMethodsThrowBufferFullExceptionOnOverflow() {
        ReusableByteArrayOutputStream out = new ReusableByteArrayOutputStream(2);
        out.writeByte(1);
        out.writeByte(2);
        assertThrows(BufferFullException.class, () -> out.writeByte(3));
        assertThrows(BufferFullException.class, () -> out.writeIntPrefixLE(1, 1));
        assertThrows(BufferFullException.class, () -> out.writePackedLE(1L, 1));
        assertThrows(BufferFullException.class, () -> out.writePackedLE(1, 1));
        // None of the rejected writes may move the cursor or grow the buffer.
        assertEquals(2, out.size());
        assertEquals(2, out.buffer().length);
    }

    @Test
    void invalidWidthsAreRejected() {
        ReusableByteArrayOutputStream out = new ReusableByteArrayOutputStream(8);
        assertThrows(IllegalArgumentException.class, () -> out.writeLongPrefixLE(1L, 9));
        assertThrows(IllegalArgumentException.class, () -> out.writeLongPrefixLE(1L, -1));
        assertThrows(IllegalArgumentException.class, () -> out.writeIntPrefixLE(1, 5));
    }
}
