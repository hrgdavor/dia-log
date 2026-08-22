package hr.hrg.dialog.core;

import hr.hrg.dialog.ryu.RyuDouble;
import hr.hrg.dialog.ryu.RyuFloat;

import javax.annotation.concurrent.ThreadSafe;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Low-allocation JSON number serialization (int/long/float/double). The
 * primary shape writes digits straight into a {@code byte[]} at a caller
 * offset and returns the advanced position, using little-endian VarHandle
 * word stores ({@code LE_INT.set} per 4-digit group) and no
 * {@code System.arraycopy}. {@link OutputStream} convenience overloads are
 * provided for the stream fallback path.
 * <p>
 * All methods are stateless and thread-safe.
 */
@ThreadSafe
public final class JsonNumberWriter {

    // T5: packed ASCII digit tables ported from Apache Fory commit 585eb16f
    // ("feat(java): optimize json perf", PR #3871), Utf8JsonWriter.
    // DIGIT_QUADS[i] packs the four ASCII digits of i little-endian into one
    // int: c0 | (c1 << 8) | (c2 << 16) | (c3 << 24), stored with one LE_INT
    // VarHandle store. DIGIT_TRIPLES[i] additionally packs a leading-zero skip
    // count in the low byte: skip | (c0 << 8) | (c1 << 16) | (c2 << 24) with c2
    // the ones digit, so 1..3 significant digits come from one lookup and one
    // shifted store group.
    private static final int[] DIGIT_TRIPLES = new int[1000];
    private static final int[] DIGIT_QUADS = new int[10000];

    public static final int MAX_INT_BYTES = 11;

    private static final byte[] JSON_NULL = "null".getBytes(StandardCharsets.UTF_8);
    private static final StringByteExtractor.ByteWriter STRING_STRATEGY = StringByteExtractor.getStrategy();

    // 20 digits + optional '-'
    public static final int MAX_LONG_BYTES = 20;
    private static final byte[] MIN_LONG_BYTES = "-9223372036854775808".getBytes(StandardCharsets.UTF_8);

    // IEEE 754 max ASCII lengths
    public static final int MAX_FLOAT_BYTES = 16;
    public static final int MAX_DOUBLE_BYTES = 25;

    private static final VarHandle LE_INT =
            MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle LE_LONG =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    /** "null" packed little-endian for a single LE_INT store. */
    private static final int JSON_NULL_LE = (JSON_NULL[0] & 0xFF)
            | ((JSON_NULL[1] & 0xFF) << 8)
            | ((JSON_NULL[2] & 0xFF) << 16)
            | ((JSON_NULL[3] & 0xFF) << 24);

    /** Little-endian packed words of {@code "-9223372036854775808"} (2 longs + 1 int). */
    private static final long MIN_LONG_W0;
    private static final long MIN_LONG_W1;
    private static final int MIN_LONG_W2;

    /** POW10[i] = 10^i, for i in 0..18 (used to slice the most-significant digits). */
    private static final long[] POW10 = new long[19];

