package hr.hrg.dialog.core;

import hr.hrg.dialog.ryu.RyuDouble;
import hr.hrg.dialog.ryu.RyuFloat;

import javax.annotation.concurrent.ThreadSafe;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Low-allocation JSON number serialization (int/long/float/double) that writes
 * digits directly to an {@link OutputStream} using caller-provided reusable
 * buffers, with Ryu-based float/double formatting. All methods are stateless
 * and thread-safe.
 */
@ThreadSafe
public final class JsonNumberWriter {

    // T5: packed ASCII digit tables ported from Apache Fory commit 585eb16f
    // ("feat(java): optimize json perf", PR #3871), Utf8JsonWriter.
    // DIGIT_QUADS[i] packs the four ASCII digits of i little-endian into one
    // int: c0 | (c1 << 8) | (c2 << 16) | (c3 << 24), stored with 4 byte stores.
    // DIGIT_TRIPLES[i] additionally packs a leading-zero skip count in the low
    // byte: skip | (c0 << 8) | (c1 << 16) | (c2 << 24) with c2 the ones digit, so
    // 1..3 significant digits come from one lookup and one shifted store group.
    private static final int[] DIGIT_TRIPLES = new int[1000];
    private static final int[] DIGIT_QUADS = new int[10000];

    public static final int MAX_INT_BYTES = 11;
    private static final byte[] MIN_INT_BYTES = "-2147483648".getBytes(StandardCharsets.UTF_8);

    private static final byte[] JSON_NULL = "null".getBytes(StandardCharsets.UTF_8);
    private static final StringByteExtractor.ByteWriter STRING_STRATEGY = StringByteExtractor.getStrategy();

    // 20 digits + optional '-'
    private static final int MAX_LONG_BYTES = 20;
    private static final byte[] MIN_LONG_BYTES = "-9223372036854775808".getBytes(StandardCharsets.UTF_8);

    // IEEE 754 max ASCII lengths
    public static final int MAX_FLOAT_BYTES = 16;
    public static final int MAX_DOUBLE_BYTES = 25;

