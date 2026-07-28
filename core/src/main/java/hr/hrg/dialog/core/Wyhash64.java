package hr.hrg.dialog.core;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * Standalone implementation of Wyhash matching Zig 0.15 std.hash.Wyhash.
 * Copied from https://github.com/hrgdavor/wyhash
 * <p>
 * <b>Note on code duplication:</b> The {@link #hash(long, byte[], int, int)},
 * {@link #hash(long, java.nio.ByteBuffer, int, int)}, and
 * {@link #hash(long, char[], int, int)} methods contain identical
 * hashing logic. This duplication is intentional — extracting a shared method
 * would introduce abstraction overhead (virtual calls, interface dispatch, or
 * extra indirection) that would degrade performance in this hot-path code.
 * The JIT compiler can better optimize the duplicated, self-contained methods.
 * </p>
 * <h2>Zero-Allocation String Support</h2>
 * <p>
 * {@link #hash(long, String)} accesses the internal {@code byte[] value} field
 * of {@link String} via {@link VarHandle} &mdash; no defensive copy, no
 * {@code str.getBytes()} allocation. Works with both Latin-1 (coder=0) and
 * UTF-16 (coder=1) compact strings. Requires
 * {@code --add-opens java.base/java.lang=ALL-UNNAMED} on Java 21+ when the
 * library is not on the module path with explicit opens.
 * </p>
 * <h2>Zero-Allocation char[] Support</h2>
 * <p>
 * {@link #hash(long, char[])} reads multi-byte primitive values directly
 * from the {@code char[]} via manual byte packing — no allocation, no
 * {@code sun.misc.Unsafe}, no FFM API dependency.
 * </p>
 */
public final class Wyhash64 {

    private static final long[] DEFAULT_SECRET = {
            0xa0761d6478bd642fL, 0xe7037ed1a0b428dbL, 0x8ebc6af09c88c6e3L, 0x589965cc75374cc3L
    };

    // -- VarHandles for byte[] -------------------------------------------------

    private static final VarHandle LONG_HANDLE = MethodHandles.byteArrayViewVarHandle(long[].class,
            ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle INT_HANDLE = MethodHandles.byteArrayViewVarHandle(int[].class,
            ByteOrder.LITTLE_ENDIAN);

    // -- VarHandles for ByteBuffer ---------------------------------------------

    private static final VarHandle BB_LONG_HANDLE = MethodHandles.byteBufferViewVarHandle(long[].class,
            ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle BB_INT_HANDLE = MethodHandles.byteBufferViewVarHandle(int[].class,
            ByteOrder.LITTLE_ENDIAN);

    // -- VarHandles for String internal access ---------------------------------

    private static final VarHandle STRING_VALUE_HANDLE;
    private static final VarHandle STRING_CODER_HANDLE;

    static {
        try {
            var lookup = MethodHandles.privateLookupIn(String.class, MethodHandles.lookup());
            STRING_VALUE_HANDLE = lookup.findVarHandle(String.class, "value", byte[].class);
            STRING_CODER_HANDLE = lookup.findVarHandle(String.class, "coder", byte.class);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(
                    "Cannot access String.value/coder -- add --add-opens java.base/java.lang=ALL-UNNAMED: " + e);
        }
    }

    // LATIN-1 coder value (Java 9+ compact strings)
    private static final byte LATIN1 = 0;

    private Wyhash64() {
    }

    // ==========================================================================
    //  Public API — byte[]
    // ==========================================================================

    public static long hash(long seed, byte[] data) {
        return hash(seed, data, 0, data.length);
    }

    public static long hash(long seed, byte[] data, int off, int len) {
        long s = initSeed(seed);
        long secret1 = DEFAULT_SECRET[1];
        long secret2 = DEFAULT_SECRET[2];
        long secret3 = DEFAULT_SECRET[3];

        long a, b;

        if (len <= 16) {
            if (len >= 4) {
                a = ((long) getInt(data, off) << 32) | (getInt(data, off + ((len >> 3) << 2)) & 0xFFFFFFFFL);
                b = ((long) getInt(data, off + len - 4) << 32)
                        | (getInt(data, off + len - 4 - ((len >> 3) << 2)) & 0xFFFFFFFFL);
            } else if (len > 0) {
                a = wyr3(data, off, len);
                b = 0;
            } else {
                a = 0;
                b = 0;
            }
        } else {
            int i = len;
            int p = off;
            long see0 = s;
            long see1 = s;
            long see2 = s;

            while (i > 48) {
                see0 = mix(getLong(data, p) ^ secret1, getLong(data, p + 8) ^ see0);
                see1 = mix(getLong(data, p + 16) ^ secret2, getLong(data, p + 24) ^ see1);
                see2 = mix(getLong(data, p + 32) ^ secret3, getLong(data, p + 40) ^ see2);
                p += 48;
                i -= 48;
            }
            see0 ^= see1 ^ see2;
            while (i > 16) {
                see0 = mix(getLong(data, p) ^ secret1, getLong(data, p + 8) ^ see0);
                i -= 16;
                p += 16;
            }
            a = getLong(data, off + len - 16);
            b = getLong(data, off + len - 8);
            s = see0;
        }

        return finish(a, b, s, len);
    }

    // ==========================================================================
    //  Public API — ByteBuffer
    // ==========================================================================

    public static long hash(long seed, java.nio.ByteBuffer data) {
        return hash(seed, data, data.position(), data.remaining());
    }

    public static long hash(long seed, java.nio.ByteBuffer data, int off, int len) {
        long s = initSeed(seed);
        long secret1 = DEFAULT_SECRET[1];
        long secret2 = DEFAULT_SECRET[2];
        long secret3 = DEFAULT_SECRET[3];

        long a, b;

        if (len <= 16) {
            if (len >= 4) {
                a = ((long) getInt(data, off) << 32) | (getInt(data, off + ((len >> 3) << 2)) & 0xFFFFFFFFL);
                b = ((long) getInt(data, off + len - 4) << 32)
                        | (getInt(data, off + len - 4 - ((len >> 3) << 2)) & 0xFFFFFFFFL);
            } else if (len > 0) {
                a = wyr3(data, off, len);
                b = 0;
            } else {
                a = 0;
                b = 0;
            }
        } else {
            int i = len;
            int p = off;
            long see0 = s;
            long see1 = s;
            long see2 = s;

            while (i > 48) {
                see0 = mix(getLong(data, p) ^ secret1, getLong(data, p + 8) ^ see0);
                see1 = mix(getLong(data, p + 16) ^ secret2, getLong(data, p + 24) ^ see1);
                see2 = mix(getLong(data, p + 32) ^ secret3, getLong(data, p + 40) ^ see2);
                p += 48;
                i -= 48;
            }
            see0 ^= see1 ^ see2;
            while (i > 16) {
                see0 = mix(getLong(data, p) ^ secret1, getLong(data, p + 8) ^ see0);
                i -= 16;
                p += 16;
            }
            a = getLong(data, off + len - 16);
            b = getLong(data, off + len - 8);
            s = see0;
        }

        return finish(a, b, s, len);
    }

    // ==========================================================================
    //  Public API — String  (zero allocation)
    // ==========================================================================

    /**
     * Hash a {@link String} without allocating a {@code byte[]} copy.
     * <p>
     * Accesses the internal {@code byte[] value} field via {@link VarHandle}.
     * For Latin-1 strings the raw byte[] is hashed directly; for UTF-16 strings
     * the 2-byte character groups are read via VarHandle.
     * </p>
     *
     * @param seed hash seed
     * @param str  the string to hash
     * @return 64-bit wyhash digest
     */
    public static long hash(long seed, String str) {
        byte[] value = (byte[]) STRING_VALUE_HANDLE.get(str);
        byte coder = (byte) STRING_CODER_HANDLE.get(str);
        int len = str.length();

        if (coder == LATIN1) {
            // Latin-1: each byte IS one character — hash byte[] directly
            return hash(seed, value, 0, len);
        } else {
            // UTF-16: read 2-byte character units from the byte[]
            return hashUtf16(seed, value, 0, len);
        }
    }

    /**
     * Hash a {@link String} with offset and length (in characters, not bytes).
     * <p>
     * For Latin-1 strings, offset/len refers to byte positions = char positions.
     * For UTF-16 strings, offset/len refers to character positions — internal
     * byte offset is calculated as {@code off * 2} / {@code len * 2}.
     * </p>
     *
     * @param seed hash seed
     * @param str  the string to hash
     * @param off  character offset (not byte offset)
     * @param len  number of characters to hash
     * @return 64-bit wyhash digest
     */
    public static long hash(long seed, String str, int off, int len) {
        byte[] value = (byte[]) STRING_VALUE_HANDLE.get(str);
        byte coder = (byte) STRING_CODER_HANDLE.get(str);

        if (coder == LATIN1) {
            return hash(seed, value, off, len);
        } else {
            // UTF-16: 2 bytes per character
            return hashUtf16(seed, value, off * 2, len);
        }
    }

    // ==========================================================================
    //  Public API — char[]  (zero allocation, manual byte packing)
    // ==========================================================================

    /**
     * Hash a {@code char[]} without boxing or copying.
     * <p>
     * Reads multi-byte primitive values directly from the {@code char[]}
     * via manual byte packing — no allocation, no {@code sun.misc.Unsafe},
     * no FFM API dependency.
     * </p>
     *
     * @param seed  hash seed
     * @param chars the character array to hash
     * @return 64-bit wyhash digest
     */
    public static long hash(long seed, char[] chars) {
        return hash(seed, chars, 0, chars.length);
    }

    /**
     * Hash a {@code char[]} with offset and length.
     *
     * @param seed  hash seed
     * @param chars the character array
     * @param off   element offset (in chars, not bytes)
     * @param len   number of characters to hash
     * @return 64-bit wyhash digest
     */
    public static long hash(long seed, char[] chars, int off, int len) {
        long sc = initSeed(seed);
        long secret1 = DEFAULT_SECRET[1];
        long secret2 = DEFAULT_SECRET[2];
        long secret3 = DEFAULT_SECRET[3];

        int charOff = off;
        int charLen = len;

        long a, b;

        if (charLen <= 8) {
            if (charLen >= 2) {
                a = ((long) charToIntLE(chars, charOff) << 32) | (charToIntLE(chars, charOff + ((charLen >> 2) << 1)) & 0xFFFFFFFFL);
                b = ((long) charToIntLE(chars, charOff + charLen - 2) << 32)
                        | (charToIntLE(chars, charOff + charLen - 2 - ((charLen >> 2) << 1)) & 0xFFFFFFFFL);
            } else if (charLen > 0) {
                a = ((chars[charOff] & 0xFFL) << 16) | ((chars[charOff] & 0xFF00L) >>> 8);
                b = 0;
            } else {
                a = 0;
                b = 0;
            }
        } else {
            int i = charLen;
            int p = charOff;
            long see0 = sc;
            long see1 = sc;
            long see2 = sc;

            while (i > 24) {
                see0 = mix(charToLongLE(chars, p) ^ secret1, charToLongLE(chars, p + 4) ^ see0);
                see1 = mix(charToLongLE(chars, p + 8) ^ secret2, charToLongLE(chars, p + 12) ^ see1);
                see2 = mix(charToLongLE(chars, p + 16) ^ secret3, charToLongLE(chars, p + 20) ^ see2);
                p += 24;
                i -= 24;
            }
            see0 ^= see1 ^ see2;
            while (i > 8) {
                see0 = mix(charToLongLE(chars, p) ^ secret1, charToLongLE(chars, p + 4) ^ see0);
                i -= 8;
                p += 8;
            }
            a = charToLongLE(chars, charOff + charLen - 8);
            b = charToLongLE(chars, charOff + charLen - 4);
            sc = see0;
        }

        return finish(a, b, sc, (long) charLen * 2);
    }

    // ==========================================================================
    //  Public API — CharSequence  (character-by-character fallback)
    // ==========================================================================

    /**
     * Hash a {@link CharSequence} (zero-allocation for String, otherwise
     * character-by-character).
     * <p>
     * If the argument is a {@link String}, delegates to the zero-allocation
     * {@link #hash(long, String)} path. Otherwise iterates over chars using
     * {@link CharSequence#charAt(int)}.
     * </p>
     *
     * @param seed hash seed
     * @param cs   the character sequence
     * @return 64-bit wyhash digest
     */
    public static long hash(long seed, CharSequence cs) {
        if (cs instanceof String) {
            return hash(seed, (String) cs);
        }
        int len = cs.length();
        if (len == 0) {
            return finish(0, 0, initSeed(seed), 0);
        }
        long s = initSeed(seed);
        long secret1 = DEFAULT_SECRET[1];
        long secret2 = DEFAULT_SECRET[2];
        long secret3 = DEFAULT_SECRET[3];

        long a, b;
        if (len <= 8) {
            if (len >= 2) {
                a = packCharsLow(cs, 0, 2);
                b = packCharsLow(cs, len - 2, 2);
                a = (a << 32) | (b & 0xFFFFFFFFL);
            } else if (len > 0) {
                a = (cs.charAt(0) & 0xFFFFL) << 8 | 1L;
                b = 0;
            } else {
                a = 0;
                b = 0;
            }
        } else {
            long seed0 = s;
            long seed1 = s;
            long seed2 = s;
            int i = len;
            int pos = 0;

            while (i > 24) {
                seed0 = mix(packChars(cs, pos, 4) ^ secret1, packChars(cs, pos + 4, 4) ^ seed0);
                seed1 = mix(packChars(cs, pos + 8, 4) ^ secret2, packChars(cs, pos + 12, 4) ^ seed1);
                seed2 = mix(packChars(cs, pos + 16, 4) ^ secret3, packChars(cs, pos + 20, 4) ^ seed2);
                pos += 24;
                i -= 24;
            }
            seed0 ^= seed1 ^ seed2;
            while (i > 8) {
                seed0 = mix(packChars(cs, pos, 4) ^ secret1, packChars(cs, pos + 4, 4) ^ seed0);
                i -= 8;
                pos += 8;
            }
            a = packChars(cs, len - 8, 4);
            b = packChars(cs, len - 4, 4);
            s = seed0;
        }
        return finish(a, b, s, len * 2L);
    }

    // ==========================================================================
    //  Internal — UTF-16 string hashing
    // ==========================================================================

    private static long hashUtf16(long seed, byte[] data, int byteOff, int charLen) {
        long s = initSeed(seed);
        long secret1 = DEFAULT_SECRET[1];
        long secret2 = DEFAULT_SECRET[2];
        long secret3 = DEFAULT_SECRET[3];

        int byteLen = charLen * 2;
        long a, b;

        if (byteLen <= 16) {
            if (byteLen >= 4) {
                a = ((long) getInt(data, byteOff) << 32)
                        | (getInt(data, byteOff + ((byteLen >> 3) << 2)) & 0xFFFFFFFFL);
                b = ((long) getInt(data, byteOff + byteLen - 4) << 32)
                        | (getInt(data, byteOff + byteLen - 4 - ((byteLen >> 3) << 2)) & 0xFFFFFFFFL);
            } else if (byteLen > 0) {
                // read individual bytes (1 or 2 remaining)
                a = ((data[byteOff] & 0xFFL)) | ((data[byteOff + 1] & 0xFFL) << 8);
                b = 0;
            } else {
                a = 0;
                b = 0;
            }
        } else {
            int i = byteLen;
            int p = byteOff;
            long see0 = s;
            long see1 = s;
            long see2 = s;

            while (i > 48) {
                see0 = mix(getLong(data, p) ^ secret1, getLong(data, p + 8) ^ see0);
                see1 = mix(getLong(data, p + 16) ^ secret2, getLong(data, p + 24) ^ see1);
                see2 = mix(getLong(data, p + 32) ^ secret3, getLong(data, p + 40) ^ see2);
                p += 48;
                i -= 48;
            }
            see0 ^= see1 ^ see2;
            while (i > 16) {
                see0 = mix(getLong(data, p) ^ secret1, getLong(data, p + 8) ^ see0);
                i -= 16;
                p += 16;
            }
            a = getLong(data, byteOff + byteLen - 16);
            b = getLong(data, byteOff + byteLen - 8);
            s = see0;
        }

        return finish(a, b, s, byteLen);
    }

    // ==========================================================================
    //  Internal helpers — CharSequence & char[] packing
    // ==========================================================================

    /**
     * Pack up to 4 chars from a {@link CharSequence} into a little-endian long.
     */
    private static long packChars(CharSequence cs, int pos, int nChars) {
        long v = 0;
        for (int i = 0; i < nChars && pos + i < cs.length(); i++) {
            v |= ((long) cs.charAt(pos + i) & 0xFFFFL) << (i * 16);
        }
        return v;
    }

    /**
     * Pack up to 2 chars — optimised for the short-path {@code <= 8 char} branch.
     */
    private static long packCharsLow(CharSequence cs, int pos, int nChars) {
        long v = 0;
        for (int i = 0; i < nChars && pos + i < cs.length(); i++) {
            v |= ((long) cs.charAt(pos + i) & 0xFFFFL) << (i * 16);
        }
        return v;
    }

    /**
     * Read 4 chars from a char[] as a little-endian long (8 bytes).
     * Uses manual byte packing for cross-JDK compatibility.
     */
    private static long charToLongLE(char[] chars, int off) {
        return ((long) chars[off] & 0xFFFFL)
             | (((long) chars[off + 1] & 0xFFFFL) << 16)
             | (((long) chars[off + 2] & 0xFFFFL) << 32)
             | (((long) chars[off + 3] & 0xFFFFL) << 48);
    }

    /**
     * Read 2 chars from a char[] as a little-endian int (4 bytes).
     */
    private static int charToIntLE(char[] chars, int off) {
        return (chars[off] & 0xFFFF) | ((chars[off + 1] & 0xFFFF) << 16);
    }

    // ==========================================================================
    //  Core wyhash primitives
    // ==========================================================================

    private static long initSeed(long seed) {
        return seed ^ mix(seed ^ DEFAULT_SECRET[0], DEFAULT_SECRET[1]);
    }

    private static long mix(long a, long b) {
        long low = a * b;
        long high = Math.unsignedMultiplyHigh(a, b);
        return low ^ high;
    }

    private static long finish(long a, long b, long seed, long len) {
        long _a = a ^ DEFAULT_SECRET[1];
        long _b = b ^ seed;
        long low = _a * _b;
        long high = Math.multiplyHigh(_a, _b) + ((_a >> 63) & _b) + ((_b >> 63) & _a);
        return mix(low ^ DEFAULT_SECRET[0] ^ len, high ^ DEFAULT_SECRET[1]);
    }

    // ==========================================================================
    //  Low-level reads — byte[]
    // ==========================================================================

    private static long wyr3(byte[] data, int off, int k) {
        return ((data[off] & 0xFFL) << 16) | ((data[off + (k >> 1)] & 0xFFL) << 8) | (data[off + k - 1] & 0xFFL);
    }

    private static int getInt(byte[] b, int off) {
        return (int) INT_HANDLE.get(b, off);
    }

    private static long getLong(byte[] b, int off) {
        return (long) LONG_HANDLE.get(b, off);
    }

    // ==========================================================================
    //  Low-level reads — ByteBuffer
    // ==========================================================================

    private static long wyr3(java.nio.ByteBuffer data, int off, int k) {
        return ((data.get(off) & 0xFFL) << 16) | ((data.get(off + (k >> 1)) & 0xFFL) << 8)
                | (data.get(off + k - 1) & 0xFFL);
    }

    private static int getInt(java.nio.ByteBuffer b, int off) {
        return (int) BB_INT_HANDLE.get(b, off);
    }

    private static long getLong(java.nio.ByteBuffer b, int off) {
        return (long) BB_LONG_HANDLE.get(b, off);
    }

    // ==========================================================================
    //  Streaming hasher
    // ==========================================================================

    public static final class Streaming {
        private final long[] state = new long[3];
        private long totalLen;
        private final byte[] buf = new byte[48];
        private int bufLen;

        public Streaming(long seed) {
            long s = initSeed(seed);
            this.state[0] = s;
            this.state[1] = s;
            this.state[2] = s;
            this.totalLen = 0;
            this.bufLen = 0;
        }

        // ---- byte[] -------------------------------------------------------

        public void update(byte[] input) {
            update(input, 0, input.length);
        }

        public void update(byte[] input, int off, int len) {
            this.totalLen += len;

            if (len <= 48 - bufLen) {
                System.arraycopy(input, off, buf, bufLen, len);
                bufLen += len;
                return;
            }

            int i = 0;
            if (bufLen > 0) {
                i = 48 - bufLen;
                System.arraycopy(input, off, buf, bufLen, i);
                round(buf, 0);
                bufLen = 0;
            }

            while (i + 48 < len) {
                round(input, off + i);
                i += 48;
            }

            int remaining = len - i;
            if (remaining < 16 && i >= 48) {
                int rem = 16 - remaining;
                System.arraycopy(input, off + i - rem, buf, 48 - rem, rem);
            }
            System.arraycopy(input, off + i, buf, 0, remaining);
            bufLen = remaining;
        }

        // ---- String (zero allocation) -------------------------------------

        /**
         * Feed a {@link String} into the streaming hash without allocating a
         * {@code byte[]} copy. Latin-1 strings feed the internal byte[] directly
         * (1 byte per char). UTF-16 strings feed the internal byte[] as 2-byte
         * characters, handled by the byte[] path (2 bytes per char).
         */
        public void update(String str) {
            byte[] value = (byte[]) STRING_VALUE_HANDLE.get(str);
            byte coder = (byte) STRING_CODER_HANDLE.get(str);
            int len = str.length();
            if (coder == LATIN1) {
                update(value, 0, len);
            } else {
                // UTF-16: 2 bytes per character in the internal byte[]
                update(value, 0, len * 2);
            }
        }

        /**
         * Feed a range of characters from a {@link String}.
         * {@code off} and {@code len} are character-based offsets.
         */
        public void update(String str, int off, int len) {
            byte[] value = (byte[]) STRING_VALUE_HANDLE.get(str);
            byte coder = (byte) STRING_CODER_HANDLE.get(str);
            if (coder == LATIN1) {
                update(value, off, len);
            } else {
                update(value, off * 2, len * 2);
            }
        }

        // ---- char[] (zero allocation, manual byte packing) ---------------

        /**
         * Feed a {@code char[]} into the streaming hash without boxing or
         * copying. Reads chars directly and packs them into the internal
         * 48-byte buffer — no FFM API, no {@code sun.misc.Unsafe}.
         */
        public void update(char[] chars) {
            update(chars, 0, chars.length);
        }

        public void update(char[] chars, int off, int len) {
            if (len == 0) return;
            int charEnd = off + len;
            totalLen += (long) len * 2;

            // Drain existing buffer first
            if (bufLen > 0) {
                int bufAvail = 48 - bufLen;
                int charsToBuf = Math.min(len, bufAvail >>> 1);
                for (int i = 0; i < charsToBuf; i++) {
                    char c = chars[off + i];
                    int bi = bufLen + (i << 1);
                    buf[bi]     = (byte) (c & 0xFF);
                    buf[bi + 1] = (byte) (c >>> 8);
                }
                bufLen += charsToBuf << 1;
                off += charsToBuf;
                if (bufLen == 48) {
                    round(buf, 0);
                    bufLen = 0;
                }
                if (off >= charEnd) return;
            }

            // Process full 24-char = 48-byte blocks directly from char[]
            while (charEnd - off >= 24) {
                round(chars, off);
                off += 24;
            }

            // Remaining chars go into the buffer
            int remaining = charEnd - off;
            if (remaining > 0) {
                // Tail handling: match byte[] path's <16-bytes-after-round logic
                // 16 bytes = 8 chars
                if (remaining < 8 && off >= 24) {
                    // Copy the last 8 chars (16 bytes) of the last full block into buf[32..48]
                    for (int i = 0; i < 8; i++) {
                        char c = chars[off - 8 + i];
                        buf[32 + (i << 1)]     = (byte) (c & 0xFF);
                        buf[32 + (i << 1) + 1] = (byte) (c >>> 8);
                    }
                }
                for (int i = 0; i < remaining; i++) {
                    char c = chars[off + i];
                    int bi = i << 1;
                    buf[bi]     = (byte) (c & 0xFF);
                    buf[bi + 1] = (byte) (c >>> 8);
                }
                bufLen = remaining << 1;
            }
        }

        // ---- CharSequence (String shortcut, else char-by-char) -----------

        /**
         * Feed a {@link CharSequence} into the streaming hash.
         * String instances use the zero-allocation {@link #update(String)} path.
         * Other CharSequence implementations iterate character-by-character.
         */
        public void update(CharSequence cs) {
            if (cs instanceof String) {
                update((String) cs);
                return;
            }
            int len = cs.length();
            if (len == 0) return;
            totalLen += (long) len * 2;
            for (int pos = 0; pos < len; ) {
                int avail = 48 - bufLen;
                if (avail == 0) {
                    round(buf, 0);
                    bufLen = 0;
                    avail = 48;
                }
                int chunk = Math.min(len - pos, avail >>> 1);
                for (int i = 0; i < chunk; i++) {
                    char c = cs.charAt(pos + i);
                    int bi = bufLen + (i << 1);
                    buf[bi]     = (byte) (c & 0xFF);
                    buf[bi + 1] = (byte) (c >>> 8);
                }
                bufLen += chunk << 1;
                pos += chunk;
            }
        }

        // ---- Internal: 48-byte round from byte[] -------------------------

        private void round(byte[] input, int p) {
            state[0] = mix(getLong(input, p) ^ DEFAULT_SECRET[1], getLong(input, p + 8) ^ state[0]);
            state[1] = mix(getLong(input, p + 16) ^ DEFAULT_SECRET[2], getLong(input, p + 24) ^ state[1]);
            state[2] = mix(getLong(input, p + 32) ^ DEFAULT_SECRET[3], getLong(input, p + 40) ^ state[2]);
        }

        // ---- Internal: 48-byte round from char[] (24 chars) --------------

        private void round(char[] chars, int charOff) {
            state[0] = mix(charToLongLE(chars, charOff) ^ DEFAULT_SECRET[1],
                    charToLongLE(chars, charOff + 4) ^ state[0]);
            state[1] = mix(charToLongLE(chars, charOff + 8) ^ DEFAULT_SECRET[2],
                    charToLongLE(chars, charOff + 12) ^ state[1]);
            state[2] = mix(charToLongLE(chars, charOff + 16) ^ DEFAULT_SECRET[3],
                    charToLongLE(chars, charOff + 20) ^ state[2]);
        }

        // ---- Finalise ----------------------------------------------------

        public long finalHash() {
            long _a = 0, _b = 0;
            long[] _state = { state[0], state[1], state[2] };
            byte[] input = buf;
            int inputLen = bufLen;

            if (totalLen <= 16) {
                if (inputLen >= 4) {
                    int end = inputLen - 4;
                    int quarter = (inputLen >> 3) << 2;
                    _a = ((long) getInt(input, 0) << 32) | (getInt(input, quarter) & 0xFFFFFFFFL);
                    _b = ((long) getInt(input, end) << 32) | (getInt(input, end - quarter) & 0xFFFFFFFFL);
                } else if (inputLen > 0) {
                    _a = ((input[0] & 0xFFL) << 16) | ((input[inputLen >> 1] & 0xFFL) << 8)
                            | (input[inputLen - 1] & 0xFFL);
                    _b = 0;
                } else {
                    _a = 0;
                    _b = 0;
                }
            } else {
                byte[] scratch = null;
                if (inputLen < 16) {
                    int rem = 16 - inputLen;
                    scratch = new byte[16];
                    System.arraycopy(buf, 48 - rem, scratch, 0, rem);
                    System.arraycopy(buf, 0, scratch, rem, inputLen);
                    input = scratch;
                    inputLen = 16;
                }

                if (totalLen >= 48) {
                    _state[0] ^= _state[1] ^ _state[2];
                }

                int i = 0;
                while (i + 16 < inputLen) {
                    _state[0] = mix(getLong(input, i) ^ DEFAULT_SECRET[1], getLong(input, i + 8) ^ _state[0]);
                    i += 16;
                }

                _a = getLong(input, inputLen - 16);
                _b = getLong(input, inputLen - 8);
            }

            return finish(_a, _b, _state[0], totalLen);
        }
    }
}