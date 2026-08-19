package hr.hrg.dialog.core;

import javax.annotation.concurrent.ThreadSafe;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * JSON-escaped string writing into a {@link DirectJsonBuffer} cursor — the
 * T4 option 2 string path used by {@code JsonLogWriter}'s whole-event direct
 * assembly.
 *
 * <p>The escaping logic is an intentional duplicate of
 * {@link EscapedJsonStringWriter}'s SWAR scan (T1/T2, ported from Apache Fory
 * commit 585eb16f, "feat(java): optimize json perf", PR #3871). It lives in
 * its own class so {@code EscapedJsonStringWriter} stays a small, stable C2
 * compilation unit: adding these cursor-form methods to that class changed
 * JIT inlining on the per-string hot path and measurably regressed it (see
 * {@code doc/perf/fory-perf-benchmark-results.md}). The duplication follows
 * the project's documented hot-path duplication policy.
 *
 * <p>Thread-safe: all state is static and immutable; the cursor itself is
 * owned by the writer (see {@link DirectJsonBuffer}).
 */
@ThreadSafe
public final class DirectJsonStringWriter {

    private static final byte[] JSON_NULL = "null".getBytes(StandardCharsets.UTF_8);

    // =========================================================================
    // SWAR word scanning (T1/T2) — same predicates and constants as
    // EscapedJsonStringWriter (see its header for the math).
    // =========================================================================

    private static final long HIGH_BITS = 0x8080808080808080L;
    private static final int INT_HIGH_BITS = 0x80808080;
    private static final int SHORT_HIGH_BITS = 0x8080;
    private static final long ASCII_CONTROL_OFFSET = 0x6060606060606060L;
    private static final int INT_ASCII_CONTROL_OFFSET = 0x60606060;
    private static final int SHORT_ASCII_CONTROL_OFFSET = 0x6060;
    private static final long ASCII_GT_QUOTE_OFFSET = 0x5D5D5D5D5D5D5D5DL;
    private static final int INT_ASCII_GT_QUOTE_OFFSET = 0x5D5D5D5D;
    private static final int SHORT_ASCII_GT_QUOTE_OFFSET = 0x5D5D;
    private static final long ONE_BYTES = 0x0101010101010101L;
    private static final int INT_ONE_BYTES = 0x01010101;
    private static final int SHORT_ONE_BYTES = 0x0101;
    private static final long QUOTE_BYTES_COMPLEMENT = ~0x2222222222222222L;
    private static final int INT_QUOTE_BYTES_COMPLEMENT = ~0x22222222;
    private static final int SHORT_QUOTE_BYTES_COMPLEMENT = ~0x2222;
    private static final long BACKSLASH_BYTES_COMPLEMENT = ~0x5C5C5C5C5C5C5C5CL;
    private static final int INT_BACKSLASH_BYTES_COMPLEMENT = ~0x5C5C5C5C;
    private static final int SHORT_BACKSLASH_BYTES_COMPLEMENT = ~0x5C5C;

