package hr.hrg.dialog.core;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Composable cursor-locality write operations that delegate to the project's
 * already-optimized primitives — {@link JsonNumberWriter} (packed digit tables,
 * {@code DIGIT_QUADS}/{@code DIGIT_TRIPLES}, Ryu float/double), {@link
 * EscapedJsonStringWriter}/{@link DirectJsonStringWriter} (SWAR scan + length-band
 * dispatch), and {@link StringByteExtractor}.
 *
 * <p>Every method returns the new cursor position ({@code int}) and takes only
 * primitives ({@code byte[]}, {@code int}, {@code long}) plus caller-owned
 * reusable digit buffers — never a heap-allocated cursor object beyond the
 * {@code byte[]} itself. That keeps C2 able to inline the methods and keep the
 * {@code buf}/{@code pos}/{@code limit} locals in registers (per
 * {@code doc/perf-exploration/t7-cursor-locality-buffer-writer.md}). No method allocates on
 * the hot path.
 *
 * <p>Two shapes are provided:
 * <ul>
 *   <li><b>Pure {@code byte[] buf, int pos}</b> overloads — assume the caller has
 *       already ensured capacity (the single cold-path check); they only store
 *       and return the advanced position. Used by the benchmark and by writers
 *       that pre-size their buffer.</li>
 *   <li><b>Grow-capable {@link ReusableByteArrayOutputStream} overloads</b> — used by the JSON
 *       direct path; they perform the inlined {@code ensure} via the cursor and
 *       are byte-identical to calling the optimized backends directly.</li>
 * </ul>
 *
 * <p>Do <b>not</b> add naive shift-and-store number formatting here: the digit
 * building stays in {@link JsonNumberWriter}.
 */
public final class WriteOps {

    private static final byte[] JSON_NULL = "null".getBytes(StandardCharsets.UTF_8);
    private static final byte[] HEX_DIGITS = "0123456789ABCDEF".getBytes(StandardCharsets.US_ASCII);

