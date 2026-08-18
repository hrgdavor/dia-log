package hr.hrg.dialog.core.perf;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The pre-optimization implementation of {@code JsonNumberWriter}'s int/long
 * writers, kept verbatim (in shape) for performance comparison.
 *
 * <p>These are the digit-by-digit writers that T5 replaced: {@code writeLong}
 * divides by 10 per digit and stores one byte per digit; {@code writeInt}
 * divides by 100 per step using the two-digit {@code DIGIT_PAIRS} table. Kept
 * in {@code src/test} as the baseline for the packed 4-digit
 * {@code DIGIT_QUADS} / {@code DIGIT_TRIPLES} chunked writers (see
 * {@code hr.hrg.dialog.core.JsonNumberWriter}).
 */
public final class ClassicJsonNumberWriter {

    private static final byte[] DIGIT_PAIRS = new byte[200];
    public static final int MAX_INT_BYTES = 11;
    private static final byte[] MIN_INT_BYTES = "-2147483648".getBytes(StandardCharsets.UTF_8);
    private static final int MAX_LONG_BYTES = 20;
    private static final byte[] MIN_LONG_BYTES = "-9223372036854775808".getBytes(StandardCharsets.UTF_8);

    static {
        for (int i = 0; i < 100; i++) {
            DIGIT_PAIRS[i * 2] = (byte) ('0' + (i / 10));
            DIGIT_PAIRS[i * 2 + 1] = (byte) ('0' + (i % 10));
        }
    }

    private ClassicJsonNumberWriter() {}

    public static byte[] makeIntBuffer() {
        return new byte[MAX_INT_BYTES];
    }

    public static byte[] makeLongBuffer() {
        return new byte[MAX_LONG_BYTES];
    }

    /** Old per-2-digit int writer (the T5 baseline). */
    public static void writeInt(OutputStream out, byte[] intBuffer, int value) throws IOException {
        if (value == Integer.MIN_VALUE) {
            out.write(MIN_INT_BYTES);
            return;
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

    /** Old per-digit long writer (the T5 baseline). */
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
}