    private static final VarHandle LE_WORD =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle LE_WORD4 =
            MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle LE_WORD2 =
            MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.LITTLE_ENDIAN);

    private static final byte[] HEX_DIGITS = "0123456789ABCDEF".getBytes(StandardCharsets.US_ASCII);

    // Zero-copy String access handles; non-null only with --add-opens.
    private static final VarHandle STRING_VALUE_HANDLE;
    private static final VarHandle STRING_CODER_HANDLE;

    static {
        VarHandle valueHandle = null;
        VarHandle coderHandle = null;
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(String.class, MethodHandles.lookup());
            valueHandle = lookup.findVarHandle(String.class, "value", byte[].class);
            coderHandle = lookup.findVarHandle(String.class, "coder", byte.class);

            String probe = "test";
            byte coder = (byte) coderHandle.get(probe);
            byte[] internal = (byte[]) valueHandle.get(probe);

            if (internal == null || (coder != 0 && coder != 1)) {
                valueHandle = null;
                coderHandle = null;
            }
        } catch (Throwable ignored) {
            // Fallback stays active when add-opens is unavailable.
        }
        STRING_VALUE_HANDLE = valueHandle;
        STRING_CODER_HANDLE = coderHandle;
    }

    private DirectJsonStringWriter() {}

    /** Writes a fully quoted JSON string (or {@code null} literal) into the cursor. */
    public static void writeJsonStringOrNull(DirectJsonBuffer buf, String value) {
        if (value == null) {
            buf.writeRaw(JSON_NULL, 0, JSON_NULL.length);
            return;
        }
        writeJsonString(buf, value);
    }

    /** Writes a fully quoted, JSON-escaped string into the cursor. */
    public static void writeJsonString(DirectJsonBuffer buf, String value) {
        buf.writeByte('"');
        if (STRING_CODER_HANDLE != null) {
            byte[] internal = (byte[]) STRING_VALUE_HANDLE.get(value);
            if (internal != null && ((byte) STRING_CODER_HANDLE.get(value)) == 0) {
                writeEscapedLatin1Cursor(buf, internal);
                buf.writeByte('"');
                return;
            }
        }
        writeEscapedCharsCursor(buf, value);
        buf.writeByte('"');
    }

    /** SWAR band scan writing straight into the cursor (Latin-1 content). */
    private static void writeEscapedLatin1Cursor(DirectJsonBuffer c, byte[] bytes) {
        int to = bytes.length;
        if (to < 8) {
            writeEscapedLatin1PerByteCursor(c, bytes, 0, to);
            return;
        }
        if (to <= 15) {
            long word = (long) LE_WORD.get(bytes, 0);
            if (isJsonAsciiWord(word) && isJsonAsciiTail(bytes, 8, to)) {
                c.writeRaw(bytes, 0, to);
                return;
            }
            writeEscapedLatin1PerByteCursor(c, bytes, 0, to);
            return;
        }
        if (to <= 24) {
            long w0 = (long) LE_WORD.get(bytes, 0);
            long w1 = (long) LE_WORD.get(bytes, 8);
            long w2 = (long) LE_WORD.get(bytes, to - 8);
            if (isJsonAsciiWords3(w0, w1, w2)) {
                // T2 three-word trick: 3 loads + 3 stores; the last store at
                // start + (to - 8) overlaps the first two but is byte-identical.
                c.ensure(to);
                int start = c.position();
                c.putPackedLE(start, w0, 8);
                c.putPackedLE(start + 8, w1, 8);
                c.putPackedLE(start + (to - 8), w2, 8);
                c.advance(to);
                return;
            }
            writeEscapedLatin1PerByteCursor(c, bytes, 0, to);
            return;
        }
        if (to <= 31) {
            long w0 = (long) LE_WORD.get(bytes, 0);
            long w1 = (long) LE_WORD.get(bytes, 8);
            long w2 = (long) LE_WORD.get(bytes, 16);
            long w3 = (long) LE_WORD.get(bytes, to - 8);
            if (isJsonAsciiWords4(w0, w1, w2, w3)) {
                c.ensure(to);
                int start = c.position();
                c.putPackedLE(start, w0, 8);
                c.putPackedLE(start + 8, w1, 8);
                c.putPackedLE(start + 16, w2, 8);
                c.putPackedLE(start + (to - 8), w3, 8);
                c.advance(to);
                return;
            }
            writeEscapedLatin1PerByteCursor(c, bytes, 0, to);
            return;
        }
        int i = 0;
        for (; i + 16 <= to; i += 16) {
            long w0 = (long) LE_WORD.get(bytes, i);
            long w1 = (long) LE_WORD.get(bytes, i + 8);
            if (isJsonAsciiWords2(w0, w1)) {
                c.ensure(16);
                c.writePackedLE(w0, 8);
                c.writePackedLE(w1, 8);
            } else {
                writeEscapedLatin1PerByteCursor(c, bytes, i, i + 16);
            }
        }
        if (i < to) {
            writeEscapedLatin1PerByteCursor(c, bytes, i, to);
        }
    }

    private static void writeEscapedLatin1PerByteCursor(DirectJsonBuffer c, byte[] bytes, int from, int to) {
        int segmentStart = from;
        for (int i = from; i < to; i++) {
            int b = bytes[i] & 0xFF;
            byte[] escape = escapeBytesForAsciiControl(b);
            if (escape != null || b < 0x20 || b >= 0x80) {
                if (i > segmentStart) {
                    c.writeRaw(bytes, segmentStart, i - segmentStart);
                }
                writeEscapedByteCursor(c, b);
                segmentStart = i + 1;
            }
        }
        if (segmentStart < to) {
            c.writeRaw(bytes, segmentStart, to - segmentStart);
        }
    }

    private static void writeEscapedByteCursor(DirectJsonBuffer c, int b) {
        switch (b) {
            case '"': c.writeByte('\\'); c.writeByte('"'); return;
            case '\\': c.writeByte('\\'); c.writeByte('\\'); return;
            case '\b': c.writeByte('\\'); c.writeByte('b'); return;
            case '\f': c.writeByte('\\'); c.writeByte('f'); return;
            case '\n': c.writeByte('\\'); c.writeByte('n'); return;
            case '\r': c.writeByte('\\'); c.writeByte('r'); return;
            case '\t': c.writeByte('\\'); c.writeByte('t'); return;
            default:
                if (b < 0x20) {
                    c.writeByte('\\');
                    c.writeByte('u');
                    c.writeByte('0');
                    c.writeByte('0');
                    c.writeByte(HEX_DIGITS[b >>> 4]);
                    c.writeByte(HEX_DIGITS[b & 0xF]);
                } else {
                    // Latin-1 code point to 2-byte UTF-8.
                    c.writeByte(0xC0 | (b >> 6));
                    c.writeByte(0x80 | (b & 0x3F));
                }
        }
    }

    /** Char-scanning UTF-8 writer for UTF-16 content or when add-opens is unavailable. */
    private static void writeEscapedCharsCursor(DirectJsonBuffer c, String value) {
        int len = value.length();
        for (int i = 0; i < len; i++) {
            char ch = value.charAt(i);
            byte[] escape = escapeBytesForAsciiControl(ch);
            if (escape != null) {
                c.writeRaw(escape, 0, escape.length);
            } else if (ch < 0x20) {
                c.writeByte('\\');
                c.writeByte('u');
                c.writeByte('0');
                c.writeByte('0');
                c.writeByte(HEX_DIGITS[(ch >>> 4) & 0xF]);
                c.writeByte(HEX_DIGITS[ch & 0xF]);
            } else if (ch <= 0x7F) {
                c.writeByte(ch);
            } else if (ch <= 0x7FF) {
                c.ensure(2);
                c.writeByte(0xC0 | (ch >> 6));
                c.writeByte(0x80 | (ch & 0x3F));
            } else if (Character.isHighSurrogate(ch) && i + 1 < len && Character.isLowSurrogate(value.charAt(i + 1))) {
                int codePoint = Character.toCodePoint(ch, value.charAt(i + 1));
                c.ensure(4);
                c.writeByte(0xF0 | (codePoint >> 18));
                c.writeByte(0x80 | ((codePoint >> 12) & 0x3F));
                c.writeByte(0x80 | ((codePoint >> 6) & 0x3F));
                c.writeByte(0x80 | (codePoint & 0x3F));
                i++;
            } else if (Character.isHighSurrogate(ch) || Character.isLowSurrogate(ch)) {
                // Lone surrogate → U+FFFD, matching the classic path.
                c.ensure(3);
                c.writeByte(0xEF);
                c.writeByte(0xBF);
                c.writeByte(0xBD);
            } else {
                c.ensure(3);
                c.writeByte(0xE0 | (ch >> 12));
                c.writeByte(0x80 | ((ch >> 6) & 0x3F));
                c.writeByte(0x80 | (ch & 0x3F));
            }
        }
    }

    // ---- escape byte table (shared with the char-scan path) ----------------

    private static final byte[] ESCAPED_QUOTE = "\\\"".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ESCAPED_BACKSLASH = "\\\\".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ESCAPED_B = "\\b".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ESCAPED_F = "\\f".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ESCAPED_N = "\\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ESCAPED_R = "\\r".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ESCAPED_T = "\\t".getBytes(StandardCharsets.UTF_8);

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

    // ---- SWAR predicates (duplicated from EscapedJsonStringWriter) ---------

    private static boolean isJsonAsciiWord(long word) {
        long notBackslashMask = ((word ^ BACKSLASH_BYTES_COMPLEMENT) + ONE_BYTES) & HIGH_BITS;
        if ((notBackslashMask & (word + ASCII_GT_QUOTE_OFFSET)) == HIGH_BITS) {
            return true;
        }
        return isJsonAsciiWordFallback(word);
    }

    private static boolean isJsonAsciiWordFallback(long word) {
        long notBackslashMask = ((word ^ BACKSLASH_BYTES_COMPLEMENT) + ONE_BYTES) & HIGH_BITS;
        return (((word + ASCII_CONTROL_OFFSET) & ~word) & HIGH_BITS) == HIGH_BITS
            && (((word ^ QUOTE_BYTES_COMPLEMENT) + ONE_BYTES) & HIGH_BITS) == HIGH_BITS
            && notBackslashMask == HIGH_BITS;
    }

    private static boolean isJsonAsciiWords2(long w0, long w1) {
        long notBackslashMask =
            ((w0 ^ BACKSLASH_BYTES_COMPLEMENT) + ONE_BYTES)
                & ((w1 ^ BACKSLASH_BYTES_COMPLEMENT) + ONE_BYTES)
                & HIGH_BITS;
        if ((notBackslashMask & (w0 + ASCII_GT_QUOTE_OFFSET) & (w1 + ASCII_GT_QUOTE_OFFSET))
            == HIGH_BITS) {
            return true;
        }
        return isJsonAsciiWordsFallback(w0, w1, notBackslashMask);
    }

    private static boolean isJsonAsciiWordsFallback(long w0, long w1, long notBackslashMask) {
        return ((w0 + ASCII_CONTROL_OFFSET)
                & (w1 + ASCII_CONTROL_OFFSET)
                & ((w0 ^ QUOTE_BYTES_COMPLEMENT) + ONE_BYTES)
                & ((w1 ^ QUOTE_BYTES_COMPLEMENT) + ONE_BYTES)
                & notBackslashMask)
            == HIGH_BITS;
    }

    private static boolean isJsonAsciiWords3(long w0, long w1, long w2) {
        long notBackslashMask =
            ((w0 ^ BACKSLASH_BYTES_COMPLEMENT) + ONE_BYTES)
                & ((w1 ^ BACKSLASH_BYTES_COMPLEMENT) + ONE_BYTES)
                & ((w2 ^ BACKSLASH_BYTES_COMPLEMENT) + ONE_BYTES)
                & HIGH_BITS;
        if ((notBackslashMask
                & (w0 + ASCII_GT_QUOTE_OFFSET)
                & (w1 + ASCII_GT_QUOTE_OFFSET)
                & (w2 + ASCII_GT_QUOTE_OFFSET))
            == HIGH_BITS) {
            return true;
        }
        return isJsonAsciiWordsFallback(w0, w1, w2, notBackslashMask);
    }

    private static boolean isJsonAsciiWordsFallback(long w0, long w1, long w2, long notBackslashMask) {
        return ((w0 + ASCII_CONTROL_OFFSET)
                & (w1 + ASCII_CONTROL_OFFSET)
                & (w2 + ASCII_CONTROL_OFFSET)
                & ((w0 ^ QUOTE_BYTES_COMPLEMENT) + ONE_BYTES)
                & ((w1 ^ QUOTE_BYTES_COMPLEMENT) + ONE_BYTES)
                & ((w2 ^ QUOTE_BYTES_COMPLEMENT) + ONE_BYTES)
                & notBackslashMask)
            == HIGH_BITS;
    }

    private static boolean isJsonAsciiWords4(long w0, long w1, long w2, long w3) {
        long notBackslashMask =
            ((w0 ^ BACKSLASH_BYTES_COMPLEMENT) + ONE_BYTES)
                & ((w1 ^ BACKSLASH_BYTES_COMPLEMENT) + ONE_BYTES)
                & ((w2 ^ BACKSLASH_BYTES_COMPLEMENT) + ONE_BYTES)
                & ((w3 ^ BACKSLASH_BYTES_COMPLEMENT) + ONE_BYTES)
                & HIGH_BITS;
        if ((notBackslashMask
                & (w0 + ASCII_GT_QUOTE_OFFSET)
                & (w1 + ASCII_GT_QUOTE_OFFSET)
                & (w2 + ASCII_GT_QUOTE_OFFSET)
                & (w3 + ASCII_GT_QUOTE_OFFSET))
            == HIGH_BITS) {
            return true;
        }
        return isJsonAsciiWordsFallback(w0, w1, w2, w3, notBackslashMask);
    }

    private static boolean isJsonAsciiWordsFallback(
            long w0, long w1, long w2, long w3, long notBackslashMask) {
        return ((w0 + ASCII_CONTROL_OFFSET)
                & (w1 + ASCII_CONTROL_OFFSET)
                & (w2 + ASCII_CONTROL_OFFSET)
                & (w3 + ASCII_CONTROL_OFFSET)
                & ((w0 ^ QUOTE_BYTES_COMPLEMENT) + ONE_BYTES)
                & ((w1 ^ QUOTE_BYTES_COMPLEMENT) + ONE_BYTES)
                & ((w2 ^ QUOTE_BYTES_COMPLEMENT) + ONE_BYTES)
                & ((w3 ^ QUOTE_BYTES_COMPLEMENT) + ONE_BYTES)
                & notBackslashMask)
            == HIGH_BITS;
    }

    private static boolean isJsonAsciiInt(int word) {
        int notBackslashMask =
            ((word ^ INT_BACKSLASH_BYTES_COMPLEMENT) + INT_ONE_BYTES) & INT_HIGH_BITS;
        if ((notBackslashMask & (word + INT_ASCII_GT_QUOTE_OFFSET)) == INT_HIGH_BITS) {
            return true;
        }
        return isJsonAsciiIntFallback(word, notBackslashMask);
    }

    private static boolean isJsonAsciiIntFallback(int word, int notBackslashMask) {
        return (((word + INT_ASCII_CONTROL_OFFSET) & ~word) & INT_HIGH_BITS) == INT_HIGH_BITS
            && (((word ^ INT_QUOTE_BYTES_COMPLEMENT) + INT_ONE_BYTES) & INT_HIGH_BITS) == INT_HIGH_BITS
            && notBackslashMask == INT_HIGH_BITS;
    }

    private static boolean isJsonAsciiShort(int word) {
        int notBackslashMask =
            ((word ^ SHORT_BACKSLASH_BYTES_COMPLEMENT) + SHORT_ONE_BYTES) & SHORT_HIGH_BITS;
        if ((notBackslashMask & (word + SHORT_ASCII_GT_QUOTE_OFFSET)) == SHORT_HIGH_BITS) {
            return true;
        }
        return isJsonAsciiShortFallback(word, notBackslashMask);
    }

    private static boolean isJsonAsciiShortFallback(int word, int notBackslashMask) {
        return (((word + SHORT_ASCII_CONTROL_OFFSET) & ~word) & SHORT_HIGH_BITS) == SHORT_HIGH_BITS
            && (((word ^ SHORT_QUOTE_BYTES_COMPLEMENT) + SHORT_ONE_BYTES) & SHORT_HIGH_BITS)
                == SHORT_HIGH_BITS
            && notBackslashMask == SHORT_HIGH_BITS;
    }

    /** Validates the 4/2/1-byte tail of an 8..15 byte string (word at 0 covers 0..7). */
    private static boolean isJsonAsciiTail(byte[] bytes, int from, int to) {
        int i = from;
        if (i + 4 <= to) {
            if (!isJsonAsciiInt((int) LE_WORD4.get(bytes, i))) {
                return false;
            }
            i += 4;
        }
        if (i + 2 <= to) {
            if (!isJsonAsciiShort((int) LE_WORD2.get(bytes, i))) {
                return false;
            }
            i += 2;
        }
        if (i < to) {
            if (!isJsonAsciiByte(bytes[i])) {
                return false;
            }
        }
        return true;
    }

    private static boolean isJsonAsciiByte(byte value) {
        int ch = value & 0xff;
        return ch > 0x1F && ch < 0x80 && ch != '"' && ch != '\\';
    }
}
