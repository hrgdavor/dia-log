package hr.hrg.dialog.core;

import javax.annotation.concurrent.ThreadSafe;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * JSON-escaped string writing into a {@link ReusableByteArrayOutputStream} cursor — the
 * T4 option 2 string path used by {@code JsonLogWriter}'s whole-event direct
 * assembly.
 *
 * <p>The escaping logic is an intentional duplicate of
 * {@link EscapedJsonStringWriter}'s SWAR scan (T1/T2, ported from Apache Fory
 * commit 585eb16f, "feat(java): optimize json perf", PR #3871). It lives in
 * its own class so {@code EscapedJsonStringWriter} stays a small, stable C2
 * compilation unit: adding these cursor-form methods to that class changed
 * JIT inlining on the per-string hot path and measurably regressed it (see
 * {@code doc/perf-exploration/fory-perf-benchmark-results.md}). The duplication follows
 * the project's documented hot-path duplication policy.
 *
 * <p>Thread-safe: all state is static and immutable; the cursor itself is
 * owned by the writer (see {@link ReusableByteArrayOutputStream}).
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
    public static void writeJsonStringOrNull(ReusableByteArrayOutputStream buf, String value) {
        if (value == null) {
            buf.writeRaw(JSON_NULL, 0, JSON_NULL.length);
            return;
        }
        writeJsonString(buf, value);
    }

    /**
     * Writes a fully quoted, JSON-escaped string into the cursor. Holds the
     * cursor (pos + backing array) locally for the whole string and publishes it
     * once at the end; grow only delegates to {@code buf.grow} on the cold path.
     */
    public static void writeJsonString(ReusableByteArrayOutputStream buf, String value) {
        int pos = writeByteDirect(buf, buf.position(), '"');
        if (STRING_CODER_HANDLE != null) {
            byte[] internal = (byte[]) STRING_VALUE_HANDLE.get(value);
            if (internal != null && ((byte) STRING_CODER_HANDLE.get(value)) == 0) {
                pos = writeEscapedLatin1Cursor(buf, pos, internal);
                pos = writeByteDirect(buf, pos, '"');
                buf.setPosition(pos);
                return;
            }
        }
        pos = writeEscapedCharsCursor(buf, pos, value);
        pos = writeByteDirect(buf, pos, '"');
        buf.setPosition(pos);
    }

    /** Inline single-byte store with an inlined capacity check; returns the advanced position. */
    private static int writeByteDirect(ReusableByteArrayOutputStream c, int pos, int b) {
        byte[] buf = c.buffer();
        if (pos >= buf.length) {
            buf = c.grow(pos + 1);
        }
        buf[pos] = (byte) b;
        return pos + 1;
    }

    /** Inline bulk copy with an inlined capacity check; returns the advanced position. */
    private static int writeRawDirect(ReusableByteArrayOutputStream c, int pos, byte[] src, int off, int len) {
        byte[] buf = c.buffer();
        if (pos + len > buf.length) {
            buf = c.grow(pos + len);
        }
        System.arraycopy(src, off, buf, pos, len);
        return pos + len;
    }

    /** SWAR band scan writing straight into the cursor (Latin-1 content). */
    private static int writeEscapedLatin1Cursor(ReusableByteArrayOutputStream c, int pos, byte[] bytes) {
        int to = bytes.length;
        if (to < 8) {
            return writeEscapedLatin1PerByteCursor(c, pos, bytes, 0, to);
        }
        if (to <= 15) {
            long word = (long) LE_WORD.get(bytes, 0);
            if (isJsonAsciiWord(word) && isJsonAsciiTail(bytes, 8, to)) {
                return writeRawDirect(c, pos, bytes, 0, to);
            }
            return writeEscapedLatin1PerByteCursor(c, pos, bytes, 0, to);
        }
        if (to <= 24) {
            long w0 = (long) LE_WORD.get(bytes, 0);
            long w1 = (long) LE_WORD.get(bytes, 8);
            long w2 = (long) LE_WORD.get(bytes, to - 8);
            if (isJsonAsciiWords3(w0, w1, w2)) {
                // T2 three-word trick: 3 loads + 3 stores; the last store at
                // start + (to - 8) overlaps the first two but is byte-identical.
                byte[] buf = c.buffer();
                if (pos + to > buf.length) {
                    buf = c.grow(pos + to);
                }
                LE_WORD.set(buf, pos, w0);
                LE_WORD.set(buf, pos + 8, w1);
                LE_WORD.set(buf, pos + (to - 8), w2);
                return pos + to;
            }
            return writeEscapedLatin1PerByteCursor(c, pos, bytes, 0, to);
        }
        if (to <= 31) {
            long w0 = (long) LE_WORD.get(bytes, 0);
            long w1 = (long) LE_WORD.get(bytes, 8);
            long w2 = (long) LE_WORD.get(bytes, 16);
            long w3 = (long) LE_WORD.get(bytes, to - 8);
            if (isJsonAsciiWords4(w0, w1, w2, w3)) {
                byte[] buf = c.buffer();
                if (pos + to > buf.length) {
                    buf = c.grow(pos + to);
                }
                LE_WORD.set(buf, pos, w0);
                LE_WORD.set(buf, pos + 8, w1);
                LE_WORD.set(buf, pos + 16, w2);
                LE_WORD.set(buf, pos + (to - 8), w3);
                return pos + to;
            }
            return writeEscapedLatin1PerByteCursor(c, pos, bytes, 0, to);
        }
        int i = 0;
        for (; i + 16 <= to; i += 16) {
            long w0 = (long) LE_WORD.get(bytes, i);
            long w1 = (long) LE_WORD.get(bytes, i + 8);
            if (isJsonAsciiWords2(w0, w1)) {
                byte[] buf = c.buffer();
                if (pos + 16 > buf.length) {
                    buf = c.grow(pos + 16);
                }
                LE_WORD.set(buf, pos, w0);
                LE_WORD.set(buf, pos + 8, w1);
                pos += 16;
            } else {
                pos = writeEscapedLatin1PerByteCursor(c, pos, bytes, i, i + 16);
            }
        }
        if (i < to) {
            pos = writeEscapedLatin1PerByteCursor(c, pos, bytes, i, to);
        }
        return pos;
    }

    private static int writeEscapedLatin1PerByteCursor(ReusableByteArrayOutputStream c, int pos, byte[] bytes, int from, int to) {
        int segmentStart = from;
        for (int i = from; i < to; i++) {
            int b = bytes[i] & 0xFF;
            byte[] escape = escapeBytesForAsciiControl(b);
            if (escape != null || b < 0x20 || b >= 0x80) {
                if (i > segmentStart) {
                    pos = writeRawDirect(c, pos, bytes, segmentStart, i - segmentStart);
                }
                pos = writeEscapedByteCursor(c, pos, b);
                segmentStart = i + 1;
            }
        }
        if (segmentStart < to) {
            pos = writeRawDirect(c, pos, bytes, segmentStart, to - segmentStart);
        }
        return pos;
    }

    private static int writeEscapedByteCursor(ReusableByteArrayOutputStream c, int pos, int b) {
        switch (b) {
            case '"': pos = writeByteDirect(c, pos, '\\'); return writeByteDirect(c, pos, '"');
            case '\\': pos = writeByteDirect(c, pos, '\\'); return writeByteDirect(c, pos, '\\');
            case '\b': pos = writeByteDirect(c, pos, '\\'); return writeByteDirect(c, pos, 'b');
            case '\f': pos = writeByteDirect(c, pos, '\\'); return writeByteDirect(c, pos, 'f');
            case '\n': pos = writeByteDirect(c, pos, '\\'); return writeByteDirect(c, pos, 'n');
            case '\r': pos = writeByteDirect(c, pos, '\\'); return writeByteDirect(c, pos, 'r');
            case '\t': pos = writeByteDirect(c, pos, '\\'); return writeByteDirect(c, pos, 't');
            default:
                if (b < 0x20) {
                    pos = writeByteDirect(c, pos, '\\');
                    pos = writeByteDirect(c, pos, 'u');
                    pos = writeByteDirect(c, pos, '0');
                    pos = writeByteDirect(c, pos, '0');
                    pos = writeByteDirect(c, pos, HEX_DIGITS[b >>> 4]);
                    return writeByteDirect(c, pos, HEX_DIGITS[b & 0xF]);
                } else {
                    // Latin-1 code point to 2-byte UTF-8.
                    pos = writeByteDirect(c, pos, 0xC0 | (b >> 6));
                    return writeByteDirect(c, pos, 0x80 | (b & 0x3F));
                }
        }
    }

    /** Char-scanning UTF-8 writer for UTF-16 content or when add-opens is unavailable. */
    private static int writeEscapedCharsCursor(ReusableByteArrayOutputStream c, int pos, String value) {
        int len = value.length();
        for (int i = 0; i < len; i++) {
            char ch = value.charAt(i);
            byte[] escape = escapeBytesForAsciiControl(ch);
            if (escape != null) {
                pos = writeRawDirect(c, pos, escape, 0, escape.length);
            } else if (ch < 0x20) {
                pos = writeByteDirect(c, pos, '\\');
                pos = writeByteDirect(c, pos, 'u');
                pos = writeByteDirect(c, pos, '0');
                pos = writeByteDirect(c, pos, '0');
                pos = writeByteDirect(c, pos, HEX_DIGITS[(ch >>> 4) & 0xF]);
                pos = writeByteDirect(c, pos, HEX_DIGITS[ch & 0xF]);
            } else if (ch <= 0x7F) {
                pos = writeByteDirect(c, pos, ch);
            } else if (ch <= 0x7FF) {
                pos = writeByteDirect(c, pos, 0xC0 | (ch >> 6));
                pos = writeByteDirect(c, pos, 0x80 | (ch & 0x3F));
            } else if (Character.isHighSurrogate(ch) && i + 1 < len && Character.isLowSurrogate(value.charAt(i + 1))) {
                int codePoint = Character.toCodePoint(ch, value.charAt(i + 1));
                pos = writeByteDirect(c, pos, 0xF0 | (codePoint >> 18));
                pos = writeByteDirect(c, pos, 0x80 | ((codePoint >> 12) & 0x3F));
                pos = writeByteDirect(c, pos, 0x80 | ((codePoint >> 6) & 0x3F));
                pos = writeByteDirect(c, pos, 0x80 | (codePoint & 0x3F));
                i++;
            } else if (Character.isHighSurrogate(ch) || Character.isLowSurrogate(ch)) {
                // Lone surrogate → U+FFFD, matching the classic path.
                pos = writeByteDirect(c, pos, 0xEF);
                pos = writeByteDirect(c, pos, 0xBF);
                pos = writeByteDirect(c, pos, 0xBD);
            } else {
                pos = writeByteDirect(c, pos, 0xE0 | (ch >> 12));
                pos = writeByteDirect(c, pos, 0x80 | ((ch >> 6) & 0x3F));
                pos = writeByteDirect(c, pos, 0x80 | (ch & 0x3F));
            }
        }
        return pos;
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
