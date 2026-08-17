package hr.hrg.dialog.core;

import javax.annotation.concurrent.ThreadSafe;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.charset.StandardCharsets;

/**
 * Writes JSON strings with mandatory escaping (quote, backslash, control chars,
 * {@code \u00XX} form, non-ASCII as UTF-8). Uses the {@code VarHandle} fast path
 * when {@code --add-opens java.base/java.lang=ALL-UNNAMED} is available and falls
 * back to a char-scanning classic path otherwise. Thread-safe.
 */
@ThreadSafe
public final class EscapedJsonStringWriter {

    @FunctionalInterface
    private interface EscapedStringWriter {
        void write(OutputStream out, String value) throws IOException;
    }

    private static final StringByteExtractor.ByteWriter STRING_STRATEGY = StringByteExtractor.getStrategy();
    private static final EscapedStringWriter ESCAPED_STRING_STRATEGY = StrategyHolder.ACTIVE_STRATEGY;

    private static final byte[] JSON_NULL = "null".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ESCAPED_QUOTE = "\\\"".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ESCAPED_BACKSLASH = "\\\\".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ESCAPED_B = "\\b".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ESCAPED_F = "\\f".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ESCAPED_N = "\\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ESCAPED_R = "\\r".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ESCAPED_T = "\\t".getBytes(StandardCharsets.UTF_8);

    private static final class StrategyHolder {
        static final EscapedStringWriter ACTIVE_STRATEGY;

        static {
            EscapedStringWriter strategy = EscapedJsonStringWriter::writeEscapedJsonStringClassic;

            try {
                MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(String.class, MethodHandles.lookup());
                VarHandle valueHandle = lookup.findVarHandle(String.class, "value", byte[].class);
                VarHandle coderHandle = lookup.findVarHandle(String.class, "coder", byte.class);

                // Validate handles before activating fast path.
                String probe = "test";
                byte coder = (byte) coderHandle.get(probe);
                byte[] internal = (byte[]) valueHandle.get(probe);

                if (internal != null && (coder == 0 || coder == 1)) {
                    strategy = (out, value) -> writeEscapedJsonStringVarHandle(out, value, valueHandle, coderHandle);
                }
            } catch (Throwable ignored) {
                // Fallback stays active when add-opens is unavailable.
            }

            ACTIVE_STRATEGY = strategy;
        }
    }

    private EscapedJsonStringWriter() {}

    public static void writeJsonStringOrNull(OutputStream out, String value) throws IOException {
        if (value == null) {
            out.write(JSON_NULL);
            return;
        }
        ESCAPED_STRING_STRATEGY.write(out, value);
    }

    private static void writeEscapedJsonStringVarHandle(OutputStream out, String value, VarHandle valueHandle, VarHandle coderHandle) throws IOException {
        out.write('"');

        byte coder = (byte) coderHandle.get(value);
        byte[] internal = (byte[]) valueHandle.get(value);

        if (internal == null) {
            out.write('"');
            return;
        }

        if (coder == 0) {
            writeEscapedLatin1(out, internal);
        } else {
            writeEscapedUtf16(out, internal);
        }

        out.write('"');
    }

    private static void writeEscapedJsonStringClassic(OutputStream out, String value) throws IOException {
        out.write('"');

        int start = 0;
        int len = value.length();
        for (int i = 0; i < len; i++) {
            char ch = value.charAt(i);

            byte[] escape = switch (ch) {
                case '"' -> ESCAPED_QUOTE;
                case '\\' -> ESCAPED_BACKSLASH;
                case '\b' -> ESCAPED_B;
                case '\f' -> ESCAPED_F;
                case '\n' -> ESCAPED_N;
                case '\r' -> ESCAPED_R;
                case '\t' -> ESCAPED_T;
                default -> null;
            };

            if (escape != null || ch < 0x20) {
                if (i > start) {
                    writeUtf8Range(out, value, start, i);
                }

                if (escape != null) {
                    out.write(escape);
                } else {
                    // Control chars not covered above use \\u00XX form.
                    out.write('\\');
                    out.write('u');
                    out.write('0');
                    out.write('0');
                    writeHexNibble(out, (ch >>> 4) & 0x0F);
                    writeHexNibble(out, ch & 0x0F);
                }

                start = i + 1;
            }
        }

        if (start < len) {
            writeUtf8Range(out, value, start, len);
        }

        out.write('"');
    }

    private static void writeEscapedLatin1(OutputStream out, byte[] internal) throws IOException {
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

    private static void writeEscapedUtf16(OutputStream out, byte[] internal) throws IOException {
        int charLen = internal.length >>> 1;

        for (int i = 0; i < charLen; i++) {
            int pos = i << 1;
            char ch = (char) (((internal[pos] & 0xFF) << 8) | (internal[pos + 1] & 0xFF));

            byte[] escape = escapeBytesForAsciiControl(ch);
            if (escape != null) {
                out.write(escape);
                continue;
            }

            if (ch < 0x20) {
                out.write('\\');
                out.write('u');
                out.write('0');
                out.write('0');
                writeHexNibble(out, (ch >>> 4) & 0x0F);
                writeHexNibble(out, ch & 0x0F);
                continue;
            }

            if (ch <= 0x7F) {
                out.write(ch);
                continue;
            }

            if (Character.isHighSurrogate(ch)) {
                if (i + 1 < charLen) {
                    int lowPos = (i + 1) << 1;
                    char low = (char) (((internal[lowPos] & 0xFF) << 8) | (internal[lowPos + 1] & 0xFF));
                    if (Character.isLowSurrogate(low)) {
                        int codePoint = Character.toCodePoint(ch, low);
                        writeUtf8CodePoint(out, codePoint);
                        i++;
                        continue;
                    }
                }
                writeReplacementChar(out);
                continue;
            }

            if (Character.isLowSurrogate(ch)) {
                writeReplacementChar(out);
                continue;
            }

            writeUtf8CodePoint(out, ch);
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

    private static void writeUtf8Range(OutputStream out, String value, int start, int end) throws IOException {
        for (int i = start; i < end; i++) {
            char ch = value.charAt(i);

            if (ch <= 0x7F) {
                out.write(ch);
            } else if (ch <= 0x7FF) {
                out.write(0xC0 | (ch >> 6));
                out.write(0x80 | (ch & 0x3F));
            } else if (Character.isHighSurrogate(ch)) {
                if (i + 1 < end) {
                    char low = value.charAt(i + 1);
                    if (Character.isLowSurrogate(low)) {
                        int codePoint = Character.toCodePoint(ch, low);
                        out.write(0xF0 | (codePoint >> 18));
                        out.write(0x80 | ((codePoint >> 12) & 0x3F));
                        out.write(0x80 | ((codePoint >> 6) & 0x3F));
                        out.write(0x80 | (codePoint & 0x3F));
                        i++;
                        continue;
                    }
                }
                writeReplacementChar(out);
            } else if (Character.isLowSurrogate(ch)) {
                writeReplacementChar(out);
            } else {
                out.write(0xE0 | (ch >> 12));
                out.write(0x80 | ((ch >> 6) & 0x3F));
                out.write(0x80 | (ch & 0x3F));
            }
        }
    }

    private static void writeReplacementChar(OutputStream out) throws IOException {
        out.write(0xEF);
        out.write(0xBF);
        out.write(0xBD);
    }
}