    static {
        long w0 = 0;
        long w1 = 0;
        for (int i = 0; i < 8; i++) {
            w0 |= (long) (MIN_LONG_BYTES[i] & 0xFF) << (i * 8);
            w1 |= (long) (MIN_LONG_BYTES[8 + i] & 0xFF) << (i * 8);
        }
        MIN_LONG_W0 = w0;
        MIN_LONG_W1 = w1;
        MIN_LONG_W2 = (MIN_LONG_BYTES[16] & 0xFF)
                | ((MIN_LONG_BYTES[17] & 0xFF) << 8)
                | ((MIN_LONG_BYTES[18] & 0xFF) << 16)
                | ((MIN_LONG_BYTES[19] & 0xFF) << 24);

        POW10[0] = 1L;
        for (int i = 1; i < POW10.length; i++) {
            POW10[i] = POW10[i - 1] * 10L;
        }

        for (int i = 0; i < 1000; i++) {
            int c0 = '0' + i / 100;
            int c1 = '0' + (i / 10) % 10;
            int c2 = '0' + i % 10;
            int skip = i < 10 ? 2 : i < 100 ? 1 : 0;
            DIGIT_TRIPLES[i] = skip | (c0 << 8) | (c1 << 16) | (c2 << 24);
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
    }

    private JsonNumberWriter() {}

    // =========================================================================
    // byte[] + offset (hot path: writes directly, no scratch buffer, no arraycopy)
    // =========================================================================

    /**
     * Writes the decimal digits of {@code value} into {@code buf} at {@code pos}
     * and returns the advanced position. Digits are written most-significant
     * first with one little-endian {@code LE_INT} store per 4-digit group —
     * no scratch buffer and no {@code System.arraycopy}.
     */
    public static int writeInt(byte[] buf, int pos, int value) {
        if (value == Integer.MIN_VALUE) {
            // Magnitude 2147483648 does not fit an int; delegate to the long path.
            return writeLong(buf, pos, value);
        }
        boolean negative = value < 0;
        int v = negative ? -value : value;
        if (negative) {
            buf[pos++] = '-';
        }
        return writePositiveLong(buf, pos, v);
    }

    /**
     * Writes the decimal digits of {@code value} into {@code buf} at {@code pos}
     * and returns the advanced position. Digits are written most-significant
     * first with one little-endian {@code LE_INT} store per 4-digit group —
     * no scratch buffer and no {@code System.arraycopy}.
     */
    public static int writeLong(byte[] buf, int pos, long value) {
        if (value == Long.MIN_VALUE) {
            LE_LONG.set(buf, pos, MIN_LONG_W0);
            LE_LONG.set(buf, pos + 8, MIN_LONG_W1);
            LE_INT.set(buf, pos + 16, MIN_LONG_W2);
            return pos + MIN_LONG_BYTES.length;
        }
        boolean negative = value < 0;
        long v = negative ? -value : value;
        if (negative) {
            buf[pos++] = '-';
        }
        return writePositiveLong(buf, pos, v);
    }

    /** Writes {@code value} (Ryu) into {@code buf} at {@code pos}; {@code null} for non-finite. */
    public static int writeFloat(byte[] buf, int pos, float value) {
        if (!Float.isFinite(value)) {
            LE_INT.set(buf, pos, JSON_NULL_LE);
            return pos + JSON_NULL.length;
        }
        int len = RyuFloat.writeFloat(value, buf, pos);
        return pos + len;
    }

    /** Writes {@code value} (Ryu) into {@code buf} at {@code pos}; {@code null} for non-finite. */
    public static int writeDouble(byte[] buf, int pos, double value) {
        if (!Double.isFinite(value)) {
            LE_INT.set(buf, pos, JSON_NULL_LE);
            return pos + JSON_NULL.length;
        }
        int len = RyuDouble.writeDouble(value, buf, pos);
        return pos + len;
    }

    // =========================================================================
    // OutputStream overloads (stream fallback path)
    // =========================================================================

    public static void writeInt(OutputStream out, int value) throws IOException {
        byte[] tmp = new byte[MAX_INT_BYTES];
        int len = writeInt(tmp, 0, value);
        out.write(tmp, 0, len);
    }

    public static void writeLong(OutputStream out, long value) throws IOException {
        byte[] tmp = new byte[MAX_LONG_BYTES];
        int len = writeLong(tmp, 0, value);
        out.write(tmp, 0, len);
    }

    public static void writeFloat(OutputStream out, float value) throws IOException {
        if (!Float.isFinite(value)) {
            out.write(JSON_NULL);
            return;
        }
        byte[] tmp = new byte[MAX_FLOAT_BYTES];
        int len = writeFloat(tmp, 0, value);
        out.write(tmp, 0, len);
    }

    public static void writeDouble(OutputStream out, double value) throws IOException {
        if (!Double.isFinite(value)) {
            out.write(JSON_NULL);
            return;
        }
        byte[] tmp = new byte[MAX_DOUBLE_BYTES];
        int len = writeDouble(tmp, 0, value);
        out.write(tmp, 0, len);
    }

    public static void writeNumber(OutputStream out, Number value) throws IOException {
        if (value == null) {
            out.write(JSON_NULL);
            return;
        }
        switch (value) {
            case Integer i -> writeInt(out, i);
            case Long l -> writeLong(out, l);
            case Short s -> writeInt(out, s.intValue());
            case Byte b -> writeInt(out, b.intValue());
            case Float f -> writeFloat(out, f);
            case Double d -> writeDouble(out, d);
            default -> STRING_STRATEGY.write(out, value.toString());
        }
    }

    // =========================================================================
    // Digit-building internals (write left-to-right into buf)
    // =========================================================================

    /** Writes the decimal digits of positive {@code v} most-significant-first, returning the advanced pos. */
    private static int writePositiveLong(byte[] buf, int pos, long v) {
        int digits = decimalDigits(v);
        int lead = ((digits - 1) & 3) + 1;   // 1..4 digits in the first (partial) group
        long divisor = POW10[digits - lead];
        int chunk = (int) (v / divisor);
        v -= (long) chunk * divisor;

        if (lead == 4) {
            LE_INT.set(buf, pos, DIGIT_QUADS[chunk]);
            pos += 4;
        } else {
            pos = writeTriple(buf, pos, chunk);
        }
        digits -= lead;

        while (digits > 0) {
            divisor = POW10[digits - 4];
            chunk = (int) (v / divisor);
            v -= (long) chunk * divisor;
            LE_INT.set(buf, pos, DIGIT_QUADS[chunk]);
            pos += 4;
            digits -= 4;
        }
        return pos;
    }

    /** Writes the 1..3 significant digits of {@code v} (v in 0..999) left-to-right, returning the advanced pos. */
    private static int writeTriple(byte[] buf, int pos, int v) {
        int d = DIGIT_TRIPLES[v];
        int skip = d & 0xFF;
        int shifted = d >>> ((skip + 1) << 3);
        int len = 3 - skip;
        switch (len) {
            case 3: buf[pos + 2] = (byte) (shifted >>> 16);
            case 2: buf[pos + 1] = (byte) (shifted >>> 8);
            case 1: buf[pos] = (byte) shifted;
            default: break;
        }
        return pos + len;
    }

    /** Number of decimal digits in positive {@code v} (1..19). */
    private static int decimalDigits(long v) {
        if (v < 10L) return 1;
        if (v < 100L) return 2;
        if (v < 1_000L) return 3;
        if (v < 10_000L) return 4;
        if (v < 100_000L) return 5;
        if (v < 1_000_000L) return 6;
        if (v < 10_000_000L) return 7;
        if (v < 100_000_000L) return 8;
        if (v < 1_000_000_000L) return 9;
        if (v < 10_000_000_000L) return 10;
        if (v < 100_000_000_000L) return 11;
        if (v < 1_000_000_000_000L) return 12;
        if (v < 10_000_000_000_000L) return 13;
        if (v < 100_000_000_000_000L) return 14;
        if (v < 1_000_000_000_000_000L) return 15;
        if (v < 10_000_000_000_000_000L) return 16;
        if (v < 100_000_000_000_000_000L) return 17;
        if (v < 1_000_000_000_000_000_000L) return 18;
        return 19;
    }
}
