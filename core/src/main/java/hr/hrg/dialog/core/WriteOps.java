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
 *   <li><b>Limit-aware {@code byte[] buf, int pos, int limit}</b> overloads (the
 *       {@code NoGrow} suffix) — check against {@code limit} and return the new
 *       position, or the <b>negated position</b> ({@code -pos}) on overflow, so
 *       the caller restores its cursor from the magnitude and finalizes. Used by
 *       the no-grow event assembly; the backing buffer never reallocates.</li>
 * </ul>
 *
 * <p>Do <b>not</b> add naive shift-and-store number formatting here: the digit
 * building stays in {@link JsonNumberWriter}.
 */
public final class WriteOps {

    // @CB.StrPacker private static final JSON_NULL = `null`
    private static final long JSON_NULL_W0 = 0x000000006c6c756eL;  // "null"
    private static final int JSON_NULL_LEN = 4;
    private static final int JSON_NULL_LEN_BUF = 8;

    private static final byte[] HEX_DIGITS = "0123456789ABCDEF".getBytes(StandardCharsets.US_ASCII);

    /**
     * Little-endian 8-byte long store over a {@code byte[]} (the {@code byte[]}
     * VarHandle view needs no alignment). Hot writers call
     * {@code LE_LONG.set(buf, pos, value)} directly — one full word store per
     * 8-byte window — then advance the cursor by the byte length they consumed.
     */
    public static final VarHandle LE_LONG =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

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

    /**
     * Writes a fully quoted, JSON-escaped string (or {@code null} literal) into
     * {@code buf} at {@code pos}. Capacity must already be ensured.
     */
    public static int writeEscapedJsonString(byte[] buf, int pos, String s) {
        if (s == null) {
            LE_LONG.set(buf, pos, JSON_NULL_W0);   // full 8-byte store, advance 4
            return pos + JSON_NULL_LEN;
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
    // Limit-aware (no-grow) overloads — negated-position contract.
    // =========================================================================

    /**
     * Writes a fully quoted, JSON-escaped string (or {@code null} literal) into
     * {@code buf} at {@code pos}, checking against {@code limit}. Returns the new
     * position, or {@code -pos} on overflow. The null branch stays here (so
     * {@link DirectJsonStringWriter} needs no packed-null constants); the
     * non-null branch delegates to {@link DirectJsonStringWriter#writeJsonStringNoGrow}.
     */
    public static int writeEscapedJsonStringNoGrow(byte[] buf, int pos, int limit, String s) {
        if (s == null) {
            if (pos + JSON_NULL_LEN_BUF > limit) return -pos;
            LE_LONG.set(buf, pos, JSON_NULL_W0);      // full 8-byte store, advance 4
            return pos + JSON_NULL_LEN;
        }
        return DirectJsonStringWriter.writeJsonStringNoGrow(buf, pos, limit, s);
    }

    /**
     * Bulk copy with a limit (not {@code buf.length}) check. All-or-nothing:
     * checks first, then copies. Returns the new position, or {@code -pos} on
     * overflow.
     */
    public static int writeRawNoGrow(byte[] buf, int pos, int limit, byte[] src, int off, int len) {
        if (pos + len > limit) return -pos;
        System.arraycopy(src, off, buf, pos, len);
        return pos + len;
    }
}
