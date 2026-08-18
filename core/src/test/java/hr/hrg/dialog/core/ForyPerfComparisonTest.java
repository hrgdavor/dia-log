package hr.hrg.dialog.core;

import hr.hrg.dialog.core.perf.ClassicEscapedStringWriter;
import hr.hrg.dialog.core.perf.ClassicJsonNumberWriter;
import hr.hrg.dialog.core.perf.ClassicStringByteExtractor;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Old-vs-new equivalence tests for the performance techniques ported from
 * Apache Fory commit 585eb16f ("feat(java): optimize json perf", PR #3871).
 *
 * <p>Each test writes the same input through the pre-optimization
 * implementation (kept in {@code hr.hrg.dialog.core.perf}) and through the new
 * production path, and asserts byte-identical output:
 * <ul>
 *   <li>T1/T2 — SWAR escape scan: {@link ClassicEscapedStringWriter} vs
 *       {@link EscapedJsonStringWriter} (stream and direct modes);</li>
 *   <li>T1/T2 — SWAR Latin-1 scan: {@link ClassicStringByteExtractor} vs
 *       {@link StringByteExtractor};</li>
 *   <li>T5 — packed digit tables: {@link ClassicJsonNumberWriter} vs
 *       {@link JsonNumberWriter}.</li>
 * </ul>
 * The escaping tests additionally compare against an independent reference
 * implementation, so old and new cannot both drift the same way.
 */
class ForyPerfComparisonTest {

    // ==========================================
    // Reference implementations (independent)
    // ==========================================

    /** Naive per-char JSON escaper used as an independent oracle. */
    private static String referenceJsonString(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04X", (int) ch));
                    } else {
                        sb.append(ch);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /** Reference JSON escape of a Latin-1 byte array (0x80..0xFF become UTF-8). */
    private static String referenceLatin1Json(byte[] latin1) {
        return referenceJsonString(new String(latin1, StandardCharsets.ISO_8859_1));
    }

    // ==========================================
    // Input batteries
    // ==========================================

    private static List<byte[]> latin1Battery() {
        List<byte[]> list = new ArrayList<>();
        // Pure printable ASCII, every length 0..48.
        for (int len = 0; len <= 48; len++) {
            byte[] b = new byte[len];
            for (int i = 0; i < len; i++) {
                b[i] = (byte) (0x21 + (i * 7) % 0x5E);
            }
            list.add(b);
        }
        // Spaces only (fast path rejects <= 0x22, fallback accepts).
        for (int len = 0; len <= 40; len++) {
            list.add(new byte[len]); // zeros (control chars)
            byte[] sp = new byte[len];
            java.util.Arrays.fill(sp, (byte) ' ');
            list.add(sp);
        }
        // One special byte at the middle of an 'a' run, every length.
        for (int len = 0; len <= 40; len++) {
            byte[][] specials = {
                {'"'}, {'\\'}, {0x1F}, {0x00}, {0x7F}, {(byte) 0x80}, {(byte) 0xFF}, {(byte) 0xE9}
            };
            for (byte[] s : specials) {
                byte[] b = new byte[len];
                java.util.Arrays.fill(b, (byte) 'a');
                if (len > 0) b[len / 2] = s[0];
                list.add(b);
            }
        }
        // Special bytes at every position of a fixed-length string.
        for (int pos = 0; pos < 24; pos++) {
            for (int special = 0; special < 3; special++) {
                byte[] b = new byte[24];
                java.util.Arrays.fill(b, (byte) 'x');
                b[pos] = (byte) (special == 0 ? '"' : special == 1 ? '\\' : 0x01);
                list.add(b);
            }
        }
        // Latin-1 high bytes sprinkled at several positions.
        for (int len = 8; len <= 40; len++) {
            byte[] b = new byte[len];
            java.util.Arrays.fill(b, (byte) 'x');
            b[len / 3] = (byte) 0xE9; // é
            b[len / 2] = (byte) 0xFF;
            b[len - 1] = (byte) 0x80;
            list.add(b);
        }
        // Full Latin-1 range, forward and reversed.
        byte[] all = new byte[256];
        for (int i = 0; i < 256; i++) all[i] = (byte) i;
        list.add(all);
        byte[] rev = new byte[256];
        for (int i = 0; i < 256; i++) rev[i] = (byte) (255 - i);
        list.add(rev);
        // Seeded random, skewed toward printable ASCII.
        Random rnd = new Random(42);
        for (int n = 0; n < 300; n++) {
            int len = rnd.nextInt(90);
            byte[] b = new byte[len];
            for (int i = 0; i < len; i++) {
                int v = rnd.nextInt(256);
                if (rnd.nextInt(4) != 0) v = 0x20 + rnd.nextInt(0x5F);
                b[i] = (byte) v;
            }
            list.add(b);
        }
        return list;
    }

    private static long[] longBattery() {
        long[] fixed = {
            0L, 1L, -1L, 2L, -2L, 9L, 10L, 11L, 99L, 100L, 101L, 999L, 1000L, 1001L,
            9999L, 10000L, 10001L, 99999L, 100000L, 999999L, 1000000L, 9999999L, 10000000L,
            99999999L, 100000000L, 999999999L, 1000000000L, 1000000001L,
            9999999999L, 10000000000L, 1234567890123L, -1234567890123L,
            9223372036854775807L, -9223372036854775808L, 922337203685477580L, -922337203685477580L,
            2147483647L, -2147483648L, 2147483646L, -2147483647L
        };
        Random rnd = new Random(7);
        long[] out = new long[fixed.length + 400];
        System.arraycopy(fixed, 0, out, 0, fixed.length);
        for (int i = fixed.length; i < out.length; i++) {
            long v = rnd.nextLong();
            if (rnd.nextInt(3) == 0) v = v % 100_000_000L; // small values
            out[i] = v;
        }
        return out;
    }

    private static int[] intBattery() {
        int[] fixed = {
            0, 1, -1, 9, 10, 11, 99, 100, 101, 999, 1000, 1001, 9999, 10000, 10001,
            99999, 100000, 999999, 1000000, 9999999, 10000000, 99999999, 100000000,
            999999999, 1000000000, 123456789, -123456789, Integer.MAX_VALUE, Integer.MIN_VALUE,
            Integer.MAX_VALUE - 1, Integer.MIN_VALUE + 1
        };
        Random rnd = new Random(13);
        int[] out = new int[fixed.length + 300];
        System.arraycopy(fixed, 0, out, 0, fixed.length);
        for (int i = fixed.length; i < out.length; i++) {
            int v = rnd.nextInt();
            if (rnd.nextInt(3) == 0) v = v % 100_000;
            out[i] = v;
        }
        return out;
    }

    // ==========================================
    // T1/T2 — SWAR escape scan
    // ==========================================

    @Test
    void escapedLatin1_classicVsNewStreamVsNewDirect_allMatchReference() throws IOException {
        for (byte[] latin1 : latin1Battery()) {
            String s = new String(latin1, StandardCharsets.ISO_8859_1);
            String expected = referenceLatin1Json(latin1);

            // Old per-byte implementation.
            ByteArrayOutputStream classic = new ByteArrayOutputStream();
            classic.write('"');
            ClassicEscapedStringWriter.writeEscapedLatin1(classic, latin1);
            classic.write('"');
            assertEquals(expected, classic.toString(StandardCharsets.UTF_8), "classic len=" + latin1.length);

            // New SWAR path, stream mode (ByteArrayOutputStream).
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            EscapedJsonStringWriter.writeJsonStringOrNull(stream, s);
            assertEquals(expected, stream.toString(StandardCharsets.UTF_8), "new-stream len=" + latin1.length);

            // New SWAR path, direct mode (ReusableByteArrayOutputStream).
            ReusableByteArrayOutputStream direct = new ReusableByteArrayOutputStream(16);
            EscapedJsonStringWriter.writeJsonStringOrNull(direct, s);
            assertEquals(expected, new String(direct.buffer(), 0, direct.size(), StandardCharsets.UTF_8),
                    "new-direct len=" + latin1.length);
        }
    }

    // ==========================================
    // T1/T2 — SWAR Latin-1 scan (StringByteExtractor)
    // ==========================================

    @Test
    void latin1ToUtf8_classicVsNewStreamVsNewDirect() throws IOException {
        for (byte[] latin1 : latin1Battery()) {
            ByteArrayOutputStream classic = new ByteArrayOutputStream();
            ClassicStringByteExtractor.writeLatin1(classic, latin1);

            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            StringByteExtractor.writeLatin1(stream, latin1);

            ReusableByteArrayOutputStream direct = new ReusableByteArrayOutputStream(16);
            StringByteExtractor.writeLatin1(direct, latin1);

            byte[] expected = classic.toByteArray();
            assertArrayEquals(expected, stream.toByteArray(), "stream len=" + latin1.length);
            assertArrayEquals(expected, java.util.Arrays.copyOf(direct.buffer(), direct.size()),
                    "direct len=" + latin1.length);
        }
    }

    // ==========================================
    // T5 — packed digit tables
    // ==========================================

    @Test
    void ints_classicVsNew() throws IOException {
        byte[] classicBuf = ClassicJsonNumberWriter.makeIntBuffer();
        byte[] newBuf = JsonNumberWriter.makeIntBuffer();
        for (int value : intBattery()) {
            ByteArrayOutputStream classic = new ByteArrayOutputStream();
            ClassicJsonNumberWriter.writeInt(classic, classicBuf, value);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            JsonNumberWriter.writeInt(out, newBuf, value);

            assertEquals(classic.toString(StandardCharsets.UTF_8), out.toString(StandardCharsets.UTF_8),
                    "int=" + value);
            assertEquals(String.valueOf(value), out.toString(StandardCharsets.UTF_8), "int=" + value);
        }
    }

    @Test
    void longs_classicVsNew() throws IOException {
        byte[] classicBuf = ClassicJsonNumberWriter.makeLongBuffer();
        byte[] newBuf = JsonNumberWriter.makeLongBuffer();
        for (long value : longBattery()) {
            ByteArrayOutputStream classic = new ByteArrayOutputStream();
            ClassicJsonNumberWriter.writeLong(classic, classicBuf, value);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            JsonNumberWriter.writeLong(out, newBuf, value);

            assertEquals(classic.toString(StandardCharsets.UTF_8), out.toString(StandardCharsets.UTF_8),
                    "long=" + value);
            assertEquals(String.valueOf(value), out.toString(StandardCharsets.UTF_8), "long=" + value);
        }
    }
}
