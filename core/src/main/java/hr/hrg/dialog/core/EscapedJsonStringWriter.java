package hr.hrg.dialog.core;

import javax.annotation.concurrent.ThreadSafe;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Writes JSON strings with mandatory escaping (quote, backslash, control
 * characters below 0x20 written as U+XXXX escapes, non-ASCII as UTF-8). Uses the
 * {@code VarHandle} fast path when {@code --add-opens java.base/java.lang=ALL-UNNAMED}
 * is available and falls back to a char-scanning classic path otherwise. Thread-safe.
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

    // =========================================================================
    // SWAR word scanning (T1/T2) — ported from Apache Fory commit 585eb16f
    // ("feat(java): optimize json perf", PR #3871), Utf8JsonWriter.
    //
    // 8 Latin-1 bytes are loaded as one little-endian long and classified with a
    // few integer ops. The fast path accepts bytes in (0x22, 0x5C) ∪ (0x5C, 0x80)
    // (printable ASCII strictly above '"' and not '\\') with NO false negatives;
    // every byte that needs escaping — '"' 0x22, '\\' 0x5C, control < 0x20,
    // non-ASCII >= 0x80 — fails it and reaches the exact fallback. Math:
    //   b + 0x5D >= 0x80  <=>  b >= 0x23 (for b <= 0x7F)            -> "gt quote"
    //   (b ^ ~0x5C) + 1 = -(b ^ 0x5C), high bit set <=> b != 0x5C   -> backslash mask
    //   fallback: (b + 0x60) & ~b sets high bit <=> b in [0x20,0x7F],
    //   quote term rejects exactly b == 0x22, mask rejects b == 0x5C.
    // The load uses byte-array view VarHandles (no --add-opens needed) and is
    // byte-order portable: byte i always lands in bits 8*i..8*i+7.
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
        byte coder = (byte) coderHandle.get(value);
        byte[] internal = (byte[]) valueHandle.get(value);

        if (internal == null) {
            out.write('"');
            out.write('"');
            return;
        }

        if (coder == 0) {
            // Latin-1 is 1 byte per char — byte-order independent, safe on all JDKs.
            out.write('"');
            writeEscapedLatin1(out, internal);
            out.write('"');
        } else {
            // The internal UTF-16 byte order changed across JDK versions (big-endian
            // before 25, little-endian on 25+), so reading the raw bytes is not
            // portable. Use the JDK-version-independent char-scanning path instead
            // (it writes its own surrounding quotes).
            writeEscapedJsonStringClassic(out, value);
        }
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

    /**
     * Writes a Latin-1 byte array with mandatory JSON escaping (quote, backslash,
     * control chars below 0x20 as U+XXXX, 0x80..0xFF as 2-byte UTF-8).
     * <p>
     * T1: 8-byte SWAR words are tested with one predicate; clean words (the
     * dominant case for log content) are emitted as one bulk chunk. T2: length
     * bands specialize the scan — one word (8..15), three overlapping words
     * (16..24), four words (25..31), 16-byte block loop (>= 32). T4: when
     * {@code out} is a {@link ReusableByteArrayOutputStream}, clean words are
     * stored straight into its backing array through the direct-buffer API.
     */
    private static void writeEscapedLatin1(OutputStream out, byte[] internal) throws IOException {
        if (out instanceof ReusableByteArrayOutputStream rbo) {
            writeEscapedLatin1Direct(rbo, internal);
            return;
        }
        writeEscapedLatin1Stream(out, internal, 0, internal.length);
    }

    // ---- stream mode ------------------------------------------------------

    private static void writeEscapedLatin1Stream(OutputStream out, byte[] bytes, int from, int to) throws IOException {
        int length = to - from;
        if (length < 8) {
            writeEscapedLatin1PerByte(out, bytes, from, to);
            return;
        }
        if (length <= 15) {
            long word = (long) LE_WORD.get(bytes, from);
            if (isJsonAsciiWord(word) && isJsonAsciiTail(bytes, from + 8, to)) {
                out.write(bytes, from, length);
                return;
            }
            writeEscapedLatin1PerByte(out, bytes, from, to);
            return;
        }
        if (length <= 24) {
            long w0 = (long) LE_WORD.get(bytes, from);
            long w1 = (long) LE_WORD.get(bytes, from + 8);
            long w2 = (long) LE_WORD.get(bytes, to - 8);
            if (isJsonAsciiWords3(w0, w1, w2)) {
                out.write(bytes, from, length);
                return;
            }
            writeEscapedLatin1PerByte(out, bytes, from, to);
            return;
        }
        if (length <= 31) {
            long w0 = (long) LE_WORD.get(bytes, from);
            long w1 = (long) LE_WORD.get(bytes, from + 8);
            long w2 = (long) LE_WORD.get(bytes, from + 16);
            long w3 = (long) LE_WORD.get(bytes, to - 8);
            if (isJsonAsciiWords4(w0, w1, w2, w3)) {
                out.write(bytes, from, length);
                return;
            }
            writeEscapedLatin1PerByte(out, bytes, from, to);
            return;
        }
        // >= 32 bytes: 16-byte block loop; the < 16 tail re-enters the short bands.
        int segmentStart = from;
        int i = from;
        int blockEnd = to - 15;
        for (; i < blockEnd; i += 16) {
            long w0 = (long) LE_WORD.get(bytes, i);
            long w1 = (long) LE_WORD.get(bytes, i + 8);
            if (isJsonAsciiWords2(w0, w1)) {
                continue;
            }
            if (i > segmentStart) {
                out.write(bytes, segmentStart, i - segmentStart);
            }
            writeEscapedLatin1PerByte(out, bytes, i, i + 16);
            segmentStart = i + 16;
        }
        // Flush any pending clean segment (possibly the whole tail when the
        // block loop consumed every byte), then let the short bands handle the
        // remaining < 16 tail if any.
        if (i > segmentStart) {
            out.write(bytes, segmentStart, i - segmentStart);
        }
        if (i < to) {
            writeEscapedLatin1Stream(out, bytes, i, to);
        }
    }

    private static void writeEscapedLatin1PerByte(OutputStream out, byte[] bytes, int from, int to) throws IOException {
        int segmentStart = from;
        for (int i = from; i < to; i++) {
            int b = bytes[i] & 0xFF;
            byte[] escape = escapeBytesForAsciiControl(b);
            if (escape != null || b < 0x20 || b >= 0x80) {
                if (i > segmentStart) {
                    out.write(bytes, segmentStart, i - segmentStart);
                }
                writeEscapedByteStream(out, b);
                segmentStart = i + 1;
            }
        }
        if (segmentStart < to) {
            out.write(bytes, segmentStart, to - segmentStart);
        }
    }

    private static void writeEscapedByteStream(OutputStream out, int b) throws IOException {
        byte[] escape = escapeBytesForAsciiControl(b);
        if (escape != null) {
            out.write(escape);
            return;
        }
        if (b < 0x20) {
            // Control chars not covered above use \\u00XX form.
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
    }

    // ---- direct-buffer mode (T4) ------------------------------------------

    private static void writeEscapedLatin1Direct(ReusableByteArrayOutputStream rbo, byte[] bytes) throws IOException {
        int to = bytes.length;
        if (to < 8) {
            writeEscapedLatin1PerByteDirect(rbo, bytes, 0, to);
            return;
        }
        if (to <= 15) {
            long word = (long) LE_WORD.get(bytes, 0);
            if (isJsonAsciiWord(word) && isJsonAsciiTail(bytes, 8, to)) {
                copyDirect(rbo, bytes, 0, to);
                return;
            }
            writeEscapedLatin1PerByteDirect(rbo, bytes, 0, to);
            return;
        }
        if (to <= 24) {
            long w0 = (long) LE_WORD.get(bytes, 0);
            long w1 = (long) LE_WORD.get(bytes, 8);
            long w2 = (long) LE_WORD.get(bytes, to - 8);
            if (isJsonAsciiWords3(w0, w1, w2)) {
                // T2 three-word trick: 3 loads + 3 stores for any 16..24 byte
                // string; the last store overlaps the first two but is byte-identical.
                int pos = rbo.position();
                byte[] buf = rbo.buffer();
                if (pos + 16 > buf.length) {
                    buf = rbo.grow(pos + 16);
                }
                putWordLE(buf, pos, w0);
                putWordLE(buf, pos + 8, w1);
                putWordLE(buf, pos + (to - 8), w2);
                rbo.setPosition(pos + to);
                return;
            }
            writeEscapedLatin1PerByteDirect(rbo, bytes, 0, to);
            return;
        }
        if (to <= 31) {
            long w0 = (long) LE_WORD.get(bytes, 0);
            long w1 = (long) LE_WORD.get(bytes, 8);
            long w2 = (long) LE_WORD.get(bytes, 16);
            long w3 = (long) LE_WORD.get(bytes, to - 8);
            if (isJsonAsciiWords4(w0, w1, w2, w3)) {
                int pos = rbo.position();
                byte[] buf = rbo.buffer();
                if (pos + to > buf.length) {
                    buf = rbo.grow(pos + to);
                }
                putWordLE(buf, pos, w0);
                putWordLE(buf, pos + 8, w1);
                putWordLE(buf, pos + 16, w2);
                putWordLE(buf, pos + (to - 8), w3);
                rbo.setPosition(pos + to);
                return;
            }
            writeEscapedLatin1PerByteDirect(rbo, bytes, 0, to);
            return;
        }
        // >= 32 bytes: 16-byte block loop, clean blocks stored with one capacity
        // check per block; the < 16 tail is handled per-byte.
        int i = 0;
        for (; i + 16 <= to; i += 16) {
            long w0 = (long) LE_WORD.get(bytes, i);
            long w1 = (long) LE_WORD.get(bytes, i + 8);
            if (isJsonAsciiWords2(w0, w1)) {
                int pos = rbo.position();
                byte[] buf = rbo.buffer();
                if (pos + to > buf.length) {
                    buf = rbo.grow(pos + to);
                }
                putWordLE(buf, pos, w0);
                putWordLE(buf, pos + 8, w1);
                rbo.setPosition(pos + 16);
            } else {
                writeEscapedLatin1PerByteDirect(rbo, bytes, i, i + 16);
            }
        }
        if (i < to) {
            writeEscapedLatin1PerByteDirect(rbo, bytes, i, to);
        }
    }

    private static void writeEscapedLatin1PerByteDirect(ReusableByteArrayOutputStream rbo, byte[] bytes, int from, int to) {
        int segmentStart = from;
        for (int i = from; i < to; i++) {
            int b = bytes[i] & 0xFF;
            byte[] escape = escapeBytesForAsciiControl(b);
            if (escape != null || b < 0x20 || b >= 0x80) {
                copyDirect(rbo, bytes, segmentStart, i);
                writeEscapedByteDirect(rbo, b);
                segmentStart = i + 1;
            }
        }
        if (segmentStart < to) {
            copyDirect(rbo, bytes, segmentStart, to);
        }
    }

    /** Stores one escaped byte (1..6 output bytes) with one inlined capacity check. */
    private static void writeEscapedByteDirect(ReusableByteArrayOutputStream rbo, int b) {
        int pos = rbo.position();
        byte[] buf = rbo.buffer();
        if (pos + 6 > buf.length) {
            buf = rbo.grow(pos + 6);
        }
        switch (b) {
            case '"': buf[pos++] = '\\'; buf[pos++] = '"'; break;
            case '\\': buf[pos++] = '\\'; buf[pos++] = '\\'; break;
            case '\b': buf[pos++] = '\\'; buf[pos++] = 'b'; break;
            case '\f': buf[pos++] = '\\'; buf[pos++] = 'f'; break;
            case '\n': buf[pos++] = '\\'; buf[pos++] = 'n'; break;
            case '\r': buf[pos++] = '\\'; buf[pos++] = 'r'; break;
            case '\t': buf[pos++] = '\\'; buf[pos++] = 't'; break;
            default:
                if (b < 0x20) {
                    buf[pos++] = '\\';
                    buf[pos++] = 'u';
                    buf[pos++] = '0';
                    buf[pos++] = '0';
                    buf[pos++] = HEX_DIGITS[b >>> 4];
                    buf[pos++] = HEX_DIGITS[b & 0xF];
                } else {
                    // Latin-1 code point to 2-byte UTF-8.
                    buf[pos++] = (byte) (0xC0 | (b >> 6));
                    buf[pos++] = (byte) (0x80 | (b & 0x3F));
                }
        }
        rbo.setPosition(pos);
    }

    /** Copies {@code [from, to)} into the buffer with one inlined capacity check. */
    private static void copyDirect(ReusableByteArrayOutputStream rbo, byte[] src, int from, int to) {
        int len = to - from;
        if (len <= 0) {
            return;
        }
        int pos = rbo.position();
        byte[] buf = rbo.buffer();
        if (pos + len > buf.length) {
            buf = rbo.grow(pos + len);
        }
        System.arraycopy(src, from, buf, pos, len);
        rbo.setPosition(pos + len);
    }

    private static void putWordLE(byte[] buf, int pos, long v) {
        LE_WORD.set(buf, pos, v);
    }

    // ---- SWAR predicates (ported from Fory Utf8JsonWriter) -----------------

    private static boolean isJsonAsciiByte(byte value) {
        int ch = value & 0xff;
        return ch > 0x1F && ch < 0x80 && ch != '"' && ch != '\\';
    }

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

    /** Validates the 4/2/1-byte tail of an 8..15 byte string (word at {@code from} covers 0..7). */
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
