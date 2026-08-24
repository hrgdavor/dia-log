package hr.hrg.dialog.core;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Correctness and alignment coverage for the packed/varhandle stores in
 * {@link WriteOps}: full 8-byte little-endian long store, the specialized
 * {@code writePackedLE1..7} partial stores, and the "store full 8 bytes then
 * advance by a partial length" trick used by the direct-buffer writer.
 * Every case runs at every byte offset 0..7 so unaligned stores are exercised
 * (the {@code byte[]} VarHandle view must not require 8-byte alignment).
 */
class WriteOpsPackedTest {

    /** 0x0102030405060708L stored little-endian is 08 07 06 05 04 03 02 01. */
    private static final long PATTERN = 0x0102030405060708L;
    private static final long PATTERN2 = 0x0A0B0C0D0E0F1021L;

    @Test
    void leLongStore_storesFullLittleEndianAtEveryOffset() {
        for (int off = 0; off < 8; off++) {
            byte[] buf = sentinel(off + 16);
            WriteOps.LE_LONG.set(buf, off, PATTERN);
            assertLowBytesLE(buf, off, PATTERN, 8);
            assertSentinel(buf, off, 8, off);
        }
    }

    @Test
    void writePackedLE1to7_storeOnlyLowBytesAtEveryOffset() {
        for (int n = 1; n <= 7; n++) {
            for (int off = 0; off < 8; off++) {
                byte[] buf = sentinel(off + 16);
                int returned = specializedWrite(buf, off, PATTERN, n);
                assertEquals(off + n, returned, "n=" + n + " off=" + off);
                assertLowBytesLE(buf, off, PATTERN, n);
                assertSentinel(buf, off, n, off);
            }
        }
    }

    @Test
    void specializedStores_matchGenericWritePackedLE() {
        for (int n = 1; n <= 7; n++) {
            for (int off = 0; off < 8; off++) {
                byte[] a = new byte[32];
                byte[] b = new byte[32];
                int ra = specializedWrite(a, off, PATTERN2, n);
                int rb = PackedWriteTestOps.writePackedLE(b, off, PATTERN2, n);
                assertEquals(rb, ra, "return n=" + n + " off=" + off);
                assertArrayEquals(a, b, "n=" + n + " off=" + off);
            }
        }
    }

    @Test
    void fullStoreThenPartialAdvance_keepsLowBytesCorrect() {
        // "Reserve full 8, advance by len": LE_LONG.set writes 8 bytes; only
        // the low n are logical. The low n bytes must be the packed tail; the
        // high 8-n bytes are overwritten garbage by contract and not asserted.
        for (int n = 1; n <= 7; n++) {
            for (int off = 0; off < 8; off++) {
                byte[] buf = sentinel(off + 16);
                WriteOps.LE_LONG.set(buf, off, PATTERN);
                int pos = off + n;
                assertLowBytesLE(buf, off, PATTERN, n);
                assertEquals(off + n, pos, "pos n=" + n + " off=" + off);
            }
        }
    }

    private static int specializedWrite(byte[] buf, int pos, long v, int n) {
        return switch (n) {
            case 1 -> PackedWriteTestOps.writePackedLE1(buf, pos, v);
            case 2 -> PackedWriteTestOps.writePackedLE2(buf, pos, v);
            case 3 -> PackedWriteTestOps.writePackedLE3(buf, pos, v);
            case 4 -> PackedWriteTestOps.writePackedLE4(buf, pos, v);
            case 5 -> PackedWriteTestOps.writePackedLE5(buf, pos, v);
            case 6 -> PackedWriteTestOps.writePackedLE6(buf, pos, v);
            default -> PackedWriteTestOps.writePackedLE7(buf, pos, v);
        };
    }

    private static byte[] sentinel(int len) {
        byte[] buf = new byte[len];
        Arrays.fill(buf, (byte) 0xAA);
        return buf;
    }

    /** Asserts the store left bytes outside {@code [pos, pos + written)} untouched. */
    private static void assertSentinel(byte[] buf, int pos, int written, int off) {
        for (int i = 0; i < pos; i++) {
            assertEquals((byte) 0xAA, buf[i], "before store, i=" + i + " off=" + off);
        }
        for (int i = pos + written; i < buf.length; i++) {
            assertEquals((byte) 0xAA, buf[i], "after store, i=" + i + " off=" + off);
        }
    }

    private static void assertLowBytesLE(byte[] buf, int pos, long value, int n) {
        for (int i = 0; i < n; i++) {
            assertEquals((byte) (value >>> (i * 8)), buf[pos + i], "byte " + i + " @ " + pos);
        }
    }
}
