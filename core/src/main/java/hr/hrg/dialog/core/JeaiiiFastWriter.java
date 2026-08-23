package hr.hrg.dialog.core;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Division-free {@code int}/{@code long} to ASCII decimal writer in the spirit
 * of jeaiii's {@code itoa}: digits are emitted most-significant-first in
 * four-digit groups (plus a 1..4 digit leading group) using packed digit tables
 * and reciprocal multiplication instead of hardware division.
 * <p>
 * Each group is a single little-endian {@code LE_INT} VarHandle store from a
 * 40 KB {@code DIGIT_QUADS} table (fixed 4 digits) or a 4 KB
 * {@code TRAILING_TRIPLES} table (1..3 digits right-aligned with a trailing '0'
 * that is overwritten by the next group or lies past the returned pos). A 2-digit
 * value uses one {@code short} store and a 1-digit value a plain byte.
 * <p>
 * Division by a power of ten uses a full 64-bit magic constant and
 * {@link Math#multiplyHigh(long, long)}. For a divisor {@code d = 10^k} with
 * {@code 1 <= k <= 8} the magic {@code M = ceil(2^64 / d)} satisfies
 * {@code multiplyHigh(v, M) == v / d} exactly for every {@code 0 <= v < 2^64 / d};
 * all operands stay non-negative, so the signed {@code multiplyHigh} equals the
 * unsigned high product with no correction terms.
 * <p>
 * A {@code long} (up to 19 digits) is split at {@code 10^9} into a high part of
 * at most 10 digits and a low part of exactly 9 digits, then each half is fed
 * through the same group writer. The split itself is division-free: the 64-bit
 * reciprocal {@code SPLIT_MAGIC = ceil(2^64 / 10^9)} may overshoot the true
 * quotient by at most one, which is repaired with a single signed-remainder
 * correction.
 * <p>
 * The earlier two-digit-pair variant (200-byte table, one {@code short} store
 * per pair) is kept as a benchmark fixture under
 * {@code hr.hrg.dialog.core.perf.JeaiiiPairsWriter}.
 */
public final class JeaiiiFastWriter {

    private static final VarHandle LE_INT =
            MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle LE_SHORT =
            MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.LITTLE_ENDIAN);

    // TWO_DIGITS_LE[i] packs the two ASCII digits of i little-endian
    // (tens in the low byte, ones in the high byte): one 16-bit store per 2-digit value.
    private static final short[] TWO_DIGITS_LE = new short[100];

    // TRAILING_TRIPLES[i] packs the 1..3 significant digits of i RIGHT-aligned to
    // the low bytes, with trailing '0' padding in the high bytes:
    // d0 | d1<<8 | d2<<16 | '0'<<24. One LE_INT store therefore writes the
    // significant digits first and a trailing '0' that is either overwritten by
    // the next group or lies past the returned pos.
    private static final int[] TRAILING_TRIPLES = new int[1000];

    // DIGIT_QUADS[i] packs the four ASCII digits of i little-endian:
    // c0 | c1<<8 | c2<<16 | c3<<24, stored with one LE_INT VarHandle store.
    private static final int[] DIGIT_QUADS = new int[10000];

    /** POW10[i] = 10^i for i in 0..8. */
    private static final long[] POW10 = new long[9];

    /** MAGIC[i] = ceil(2^64 / 10^i) for i in 1..8; fits in a positive long. */
    private static final long[] MAGIC = new long[9];

    /** ceil(2^64 / 10^9), used (with a correction) to split a long at 10^9. */
    private static final long SPLIT_MAGIC;

    private static final byte[] MIN_LONG_BYTES = "-9223372036854775808".getBytes(StandardCharsets.UTF_8);

    static {
        for (int i = 0; i < 100; i++) {
            int d0 = (i / 10) + '0';
            int d1 = (i % 10) + '0';
            TWO_DIGITS_LE[i] = (short) (d0 | (d1 << 8));
        }
        for (int i = 0; i < 1000; i++) {
            int a = i / 100;        // hundreds
            int b = (i / 10) % 10;  // tens
            int c = i % 10;         // ones
            // Right-align the significant digits; byte3 is a trailing '0' pad.
            int d0, d1, d2;
            if (i >= 100) {
                d0 = a; d1 = b; d2 = c;
            } else if (i >= 10) {
                d0 = b; d1 = c; d2 = 0;
            } else {
                d0 = c; d1 = 0; d2 = 0;
            }
            TRAILING_TRIPLES[i] = ('0' + d0) | (('0' + d1) << 8) | (('0' + d2) << 16) | ('0' << 24);
        }
        for (int i = 0; i < 10000; i++) {
            int high = i / 100;
            int low = i - high * 100;
            int c0 = '0' + high / 10;
            int c1 = '0' + high % 10;
            int c2 = '0' + low / 10;
            int c3 = '0' + low % 10;
            DIGIT_QUADS[i] = c0 | (c1 << 8) | (c2 << 16) | (c3 << 24);
        }

        POW10[0] = 1L;
        for (int i = 1; i < POW10.length; i++) {
            POW10[i] = POW10[i - 1] * 10L;
        }

        // ceil(2^64 / d) = unsignedFloor((2^64 - 1) / d) + 1. (2^64 - 1) is the
        // unsigned long with all bits set, i.e. -1L in two's complement.
        for (int i = 1; i < MAGIC.length; i++) {
            MAGIC[i] = Long.divideUnsigned(-1L, POW10[i]) + 1L;
        }
        SPLIT_MAGIC = Long.divideUnsigned(-1L, 1_000_000_000L) + 1L;
    }

    private JeaiiiFastWriter() {
    }

    /**
     * Writes {@code value} as ASCII decimal digits into {@code buffer} at {@code offset}.
     * <p>
     * A 1..3 digit magnitude is written as a full 4-byte word (trailing '0' bytes past the
     * returned length), so {@code buffer} must have at least 4 bytes of room at {@code offset}.
     *
     * @return the number of bytes written.
     */
    public static int writeIntToBytes(byte[] buffer, int offset, int value) {
        int pos = offset;
        long u = value;
        if (value < 0) {
            buffer[pos++] = '-';
            // Negate in long so Integer.MIN_VALUE's magnitude 2147483648 is representable.
            u = -(long) value;
        }
        return writeQuadPositive(buffer, pos, u) - offset;
    }

    /**
     * Writes {@code value} as ASCII decimal digits into {@code buffer} at {@code offset}.
     * <p>
     * A 1..3 digit magnitude is written as a full 4-byte word (trailing '0' bytes past the
     * returned length), so {@code buffer} must have at least 4 bytes of room at {@code offset}.
     *
     * @return the number of bytes written.
     */
    public static int writeLongToBytes(byte[] buffer, int offset, long value) {
        if (value == Long.MIN_VALUE) {
            // Magnitude 2^63 does not fit a signed long; copy the literal.
            System.arraycopy(MIN_LONG_BYTES, 0, buffer, offset, MIN_LONG_BYTES.length);
            return MIN_LONG_BYTES.length;
        }
        int pos = offset;
        long u = value;
        if (value < 0) {
            buffer[pos++] = '-';
            u = -value; // now in [1, 2^63)
        }
        return writeQuadPositiveLong(buffer, pos, u) - offset;
    }

    /** Writes the decimal digits of {@code u} ({@code 0 <= u < 2^63}) and returns the advanced pos. */
    private static int writeQuadPositiveLong(byte[] buffer, int offset, long u) {
        if (u < 1_000_000_000L) {
            return writeQuadPositive(buffer, offset, u);
        }

        // Split at 10^9 without hardware division. multiplyHigh(u, SPLIT_MAGIC)
        // is floor(u / 10^9) or one too high; the signed remainder repair makes
        // it exact: hi = u / 10^9 (1..10 digits), lo = u % 10^9 (9 digits).
        long hi = Math.multiplyHigh(u, SPLIT_MAGIC);
        long lo = u - hi * 1_000_000_000L;
        if (lo < 0) {
            hi -= 1;
            lo += 1_000_000_000L;
        }

        int pos = writeQuadPositive(buffer, offset, hi);
        return writeQuadPadded9(buffer, pos, lo);
    }

    /**
     * Writes the decimal digits of {@code u} ({@code 0 <= u < 10^10}, i.e. at most 10
     * digits) most-significant-first and returns the advanced pos.
     * <p>
     * Every group is a single VarHandle store; a 1..3 digit leading group is written
     * as a full 4-byte word from {@code TRAILING_TRIPLES} (significant digits first,
     * trailing '0' past the returned pos, so it is overwritten by the next group or
     * never observed). Consequently the caller must leave at least 4 bytes of room at
     * {@code offset} even for a 1..3 digit result.
     */
    private static int writeQuadPositive(byte[] buffer, int offset, long u) {
        if (u < 10) {
            buffer[offset] = (byte) ('0' + (int) u);
            return offset + 1;
        }
        if (u < 100) {
            LE_SHORT.set(buffer, offset, TWO_DIGITS_LE[(int) u]);
            return offset + 2;
        }
        if (u < 1000) {
            LE_INT.set(buffer, offset, TRAILING_TRIPLES[(int) u]); // 3 significant + trailing '0'
            return offset + 3;
        }
        if (u < 10000) {
            LE_INT.set(buffer, offset, DIGIT_QUADS[(int) u]);
            return offset + 4;
        }

        // u has 5..10 digits. lead = 1..4 leading digits, div = 4 or 8 remaining digits.
        int digits = decimalDigits(u);
        int lead = ((digits - 1) & 3) + 1;
        int div = digits - lead; // 4 or 8

        long chunk = Math.multiplyHigh(u, MAGIC[div]); // u / 10^div
        long rem = u - chunk * POW10[div];

        int pos = offset;
        if (lead == 4) {
            LE_INT.set(buffer, pos, DIGIT_QUADS[(int) chunk]);
            pos += 4;
        } else {
            LE_INT.set(buffer, pos, TRAILING_TRIPLES[(int) chunk]); // trailing bytes overwritten below
            pos += lead;
        }

        while (div > 0) {
            int k = div - 4;
            long q = k == 0 ? rem : Math.multiplyHigh(rem, MAGIC[k]); // rem / 10^k
            LE_INT.set(buffer, pos, DIGIT_QUADS[(int) q]);
            pos += 4;
            rem -= q * POW10[k];
            div -= 4;
        }
        return pos;
    }

    /** Writes {@code lo} ({@code 0 <= lo < 10^9}) as exactly 9 zero-padded digits. */
    private static int writeQuadPadded9(byte[] buffer, int offset, long lo) {
        int pos = offset;

        int lead = (int) Math.multiplyHigh(lo, MAGIC[8]); // lo / 10^8, 0..9
        buffer[pos++] = (byte) ('0' + lead);
        long r = lo - (long) lead * POW10[8]; // remaining 8 digits

        int q1 = (int) Math.multiplyHigh(r, MAGIC[4]); // r / 10^4
        LE_INT.set(buffer, pos, DIGIT_QUADS[q1]);
        pos += 4;
        r -= (long) q1 * POW10[4];

        LE_INT.set(buffer, pos, DIGIT_QUADS[(int) r]);
        pos += 4;
        return pos;
    }

    /** Number of decimal digits in {@code u} ({@code 0 <= u < 10^10}): 1..10. */
    private static int decimalDigits(long u) {
        if (u < 10L) return 1;
        if (u < 100L) return 2;
        if (u < 1_000L) return 3;
        if (u < 10_000L) return 4;
        if (u < 100_000L) return 5;
        if (u < 1_000_000L) return 6;
        if (u < 10_000_000L) return 7;
        if (u < 100_000_000L) return 8;
        if (u < 1_000_000_000L) return 9;
        return 10;
    }
}