    static {
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

    public static byte[] makeIntBuffer() {
        return new byte[MAX_INT_BYTES];
    }

    public static byte[] makeLongBuffer() {
        return new byte[MAX_LONG_BYTES];
    }

    public static byte[] makeFloatBuffer() {
        return new byte[MAX_FLOAT_BYTES];
    }

    public static byte[] makeDoubleBuffer() {
        return new byte[MAX_DOUBLE_BYTES];
    }

    public static void writeInt(OutputStream out, byte[] intBuffer, int value) throws IOException {
        if (intBuffer.length < MAX_INT_BYTES) {
            throw new IllegalArgumentException("Buffer too small, must be at least " + MAX_INT_BYTES);
        }
        int len = buildInt(intBuffer, value);
        out.write(intBuffer, MAX_INT_BYTES - len, len);
    }

    /**
     * Builds the decimal digits of {@code value} into {@code intBuffer} in the
     * end-aligned layout, returning the number of significant bytes. The digits
     * occupy {@code intBuffer[MAX_INT_BYTES - len .. MAX_INT_BYTES)}.
     */
    public static int buildInt(byte[] intBuffer, int value) {
        if (value == Integer.MIN_VALUE) {
            System.arraycopy(MIN_INT_BYTES, 0, intBuffer, MAX_INT_BYTES - MIN_INT_BYTES.length, MIN_INT_BYTES.length);
            return MIN_INT_BYTES.length;
        }

        int cursor = MAX_INT_BYTES;
        boolean negative = value < 0;
        if (negative) value = -value;

        // T5: 4 digits per step via DIGIT_QUADS instead of 2 via DIGIT_PAIRS.
        while (value >= 10000) {
            int q = value / 10000;
            int r = value - q * 10000;
            value = q;
            cursor -= 4;
            putIntLE(intBuffer, cursor, DIGIT_QUADS[r]);
        }

        cursor = writeFinalChunk(intBuffer, cursor, value);

        if (negative) {
            intBuffer[--cursor] = (byte) '-';
        }
        return MAX_INT_BYTES - cursor;
    }

    /** Writes {@code value} into the direct-buffer cursor (T4 option 2). */
    public static void writeInt(DirectJsonBuffer c, byte[] intBuffer, int value) {
        int len = buildInt(intBuffer, value);
        c.writeRaw(intBuffer, MAX_INT_BYTES - len, len);
    }

    public static void writeLong(OutputStream out, byte[] longBuffer, long value) throws IOException {
        if (longBuffer.length < MAX_LONG_BYTES) {
            throw new IllegalArgumentException("Buffer too small, must be at least " + MAX_LONG_BYTES);
        }
        int len = buildLong(longBuffer, value);
        out.write(longBuffer, MAX_LONG_BYTES - len, len);
    }

    /**
     * Builds the decimal digits of {@code value} into {@code longBuffer} in the
     * end-aligned layout, returning the number of significant bytes. The digits
     * occupy {@code longBuffer[MAX_LONG_BYTES - len .. MAX_LONG_BYTES)}.
     */
    public static int buildLong(byte[] longBuffer, long value) {
        if (value == Long.MIN_VALUE) {
            System.arraycopy(MIN_LONG_BYTES, 0, longBuffer, MAX_LONG_BYTES - MIN_LONG_BYTES.length, MIN_LONG_BYTES.length);
            return MIN_LONG_BYTES.length;
        }

        int cursor = MAX_LONG_BYTES;

        boolean negative = value < 0;
        if (negative) value = -value;

        // T5: values that fit an int take the cheaper int path (Fory's
        // writePositiveLong fast path), otherwise 4 digits per step.
        if (value <= Integer.MAX_VALUE) {
            cursor = writePackedInt(longBuffer, cursor, (int) value);
        } else {
            while (value >= 10000) {
                long q = value / 10000;
                int r = (int) (value - q * 10000);
                value = q;
                cursor -= 4;
                putIntLE(longBuffer, cursor, DIGIT_QUADS[r]);
            }
            cursor = writeFinalChunk(longBuffer, cursor, (int) value);
        }

        if (negative) {
            longBuffer[--cursor] = (byte) '-';
        }
        return MAX_LONG_BYTES - cursor;
    }

    /** Writes {@code value} into the direct-buffer cursor (T4 option 2). */
    public static void writeLong(DirectJsonBuffer c, byte[] longBuffer, long value) {
        int len = buildLong(longBuffer, value);
        c.writeRaw(longBuffer, MAX_LONG_BYTES - len, len);
    }

    /**
     * Writes an int into {@code buf} from the end (cursor moves left), returning
     * the new cursor. Used by the long fast path; the value fits in 10 digits.
     */
    private static int writePackedInt(byte[] buf, int cursor, int value) {
        while (value >= 10000) {
            int q = value / 10000;
            int r = value - q * 10000;
            value = q;
            cursor -= 4;
            putIntLE(buf, cursor, DIGIT_QUADS[r]);
        }
        return writeFinalChunk(buf, cursor, value);
    }

    /** Writes the most significant 1..4 digits of {@code v} (v in 0..9999). */
    private static int writeFinalChunk(byte[] buf, int cursor, int v) {
        if (v >= 1000) {
            cursor -= 4;
            putIntLE(buf, cursor, DIGIT_QUADS[v]);
            return cursor;
        }
        int digits = DIGIT_TRIPLES[v];
        int skip = digits & 0xFF;
        int shifted = digits >>> ((skip + 1) << 3);
        int len = 3 - skip;
        cursor -= len;
        switch (len) {
            case 3: buf[cursor + 2] = (byte) (shifted >>> 16);
            case 2: buf[cursor + 1] = (byte) (shifted >>> 8);
            case 1: buf[cursor] = (byte) shifted;
            default: break;
        }
        return cursor;
    }

    /** Four little-endian byte stores of one packed ASCII digit quad. */
    private static void putIntLE(byte[] buf, int pos, int digits) {
        buf[pos] = (byte) digits;
        buf[pos + 1] = (byte) (digits >>> 8);
        buf[pos + 2] = (byte) (digits >>> 16);
        buf[pos + 3] = (byte) (digits >>> 24);
    }

    public static void writeFloat(OutputStream out, byte[] floatBuffer, float value) throws IOException {
        if (!Float.isFinite(value)) {
            out.write(JSON_NULL);
            return;
        }
        int len = RyuFloat.writeFloat(value, floatBuffer, 0);
        out.write(floatBuffer, 0, len);
    }

    public static void writeDouble(OutputStream out, byte[] doubleBuffer, double value) throws IOException {
        if (!Double.isFinite(value)) {
            out.write(JSON_NULL);
            return;
        }
        int len = RyuDouble.writeDouble(value, doubleBuffer, 0);
        out.write(doubleBuffer, 0, len);
    }

    public static void writeNumber(OutputStream out, byte[] intBuffer, byte[] longBuffer, byte[] floatBuffer, byte[] doubleBuffer, Number value) throws IOException {
        if (value == null) {
            out.write(JSON_NULL);
            return;
        }

        switch (value) {
            case Integer i -> writeInt(out, intBuffer, i);
            case Long l -> writeLong(out, longBuffer, l);
            case Short s -> writeInt(out, intBuffer, s.intValue());
            case Byte b -> writeInt(out, intBuffer, b.intValue());
            case Float f -> writeFloat(out, floatBuffer, f);
            case Double d -> writeDouble(out, doubleBuffer, d);
            default -> STRING_STRATEGY.write(out, value.toString());
        }
    }

}
