package hr.hrg.dialog.core;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public final class JsonNumberWriter {

    // Pre-encoded ASCII byte lookup table for 00..99 (tens, ones)
    private static final byte[] DIGIT_PAIRS = new byte[200];
    public static final int MAX_INT_BYTES = 11;
    private static final byte[] MIN_INT_BYTES = "-2147483648".getBytes(StandardCharsets.UTF_8);

    private static final byte[] JSON_NULL = "null".getBytes(StandardCharsets.UTF_8);
    private static final StringByteExtractor.ByteWriter STRING_STRATEGY = StringByteExtractor.getStrategy();

    // 20 digits + optional '-'
    private static final int MAX_LONG_BYTES = 20;
    private static final byte[] MIN_LONG_BYTES = "-9223372036854775808".getBytes(StandardCharsets.UTF_8);

    static {
        for (int i = 0; i < 100; i++) {
            DIGIT_PAIRS[i * 2] = (byte) ('0' + (i / 10));
            DIGIT_PAIRS[i * 2 + 1] = (byte) ('0' + (i % 10));
        }
    }

    private JsonNumberWriter() {}

    public static byte[] makeIntBuffer() {
        return new byte[MAX_INT_BYTES];
    }

    public static byte[] makeLongBuffer() {
        return new byte[MAX_LONG_BYTES];
    }

    public static void writeInt(OutputStream out, byte[] intBuffer, int value) throws IOException {
        if (value == Integer.MIN_VALUE) {
            out.write(MIN_INT_BYTES);
            return;
        }
        if (intBuffer.length < MAX_INT_BYTES) {
            throw new IllegalArgumentException("Buffer too small, must be at least " + MAX_INT_BYTES);
        }

        int cursor = MAX_INT_BYTES;
        boolean negative = value < 0;
        if (negative) value = -value;

        while (value >= 100) {
            int q = value / 100;
            int r = value - ((q << 6) + (q << 5) + (q << 2));
            value = q;

            cursor -= 2;
            int idx = r * 2;
            intBuffer[cursor] = DIGIT_PAIRS[idx];
            intBuffer[cursor + 1] = DIGIT_PAIRS[idx + 1];
        }

        if (value < 10) {
            intBuffer[--cursor] = (byte) ('0' + value);
        } else {
            cursor -= 2;
            int idx = value * 2;
            intBuffer[cursor] = DIGIT_PAIRS[idx];
            intBuffer[cursor + 1] = DIGIT_PAIRS[idx + 1];
        }

        if (negative) {
            intBuffer[--cursor] = (byte) '-';
        }

        out.write(intBuffer, cursor, MAX_INT_BYTES - cursor);
    }

    public static void writeLong(OutputStream out, byte[] longBuffer, long value) throws IOException {
        if (value == Long.MIN_VALUE) {
            out.write(MIN_LONG_BYTES);
            return;
        }

        int cursor = MAX_LONG_BYTES;

        boolean negative = value < 0;
        if (negative) value = -value;

        while (value >= 10) {
            long q = value / 10;
            int digit = (int) (value - (q * 10));
            longBuffer[--cursor] = (byte) ('0' + digit);
            value = q;
        }

        longBuffer[--cursor] = (byte) ('0' + value);

        if (negative) {
            longBuffer[--cursor] = (byte) '-';
        }

        out.write(longBuffer, cursor, MAX_LONG_BYTES - cursor);
    }

    public static void writeFloat(OutputStream out, float value) throws IOException {
        if (!Float.isFinite(value)) {
            out.write(JSON_NULL);
            return;
        }
        STRING_STRATEGY.write(out, Float.toString(value));
    }

    public static void writeDouble(OutputStream out, double value) throws IOException {
        if (!Double.isFinite(value)) {
            out.write(JSON_NULL);
            return;
        }
        STRING_STRATEGY.write(out, Double.toString(value));
    }

    public static void writeNumber(OutputStream out, byte[] intBuffer, byte[] longBuffer, Number value) throws IOException {
        if (value == null) {
            out.write(JSON_NULL);
            return;
        }

        switch (value) {
            case Integer i -> writeInt(out, intBuffer, i);
            case Long l -> writeLong(out, longBuffer, l);
            case Short s -> writeInt(out, intBuffer, s.intValue());
            case Byte b -> writeInt(out, intBuffer, b.intValue());
            case Float f -> writeFloat(out, f);
            case Double d -> writeDouble(out, d);
            default -> STRING_STRATEGY.write(out, value.toString());
        }
    }
}