    /**
     * Little-endian 8-byte long store over a {@code byte[]} (the {@code byte[]}
     * VarHandle view needs no alignment). Hot writers call
     * {@code LE_LONG.set(buf, pos, value)} directly — one full word store per
     * 8-byte window — then advance the cursor by the byte length they consumed.
     */
    public static final VarHandle LE_LONG =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    private static final VarHandle LE_INT =
            MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);

    private WriteOps() {}

    // =========================================================================
    // Pure byte[] + int pos overloads (capacity assumed; caller did the check)
    // =========================================================================

    /** Bulk copy of {@code src[off..off+len)} into {@code buf} at {@code pos}. */
    public static int writeRaw(byte[] buf, int pos, byte[] src, int off, int len) {
        System.arraycopy(src, off, buf, pos, len);
        return pos + len;
    }

    /** Stores one byte at {@code pos} and returns the advanced position. */
    public static int writeByte(byte[] buf, int pos, int b) {
        buf[pos] = (byte) b;
        return pos + 1;
    }

    /** Stores the low {@code n} bytes of {@code value} little-endian (n in 0..8); returns advanced pos. */
    public static int writePackedLE(byte[] buf, int pos, long value, int n) {
        if (n == 8) {
            LE_LONG.set(buf, pos, value);
            return pos + 8;
        }
        switch (n) {
            case 7: buf[pos + 6] = (byte) (value >>> 48);
            case 6: buf[pos + 5] = (byte) (value >>> 40);
            case 5: buf[pos + 4] = (byte) (value >>> 32);
            case 4: buf[pos + 3] = (byte) (value >>> 24);
            case 3: buf[pos + 2] = (byte) (value >>> 16);
            case 2: buf[pos + 1] = (byte) (value >>> 8);
            case 1: buf[pos] = (byte) value;
            case 0: break;
            default: throw new IllegalArgumentException("n must be in 0..8: " + n);
        }
        return pos + n;
    }

    // Specialized partial stores: write exactly 1..7 low bytes little-endian
    // (the last 8-n bytes are skipped). These are the compile-time-constant
    // variants benchmarked against the byte[]/full-store approaches.

    /** Stores the low byte of {@code value} little-endian at {@code pos}; returns {@code pos + 1}. */
    public static int writePackedLE1(byte[] buf, int pos, long value) {
        buf[pos] = (byte) value;
        return pos + 1;
    }

    /** Stores the low 2 bytes of {@code value} little-endian; returns {@code pos + 2}. */
    public static int writePackedLE2(byte[] buf, int pos, long value) {
        buf[pos] = (byte) value;
        buf[pos + 1] = (byte) (value >>> 8);
        return pos + 2;
    }

    /** Stores the low 3 bytes of {@code value} little-endian; returns {@code pos + 3}. */
    public static int writePackedLE3(byte[] buf, int pos, long value) {
        buf[pos] = (byte) value;
        buf[pos + 1] = (byte) (value >>> 8);
        buf[pos + 2] = (byte) (value >>> 16);
        return pos + 3;
    }

    /** Stores the low 4 bytes of {@code value} little-endian; returns {@code pos + 4}. */
    public static int writePackedLE4(byte[] buf, int pos, long value) {
        buf[pos] = (byte) value;
        buf[pos + 1] = (byte) (value >>> 8);
        buf[pos + 2] = (byte) (value >>> 16);
        buf[pos + 3] = (byte) (value >>> 24);
        return pos + 4;
    }

    /** Stores the low 5 bytes of {@code value} little-endian; returns {@code pos + 5}. */
    public static int writePackedLE5(byte[] buf, int pos, long value) {
        buf[pos] = (byte) value;
        buf[pos + 1] = (byte) (value >>> 8);
        buf[pos + 2] = (byte) (value >>> 16);
        buf[pos + 3] = (byte) (value >>> 24);
        buf[pos + 4] = (byte) (value >>> 32);
        return pos + 5;
    }

    /** Stores the low 6 bytes of {@code value} little-endian; returns {@code pos + 6}. */
    public static int writePackedLE6(byte[] buf, int pos, long value) {
        buf[pos] = (byte) value;
        buf[pos + 1] = (byte) (value >>> 8);
        buf[pos + 2] = (byte) (value >>> 16);
        buf[pos + 3] = (byte) (value >>> 24);
        buf[pos + 4] = (byte) (value >>> 32);
        buf[pos + 5] = (byte) (value >>> 40);
        return pos + 6;
    }

    /** Stores the low 7 bytes of {@code value} little-endian (skips the 8th); returns {@code pos + 7}. */
    public static int writePackedLE7(byte[] buf, int pos, long value) {
        buf[pos] = (byte) value;
        buf[pos + 1] = (byte) (value >>> 8);
        buf[pos + 2] = (byte) (value >>> 16);
        buf[pos + 3] = (byte) (value >>> 24);
        buf[pos + 4] = (byte) (value >>> 32);
        buf[pos + 5] = (byte) (value >>> 40);
        buf[pos + 6] = (byte) (value >>> 48);
        return pos + 7;
    }

    /** Stores the low {@code n} bytes of {@code value} little-endian (n in 0..4); returns advanced pos. */
    public static int writePackedLE(byte[] buf, int pos, int value, int n) {
        if (n == 4) {
            LE_INT.set(buf, pos, value);
            return pos + 4;
        }
        switch (n) {
            case 3: buf[pos + 2] = (byte) (value >>> 16);
            case 2: buf[pos + 1] = (byte) (value >>> 8);
            case 1: buf[pos] = (byte) value;
            case 0: break;
            default: throw new IllegalArgumentException("n must be in 0..4: " + n);
        }
        return pos + n;
    }

    /**
     * Writes a fully quoted, JSON-escaped string (or {@code null} literal) into
     * {@code buf} at {@code pos}. Capacity must already be ensured.
     */
    public static int writeEscapedJsonString(byte[] buf, int pos, String s) {
        if (s == null) {
            System.arraycopy(JSON_NULL, 0, buf, pos, JSON_NULL.length);
            return pos + JSON_NULL.length;
        }
        buf[pos++] = '"';
        int len = s.length();
        for (int i = 0; i < len; i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"': buf[pos++] = '\\'; buf[pos++] = '"'; break;
                case '\\': buf[pos++] = '\\'; buf[pos++] = '\\'; break;
                case '\b': buf[pos++] = '\\'; buf[pos++] = 'b'; break;
                case '\f': buf[pos++] = '\\'; buf[pos++] = 'f'; break;
                case '\n': buf[pos++] = '\\'; buf[pos++] = 'n'; break;
                case '\r': buf[pos++] = '\\'; buf[pos++] = 'r'; break;
                case '\t': buf[pos++] = '\\'; buf[pos++] = 't'; break;
                default:
                    if (ch < 0x20) {
                        buf[pos++] = '\\';
                        buf[pos++] = 'u';
                        buf[pos++] = '0';
                        buf[pos++] = '0';
                        buf[pos++] = HEX_DIGITS[(ch >>> 4) & 0xF];
                        buf[pos++] = HEX_DIGITS[ch & 0xF];
                    } else if (ch <= 0x7F) {
                        buf[pos++] = (byte) ch;
                    } else if (ch <= 0x7FF) {
                        buf[pos++] = (byte) (0xC0 | (ch >>> 6));
                        buf[pos++] = (byte) (0x80 | (ch & 0x3F));
                    } else if (Character.isHighSurrogate(ch) && i + 1 < len
                            && Character.isLowSurrogate(s.charAt(i + 1))) {
                        int cp = Character.toCodePoint(ch, s.charAt(++i));
                        buf[pos++] = (byte) (0xF0 | (cp >>> 18));
                        buf[pos++] = (byte) (0x80 | ((cp >>> 12) & 0x3F));
                        buf[pos++] = (byte) (0x80 | ((cp >>> 6) & 0x3F));
                        buf[pos++] = (byte) (0x80 | (cp & 0x3F));
                    } else if (Character.isHighSurrogate(ch) || Character.isLowSurrogate(ch)) {
                        buf[pos++] = (byte) 0xEF;
                        buf[pos++] = (byte) 0xBF;
                        buf[pos++] = (byte) 0xBD;
                    } else {
                        buf[pos++] = (byte) (0xE0 | (ch >>> 12));
                        buf[pos++] = (byte) (0x80 | ((ch >>> 6) & 0x3F));
                        buf[pos++] = (byte) (0x80 | (ch & 0x3F));
                    }
            }
        }
        buf[pos++] = '"';
        return pos;
    }

    /**
     * Writes {@code s} as raw UTF-8 bytes into {@code buf} at {@code pos} (no
     * escaping). Capacity must already be ensured.
     */
    public static int writeLatin1(byte[] buf, int pos, String s) {
        if (s == null) {
            return pos;
        }
        int len = s.length();
        for (int i = 0; i < len; i++) {
            char ch = s.charAt(i);
            if (ch <= 0x7F) {
                buf[pos++] = (byte) ch;
            } else if (ch <= 0x7FF) {
                buf[pos++] = (byte) (0xC0 | (ch >>> 6));
                buf[pos++] = (byte) (0x80 | (ch & 0x3F));
            } else if (Character.isHighSurrogate(ch) && i + 1 < len
                    && Character.isLowSurrogate(s.charAt(i + 1))) {
                int cp = Character.toCodePoint(ch, s.charAt(++i));
                buf[pos++] = (byte) (0xF0 | (cp >>> 18));
                buf[pos++] = (byte) (0x80 | ((cp >>> 12) & 0x3F));
                buf[pos++] = (byte) (0x80 | ((cp >>> 6) & 0x3F));
                buf[pos++] = (byte) (0x80 | (cp & 0x3F));
            } else if (Character.isHighSurrogate(ch) || Character.isLowSurrogate(ch)) {
                buf[pos++] = (byte) 0xEF;
                buf[pos++] = (byte) 0xBF;
                buf[pos++] = (byte) 0xBD;
            } else {
                buf[pos++] = (byte) (0xE0 | (ch >>> 12));
                buf[pos++] = (byte) (0x80 | ((ch >>> 6) & 0x3F));
                buf[pos++] = (byte) (0x80 | (ch & 0x3F));
            }
        }
        return pos;
    }

    // =========================================================================
    // Grow-capable ReusableByteArrayOutputStream overloads (used by the JSON direct path)
    // =========================================================================

    /** Bulk copy through the grow-capable cursor. */
    public static int writeRaw(ReusableByteArrayOutputStream c, byte[] src, int off, int len) {
        c.writeRaw(src, off, len);
        return c.position();
    }

    /**
     * Writes a fully quoted, JSON-escaped string (or {@code null} literal)
     * through the grow-capable cursor, reusing the SWAR {@link
     * DirectJsonStringWriter} path.
     */
    public static int writeEscapedJsonString(ReusableByteArrayOutputStream c, String s) {
        if (s == null) {
            c.writeRaw(JSON_NULL, 0, JSON_NULL.length);
            return c.position();
        }
        DirectJsonStringWriter.writeJsonString(c, s);
        return c.position();
    }
}
