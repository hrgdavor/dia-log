package hr.hrg.dialog.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Random;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

/**
 * Streaming-vs-single-shot equivalence tests for the finalization edge cases:
 * <ul>
 *   <li>every pending-tail size 1..16 (the {@code rem = 16 - bufLen} window in
 *       {@link Wyhash64.Streaming#finalHash()})</li>
 *   <li>stream lengths that are exact multiples of 48 bytes — the block-round
 *       boundary the char[] update paths historically mishandled (they rounded
 *       the final block while the single-shot {@link Wyhash64#hash} family and
 *       the byte[]/updateByte streaming paths leave it unrounded)</li>
 *   <li>drain rounds that fill the buffer exactly, randomized chunkings, mixed
 *       update flavors and {@link Wyhash64.Streaming#reset(long)} reuse</li>
 * </ul>
 * Every case asserts that the streaming hash equals the single-shot hash of the
 * same logical byte stream.
 */
class Wyhash64StreamingSingleShotTest {

    private static final long SEED = 0x5EED_1234_ABCD_5678L;

    // ==========================================================================
    //  Helpers
    // ==========================================================================

    private static long streamHash(long seed, Consumer<Wyhash64.Streaming> feed) {
        Wyhash64.Streaming st = new Wyhash64.Streaming(seed);
        feed.accept(st);
        return st.finalHash();
    }

    private static void assertStreamMatchesBytes(long seed, byte[] bytes,
            Consumer<Wyhash64.Streaming> feed, String what) {
        long singleShot = Wyhash64.hash(seed, bytes);
        assertEquals(singleShot, streamHash(seed, feed),
                what + ": streaming hash must equal single-shot hash(byte[]) for byte length " + bytes.length);
    }

