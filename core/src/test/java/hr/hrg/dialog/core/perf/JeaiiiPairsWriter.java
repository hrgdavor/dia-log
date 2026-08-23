package hr.hrg.dialog.core.perf;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * The two-digit-pair variant of the division-free jeaiii-style writer, kept as
 * a benchmark fixture so its small (200-byte, L1-resident) pair table can be
 * compared against the production four-digit-quad writer
 * ({@code hr.hrg.dialog.core.JeaiiiFastWriter}) and
 * {@code hr.hrg.dialog.core.JsonNumberWriter}.
 *
 * <p>Unlike the quad writer this version writes two ASCII digits per
 * {@code short} store from a 100-entry table and uses no 40 KB digit-quad
 * table. It is the implementation {@code JeaiiiFastWriter} carried before the
 * quad rewrite; it lives in {@code src/test} only for benchmark reference.
 */
public final class JeaiiiPairsWriter {

    private static final VarHandle SHORT_ARRAY_HANDLE = MethodHandles.byteArrayViewVarHandle(
            short[].class,
            ByteOrder.LITTLE_ENDIAN
    );

    // Precomputed 2-digit ASCII pairs packed into 16-bit short values (Little-Endian).
    // TWO_DIGITS_LE[i] = tens(i) | (ones(i) << 8).
    private static final short[] TWO_DIGITS_LE = new short[100];

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

    private JeaiiiPairsWriter() {
    }

    public static int writeIntToBytes(byte[] buffer, int offset, int value) {
        int pos = offset;
        long u = value;
        if (value < 0) {
            buffer[pos++] = '-';
            // Negate in long so Integer.MIN_VALUE's magnitude 2147483648 is representable.
            u = -(long) value;
        }
        return writePositive(buffer, pos, u) - offset;
    }

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
        return writePositiveLong(buffer, pos, u) - offset;
    }

    /** Writes the decimal digits of {@code u} ({@code 0 <= u < 2^63}) and returns the advanced pos. */
    private static int writePositiveLong(byte[] buffer, int offset, long u) {
        if (u < 1_000_000_000L) {
            return writePositive(buffer, offset, u);
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

        int pos = writePositive(buffer, offset, hi);
        return writePadded9(buffer, pos, lo);
    }

    /** Writes the decimal digits of {@code u} ({@code 0 <= u < 10^10}) and returns the advanced pos. */
    private static int writePositive(byte[] buffer, int offset, long u) {
        int pos = offset;

        if (u < 10) {
            buffer[pos] = (byte) ('0' + (int) u);
            return pos + 1;
        }
        if (u < 100) {
            write2(buffer, pos, (int) u);
            return pos + 2;
        }

        // u has 3..10 digits. d = (digitCount - 2) is how many digits remain
        // after the leading two-digit pair, so the leading pair is u / 10^d.
        int d;
        if (u < 1_000L) {
            d = 1;
        } else if (u < 10_000L) {
            d = 2;
        } else if (u < 100_000L) {
            d = 3;
        } else if (u < 1_000_000L) {
            d = 4;
        } else if (u < 10_000_000L) {
            d = 5;
        } else if (u < 100_000_000L) {
            d = 6;
        } else if (u < 1_000_000_000L) {
            d = 7;
        } else {
            d = 8;
        }

        int lead = (int) Math.multiplyHigh(u, MAGIC[d]); // leading pair, 10..99
        write2(buffer, pos, lead);
        pos += 2;

        long rem = u - lead * POW10[d]; // remaining d digits, in [0, 10^d)

        // Emit the remaining digits most-significant-first in two-digit groups;
        // an odd leftover is a single trailing digit.
        while (d > 0) {
            if (d == 1) {
                buffer[pos++] = (byte) ('0' + (int) rem);
                break;
            }
            int dd = d - 2;
            int pair = dd == 0 ? (int) rem : (int) Math.multiplyHigh(rem, MAGIC[dd]);
            write2(buffer, pos, pair);
            pos += 2;
            rem -= (long) pair * POW10[dd];
            d -= 2;
        }
        return pos;
    }

    /** Writes {@code lo} ({@code 0 <= lo < 10^9}) as exactly 9 zero-padded digits. */
    private static int writePadded9(byte[] buffer, int offset, long lo) {
        int pos = offset;

        int lead = (int) Math.multiplyHigh(lo, MAGIC[7]); // lo / 10^7, 0..99
        write2(buffer, pos, lead);
        pos += 2;
        long r = lo - (long) lead * POW10[7];

        int p1 = (int) Math.multiplyHigh(r, MAGIC[5]); // r / 10^5
        write2(buffer, pos, p1);
        pos += 2;
        r -= (long) p1 * POW10[5];

        int p2 = (int) Math.multiplyHigh(r, MAGIC[3]); // r / 10^3
        write2(buffer, pos, p2);
        pos += 2;
        r -= (long) p2 * POW10[3];

        int p3 = (int) Math.multiplyHigh(r, MAGIC[1]); // r / 10^1
        write2(buffer, pos, p3);
        pos += 2;
        r -= (long) p3 * POW10[1];

        buffer[pos++] = (byte) ('0' + (int) r); // final digit
        return pos;
    }

    private static void write2(byte[] buffer, int offset, int digits) {
        SHORT_ARRAY_HANDLE.set(buffer, offset, TWO_DIGITS_LE[digits]);
    }
}
