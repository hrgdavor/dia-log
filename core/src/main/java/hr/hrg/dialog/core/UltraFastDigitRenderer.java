package hr.hrg.dialog.core;

import java.io.IOException;
import java.io.OutputStream;

public final class UltraFastDigitRenderer {

    // Pre-encoded ASCII byte lookup table for 00 to 99
    // Each pair stores [tens_byte, ones_byte]
    private static final byte[] DIGIT_PAIRS = new byte[200];
    public static final int MAX_INT_BYTES = 11;
    private static final byte[] MIN_INT_BYTES = "-2147483648".getBytes();

    static {
        for (int i = 0; i < 100; i++) {
            DIGIT_PAIRS[i * 2]     = (byte) ('0' + (i / 10));
            DIGIT_PAIRS[i * 2 + 1] = (byte) ('0' + (i % 10));
        }
    }

    private UltraFastDigitRenderer() {}

    public static final byte[] makeNumberBuffer() {
        return new byte[MAX_INT_BYTES];
    }

    private static int stringSize(int x) {
        if (x < 0) {
            return (x == Integer.MIN_VALUE) ? 11 : 1 + stringSize(-x);
        }
        int p = 10;
        for (int i = 1; i < 10; i++) {
            if (x < p) return i;
            p = 10 * p;
        }
        return 10;
    }

    public static void writeInt(OutputStream os, byte[] buf, int value) throws IOException {
        // Fast path for Integer.MIN_VALUE since Math.abs(MIN_VALUE) overflows
        if (value == Integer.MIN_VALUE) {
            os.write(MIN_INT_BYTES);
            return;
        }
        if(buf.length < MAX_INT_BYTES) { throw new IllegalArgumentException("Buffer too small, must be at least "+MAX_INT_BYTES); }
        int cursor = MAX_INT_BYTES;

        boolean negative = value < 0;
        if (negative) {
            value = -value;
        }

        int q, r;

        // Process 2 digits at a time using lookup pairs
        while (value >= 100) {
            q = value / 100;
            // r = value - (q * 100) using fast bit-shifts
            r = value - ((q << 6) + (q << 5) + (q << 2));
            value = q;

            cursor -= 2;
            int idx = r * 2;
            buf[cursor]     = DIGIT_PAIRS[idx];
            buf[cursor + 1] = DIGIT_PAIRS[idx + 1];
        }

        // Handle remaining 1 or 2 digits
        if (value < 10) {
            buf[--cursor] = (byte) ('0' + value);
        } else {
            cursor -= 2;
            int idx = value * 2;
            buf[cursor]     = DIGIT_PAIRS[idx];
            buf[cursor + 1] = DIGIT_PAIRS[idx + 1];
        }

        if (negative) {
            buf[--cursor] = (byte) '-';
        }

        // Single OS write call for the exact formatted byte slice
        os.write(buf, cursor, MAX_INT_BYTES - cursor);
    }

}