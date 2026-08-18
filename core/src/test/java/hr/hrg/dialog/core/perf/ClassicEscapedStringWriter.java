package hr.hrg.dialog.core.perf;

import java.io.IOException;
import java.io.OutputStream;

/**
 * The pre-optimization implementation of {@code EscapedJsonStringWriter}'s
 * Latin-1 escape path, kept verbatim (in shape) for performance comparison.
 *
 * <p>This is the per-byte scan that T1/T2 replaced: every byte is classified
 * individually with a {@code switch} plus three comparisons, clean ASCII runs
 * are flushed as bulk writes, and escapes are emitted byte-by-byte through the
 * {@link OutputStream} interface. Kept in {@code src/test} as the baseline for
 * the new SWAR word-scan implementation (see
 * {@code hr.hrg.dialog.core.EscapedJsonStringWriter}).
 */
public final class ClassicEscapedStringWriter {

    private static final byte[] ESCAPED_QUOTE = "\\\"".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final byte[] ESCAPED_BACKSLASH = "\\\\".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final byte[] ESCAPED_B = "\\b".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final byte[] ESCAPED_F = "\\f".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final byte[] ESCAPED_N = "\\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final byte[] ESCAPED_R = "\\r".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final byte[] ESCAPED_T = "\\t".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private ClassicEscapedStringWriter() {}

    /** Old per-byte Latin-1 escape writer (the T1/T2 baseline). */
    public static void writeEscapedLatin1(OutputStream out, byte[] internal) throws IOException {
        int segmentStart = 0;

        for (int i = 0; i < internal.length; i++) {
            int b = internal[i] & 0xFF;
            byte[] escape = escapeBytesForAsciiControl(b);

            if (escape != null || b < 0x20 || b >= 0x80) {
                if (i > segmentStart) {
                    out.write(internal, segmentStart, i - segmentStart);
                }

                if (escape != null) {
                    out.write(escape);
                } else if (b < 0x20) {
                    out.write('\\');
                    out.write('u');
                    out.write('0');
                    out.write('0');
                    writeHexNibble(out, (b >>> 4) & 0x0F);
                    writeHexNibble(out, b & 0x0F);
                } else {
                    // Latin-1 code point to UTF-8.
                    writeUtf8CodePoint(out, b);
                }

                segmentStart = i + 1;
            }
        }

        if (segmentStart < internal.length) {
            out.write(internal, segmentStart, internal.length - segmentStart);
        }
    }

    private static byte[] escapeBytesForAsciiControl(int ch) {
        return switch (ch) {
            case '"' -> ESCAPED_QUOTE;
            case '\\' -> ESCAPED_BACKSLASH;
            case '\b' -> ESCAPED_B;
            case '\f' -> ESCAPED_F;
            case '\n' -> ESCAPED_N;
            case '\r' -> ESCAPED_R;
            case '\t' -> ESCAPED_T;
            default -> null;
        };
    }

    private static void writeHexNibble(OutputStream out, int nibble) throws IOException {
        out.write(nibble < 10 ? ('0' + nibble) : ('A' + (nibble - 10)));
    }

    private static void writeUtf8CodePoint(OutputStream out, int codePoint) throws IOException {
        if (codePoint <= 0x7F) {
            out.write(codePoint);
        } else if (codePoint <= 0x7FF) {
            out.write(0xC0 | (codePoint >> 6));
            out.write(0x80 | (codePoint & 0x3F));
        } else if (codePoint <= 0xFFFF) {
            out.write(0xE0 | (codePoint >> 12));
            out.write(0x80 | ((codePoint >> 6) & 0x3F));
            out.write(0x80 | (codePoint & 0x3F));
        } else {
            out.write(0xF0 | (codePoint >> 18));
            out.write(0x80 | ((codePoint >> 12) & 0x3F));
            out.write(0x80 | ((codePoint >> 6) & 0x3F));
            out.write(0x80 | (codePoint & 0x3F));
        }
    }
}