    /** Byte view of a String matching streaming/String semantics: all Latin-1 -> 1 byte/char, else UTF-16 LE. */
    private static byte[] stringBytes(String s) {
        boolean utf16 = false;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 0xFF) {
                utf16 = true;
                break;
            }
        }
        if (!utf16) {
            return latin1Bytes(s);
        }
        byte[] out = new byte[s.length() * 2];
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            out[i * 2] = (byte) (c & 0xFF);
            out[i * 2 + 1] = (byte) ((c >> 8) & 0xFF);
        }
        return out;
    }

    /** Byte view of a char[] matching update(char[]) semantics (Latin-1/UTF-16 auto-detection). */
    private static byte[] charsBytes(char[] chars) {
        boolean utf16 = false;
        for (char c : chars) {
            if (c > 0xFF) {
                utf16 = true;
                break;
            }
        }
        if (!utf16) {
            return latin1Bytes(new String(chars));
        }
        byte[] out = new byte[chars.length * 2];
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            out[i * 2] = (byte) (c & 0xFF);
            out[i * 2 + 1] = (byte) ((c >> 8) & 0xFF);
        }
        return out;
    }

    /** Latin-1 byte view of a CharSequence, matching update(CharSequence)/hash(CharSequence) (chars > 0xFF -> '?'). */
    private static byte[] latin1Bytes(CharSequence cs) {
        byte[] out = new byte[cs.length()];
        for (int i = 0; i < cs.length(); i++) {
            char c = cs.charAt(i);
            out[i] = (byte) (c <= 0xFF ? c : 0x3F);
        }
        return out;
    }

    private static String asciiString(int len, Random rnd) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append((char) ('a' + rnd.nextInt(26)));
        }
        return sb.toString();
    }

    private static String utf16String(int charCount, Random rnd) {
        StringBuilder sb = new StringBuilder(charCount);
        for (int i = 0; i < charCount; i++) {
            sb.append((char) (0x100 + rnd.nextInt(0xF000)));
        }
        return sb.toString();
    }

    private static char[] latin1Chars(int len, Random rnd) {
        char[] c = new char[len];
        for (int i = 0; i < len; i++) {
            c[i] = (char) rnd.nextInt(0x100);
        }
        return c;
    }

    private static char[] utf16Chars(int charCount, Random rnd) {
        char[] c = new char[charCount];
        for (int i = 0; i < charCount; i++) {
            c[i] = (char) (0x100 + rnd.nextInt(0xF000));
        }
        return c;
    }

    // ==========================================================================
    //  Byte-length sweeps 0..160 (hits every 48-block boundary and every
    //  pending-tail size via the scratch finalization path)
    // ==========================================================================

    @Test
    void byteStream_sweep_singleShot() {
        Random rnd = new Random(11);
        for (int len = 0; len <= 160; len++) {
            byte[] data = new byte[len];
            rnd.nextBytes(data);

            assertStreamMatchesBytes(SEED, data, st -> st.update(data), "byte[] single update");
            assertStreamMatchesBytes(SEED, data, st -> {
                for (byte b : data) {
                    st.updateByte(b);
                }
            }, "updateByte");
        }
    }

    @Test
    void charStream_sweep_singleShot() {
        Random rnd = new Random(12);
        for (int byteLen = 0; byteLen <= 160; byteLen++) {
            char[] latin1 = latin1Chars(byteLen, rnd);
            assertEquals(Wyhash64.hash(SEED, latin1), streamHash(SEED, st -> st.update(latin1)),
                    "Latin-1 char[] single update failed for byte length " + byteLen);

            if (byteLen % 2 == 0) {
                char[] utf16 = utf16Chars(byteLen / 2, rnd);
                assertEquals(Wyhash64.hash(SEED, utf16), streamHash(SEED, st -> st.update(utf16)),
                        "UTF-16 char[] single update failed for byte length " + byteLen);
            }
        }
    }

    @Test
    void string_sweep_singleShot() {
        Random rnd = new Random(13);
        for (int byteLen = 0; byteLen <= 160; byteLen++) {
            String ascii = asciiString(byteLen, rnd);
            assertEquals(Wyhash64.hash(SEED, ascii), streamHash(SEED, st -> st.update(ascii)),
                    "ASCII String single update failed for byte length " + byteLen);

            if (byteLen % 2 == 0) {
                String utf16 = utf16String(byteLen / 2, rnd);
                assertEquals(Wyhash64.hash(SEED, utf16), streamHash(SEED, st -> st.update(utf16)),
                        "UTF-16 String single update failed for byte length " + byteLen);
            }
        }
    }

    @Test
    void charSequence_sweep_singleShot() {
        Random rnd = new Random(14);
        for (int len = 0; len <= 160; len++) {
            StringBuilder sb = new StringBuilder(len);
            for (int i = 0; i < len; i++) {
                sb.append((char) rnd.nextInt(0x200)); // some chars > 0xFF -> '?' replacement
            }
            assertEquals(Wyhash64.hash(SEED, (CharSequence) sb), streamHash(SEED, st -> st.update((CharSequence) sb)),
                    "CharSequence single update failed for length " + len);
        }
    }

    // ==========================================================================
    //  Exact multiples of 48 bytes — every flavor, single-shot comparison
    // ==========================================================================

    @Test
    void exact48Multiples_singleShot() {
        Random rnd = new Random(21);
        long[] seeds = {0L, 0x123456789ABCDEFL};
        for (long seed : seeds) {
            for (int blocks = 1; blocks <= 4; blocks++) {
                int byteLen = blocks * 48;
                byte[] data = new byte[byteLen];
                rnd.nextBytes(data);

                assertStreamMatchesBytes(seed, data, st -> st.update(data), "byte[] 48k");
                assertStreamMatchesBytes(seed, data, st -> {
                    for (byte b : data) {
                        st.updateByte(b);
                    }
                }, "updateByte 48k");
                // two-chunk feed where the second chunk drains the buffer exactly
                assertStreamMatchesBytes(seed, data, st -> {
                    st.update(data, 0, 40);
                    st.update(data, 40, byteLen - 40);
                }, "byte[] drain 48k");

                char[] latin1 = new char[byteLen];
                for (int i = 0; i < byteLen; i++) {
                    latin1[i] = (char) (data[i] & 0xFF);
                }
                assertEquals(Wyhash64.hash(seed, latin1), streamHash(seed, st -> st.update(latin1)),
                        "Latin-1 char[] 48k failed for byte length " + byteLen);
                // mixed byte[] + char[] drain ending exactly at a block boundary
                assertStreamMatchesBytes(seed, data, st -> {
                    st.update(data, 0, 40);
                    st.update(latin1, 40, byteLen - 40);
                }, "mixed drain 48k");

                char[] utf16 = utf16Chars(byteLen / 2, rnd);
                assertEquals(Wyhash64.hash(seed, utf16), streamHash(seed, st -> st.update(utf16)),
                        "UTF-16 char[] 48k failed for byte length " + byteLen);
                String utf16s = utf16String(byteLen / 2, rnd);
                assertEquals(Wyhash64.hash(seed, utf16s), streamHash(seed, st -> st.update(utf16s)),
                        "UTF-16 String 48k failed for byte length " + byteLen);

                String ascii = asciiString(byteLen, rnd);
                assertEquals(Wyhash64.hash(seed, ascii), streamHash(seed, st -> st.update(ascii)),
                        "ASCII String 48k failed for byte length " + byteLen);

                StringBuilder sb = new StringBuilder(byteLen);
                for (int i = 0; i < byteLen; i++) {
                    sb.append((char) rnd.nextInt(0x200));
                }
                assertEquals(Wyhash64.hash(seed, (CharSequence) sb), streamHash(seed, st -> st.update((CharSequence) sb)),
                        "CharSequence 48k failed for byte length " + byteLen);
            }
        }
    }

    // ==========================================================================
    //  Every pending-tail size 1..16 — all flavors
    // ==========================================================================

    @Test
    void everyTailSize_singleShot() {
        Random rnd = new Random(31);
        for (int blocks = 1; blocks <= 2; blocks++) {
            for (int tail = 1; tail <= 16; tail++) {
                int byteLen = blocks * 48 + tail;
                byte[] data = new byte[byteLen];
                rnd.nextBytes(data);

                assertStreamMatchesBytes(SEED, data, st -> st.update(data), "byte[] tail=" + tail);
                assertStreamMatchesBytes(SEED, data, st -> {
                    for (byte b : data) {
                        st.updateByte(b);
                    }
                }, "updateByte tail=" + tail);

                char[] latin1 = latin1Chars(byteLen, rnd);
                assertEquals(Wyhash64.hash(SEED, latin1), streamHash(SEED, st -> st.update(latin1)),
                        "Latin-1 char[] tail=" + tail);

                String ascii = asciiString(byteLen, rnd);
                assertEquals(Wyhash64.hash(SEED, ascii), streamHash(SEED, st -> st.update(ascii)),
                        "ASCII String tail=" + tail);

                if (byteLen % 2 == 0) {
                    char[] utf16 = utf16Chars(byteLen / 2, rnd);
                    assertEquals(Wyhash64.hash(SEED, utf16), streamHash(SEED, st -> st.update(utf16)),
                            "UTF-16 char[] tail=" + tail);
                    String utf16s = utf16String(byteLen / 2, rnd);
                    assertEquals(Wyhash64.hash(SEED, utf16s), streamHash(SEED, st -> st.update(utf16s)),
                            "UTF-16 String tail=" + tail);
                }

                StringBuilder sb = new StringBuilder(byteLen);
                for (int i = 0; i < byteLen; i++) {
                    sb.append((char) rnd.nextInt(0x200));
                }
                assertEquals(Wyhash64.hash(SEED, (CharSequence) sb), streamHash(SEED, st -> st.update((CharSequence) sb)),
                        "CharSequence tail=" + tail);
            }
        }
    }

    // ==========================================================================
    //  Randomized chunkings and mixed flavors
    // ==========================================================================

    @Test
    void randomChunks_singleShot() {
        Random rnd = new Random(41);
        for (int iter = 0; iter < 200; iter++) {
            int len = rnd.nextInt(300);
            byte[] data = new byte[len];
            rnd.nextBytes(data);

            Wyhash64.Streaming st = new Wyhash64.Streaming(SEED);
            int pos = 0;
            while (pos < len) {
                int chunk = 1 + rnd.nextInt(37);
                int n = Math.min(chunk, len - pos);
                st.update(data, pos, n);
                pos += n;
            }
            assertEquals(Wyhash64.hash(SEED, data), st.finalHash(),
                    "random chunking failed for length " + len);
        }
    }

    @Test
    void mixedFlavors_singleShot() {
        Random rnd = new Random(51);
        for (int iter = 0; iter < 100; iter++) {
            ByteArrayOutputStream acc = new ByteArrayOutputStream();
            Wyhash64.Streaming st = new Wyhash64.Streaming(SEED);
            int ops = 1 + rnd.nextInt(20);
            for (int op = 0; op < ops; op++) {
                int len = rnd.nextInt(40);
                switch (rnd.nextInt(5)) {
                    case 0: { // byte[]
                        byte[] b = new byte[len];
                        rnd.nextBytes(b);
                        acc.write(b, 0, len);
                        st.update(b);
                        break;
                    }
                    case 1: { // String (ASCII or UTF-16)
                        int cc = rnd.nextBoolean() ? len : len / 2;
                        String s = rnd.nextBoolean() ? asciiString(cc, rnd) : utf16String(cc, rnd);
                        byte[] sb = stringBytes(s);
                        acc.write(sb, 0, sb.length);
                        st.update(s);
                        break;
                    }
                    case 2: { // char[] (Latin-1 or UTF-16)
                        int cc = rnd.nextBoolean() ? len : len / 2;
                        char[] c = rnd.nextBoolean() ? latin1Chars(cc, rnd) : utf16Chars(cc, rnd);
                        byte[] cb = charsBytes(c);
                        acc.write(cb, 0, cb.length);
                        st.update(c);
                        break;
                    }
                    case 3: { // CharSequence
                        StringBuilder sb = new StringBuilder(len);
                        for (int i = 0; i < len; i++) {
                            sb.append((char) rnd.nextInt(0x200));
                        }
                        byte[] lb = latin1Bytes(sb);
                        acc.write(lb, 0, lb.length);
                        st.update((CharSequence) sb);
                        break;
                    }
                    default: { // updateByte
                        byte[] b = new byte[len];
                        rnd.nextBytes(b);
                        acc.write(b, 0, len);
                        for (byte x : b) {
                            st.updateByte(x);
                        }
                    }
                }
            }
            byte[] total = acc.toByteArray();
            assertEquals(Wyhash64.hash(SEED, total), st.finalHash(),
                    "mixed flavor stream failed for iter " + iter + " (total " + total.length + " bytes)");
        }
    }

    @Test
    void stringOffsetLen_singleShot() {
        Random rnd = new Random(61);
        // byte lengths around the 48-block boundary, various offsets
        for (int len = 45; len <= 100; len++) {
            String s = asciiString(len + 10, rnd);
            byte[] bytes = stringBytes(s);
            for (int off = 0; off <= 5; off++) {
                int subLen = len - off;
                int o = off;
                byte[] ref = Arrays.copyOfRange(bytes, off, off + subLen);
                assertEquals(Wyhash64.hash(SEED, ref), streamHash(SEED, st -> st.update(s, o, subLen)),
                        "update(String,off,len) failed for len " + len + " off " + off);
            }
        }
    }

    @Test
    void afterReset_singleShot() {
        Random rnd = new Random(71);
        int[] lens = {48, 96, 97, 144, 145, 192, 200};
        byte[][] streams = new byte[lens.length][];
        for (int i = 0; i < lens.length; i++) {
            streams[i] = new byte[lens[i]];
            rnd.nextBytes(streams[i]);
        }

        Wyhash64.Streaming st = new Wyhash64.Streaming(SEED);
        for (int i = 0; i < lens.length; i++) {
            byte[] data = streams[i];
            st.reset(SEED);
            st.update(data);
            assertEquals(Wyhash64.hash(SEED, data), st.finalHash(),
                    "after reset() failed for length " + lens[i]);

            // reset with a different seed and a different flavor (char[])
            long s2 = SEED + i + 1;
            char[] chars = new char[lens[i]];
            for (int k = 0; k < chars.length; k++) {
                chars[k] = (char) (data[k] & 0xFF);
            }
            st.reset(s2);
            st.update(chars);
            assertEquals(Wyhash64.hash(s2, chars), st.finalHash(),
                    "after reset() with new seed failed for length " + lens[i]);
        }
    }
}
