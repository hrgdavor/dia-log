package hr.hrg.dialog.core;

import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;

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
 * On JDK 17+ with {@code --add-opens java.base/java.lang=ALL-UNNAMED},
 * {@link #hash(long, String)} accesses the internal {@code byte[] value} field
 * of {@link String} via {@link VarHandle} — no defensive copy, no
 * {@code str.getBytes()} allocation. Works with both Latin-1 (coder=0) and
 * UTF-16 (coder=1) compact strings.
 * </p>
 * <p>
 * On JDK 25+ where {@code java.lang} is fully encapsulated, or when the
 * {@code --add-opens} flag is absent, the implementation falls back to
 * {@link String#toCharArray()} automatically.
 * </p>
 * <p>
 * The selection between the two paths is made once at class initialization
 * via a strategy interface — the hot-path methods contain no runtime branching.
 * </p>
 * <h2>Zero-Allocation char[] Support</h2>
 * <p>
 * {@link #hash(long, char[])} reads multi-byte primitive values directly
 * from the {@code char[]} via manual byte packing — no allocation, no
 * {@code sun.misc.Unsafe}, no FFM API dependency.
 * </p>
 */
@ThreadSafe
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

    // -- String hasher strategy (selected once at class init) -------------------

    private interface StringHasher {
        long hash(long seed, String str);
        long hash(long seed, String str, int off, int len);
    }

    private static final StringHasher STRING_HASHER;

    static {
        StringHasher hasher;
        try {
            var lookup = MethodHandles.privateLookupIn(String.class, MethodHandles.lookup());
            VarHandle valueHandle = lookup.findVarHandle(String.class, "value", byte[].class);
            VarHandle coderHandle = lookup.findVarHandle(String.class, "coder", byte.class);
            hasher = new DirectStringHasher(valueHandle, coderHandle);
        } catch (Exception e) {
            // JDK 25+ encapsulation: fall back to toCharArray()
            hasher = new FallbackStringHasher();
        }
        STRING_HASHER = hasher;
    }

    // -- String hasher implementations -----------------------------------------

    private static final class DirectStringHasher implements StringHasher {
        private final VarHandle valueHandle;
        private final VarHandle coderHandle;

        DirectStringHasher(VarHandle valueHandle, VarHandle coderHandle) {
            this.valueHandle = valueHandle;
            this.coderHandle = coderHandle;
        }

        @Override
        public long hash(long seed, String str) {
            byte[] value = (byte[]) valueHandle.get(str);
            byte coder = (byte) coderHandle.get(str);
            int len = str.length();
            if (coder == 0) {
                return Wyhash64.hash(seed, value, 0, len);
            } else {
                // String.value is big-endian UTF-16; the char[] path packs
                // little-endian, so delegate to stay consistent with the
                // fallback hasher (hash(String) must not depend on add-opens).
                return Wyhash64.hash(seed, str.toCharArray());
            }
        }

        @Override
        public long hash(long seed, String str, int off, int len) {
            byte[] value = (byte[]) valueHandle.get(str);
            byte coder = (byte) coderHandle.get(str);
            if (coder == 0) {
                return Wyhash64.hash(seed, value, off, len);
            } else {
                char[] chars = new char[len];
                str.getChars(off, off + len, chars, 0);
                return Wyhash64.hash(seed, chars);
            }
        }
    }

    private static final class FallbackStringHasher implements StringHasher {
        @Override
        public long hash(long seed, String str) {
            // toCharArray() may allocate, but this is the fallback path when
            // VarHandle access to String.value is unavailable (JDK 25+ encapsulation).
            // Delegate to the char[] hash which correctly detects Latin-1 vs UTF-16
            // and handles Unicode characters (unlike getBytes(ISO_8859_1) which
            // silently replaces non-Latin-1 chars with '?').
            return Wyhash64.hash(seed, str.toCharArray());
        }

        @Override
        public long hash(long seed, String str, int off, int len) {
            char[] chars = new char[len];
            str.getChars(off, off + len, chars, 0);
            return Wyhash64.hash(seed, chars);
        }
    }

    // -- Streaming string hasher strategy (selected once at class init) ---------

    private interface StreamingStringUpdater {
        void update(Streaming streaming, String str);
        void update(Streaming streaming, String str, int off, int len);
    }

    private static final StreamingStringUpdater STREAMING_STRING_UPDATER;

    static {
        StreamingStringUpdater updater;
        try {
            var lookup = MethodHandles.privateLookupIn(String.class, MethodHandles.lookup());
            VarHandle valueHandle = lookup.findVarHandle(String.class, "value", byte[].class);
            VarHandle coderHandle = lookup.findVarHandle(String.class, "coder", byte.class);
            updater = new DirectStreamingStringUpdater(valueHandle, coderHandle);
        } catch (Exception e) {
            updater = new FallbackStreamingStringUpdater();
        }
        STREAMING_STRING_UPDATER = updater;
    }

    private static final class DirectStreamingStringUpdater implements StreamingStringUpdater {
        private final VarHandle valueHandle;
        private final VarHandle coderHandle;

        DirectStreamingStringUpdater(VarHandle valueHandle, VarHandle coderHandle) {
            this.valueHandle = valueHandle;
            this.coderHandle = coderHandle;
        }

        @Override
        public void update(Streaming streaming, String str) {
            byte[] value = (byte[]) valueHandle.get(str);
            byte coder = (byte) coderHandle.get(str);
            int len = str.length();
            if (coder == 0) {
                streaming.update(value, 0, len);
            } else {
                // big-endian String.value must not be fed to the LE char path
                streaming.update(str.toCharArray());
            }
        }

        @Override
        public void update(Streaming streaming, String str, int off, int len) {
            byte[] value = (byte[]) valueHandle.get(str);
            byte coder = (byte) coderHandle.get(str);
            if (coder == 0) {
                streaming.update(value, off, len);
            } else {
                char[] chars = new char[len];
                str.getChars(off, off + len, chars, 0);
                streaming.update(chars);
            }
        }
    }

    private static final class FallbackStreamingStringUpdater implements StreamingStringUpdater {
        @Override
        public void update(Streaming streaming, String str) {
            // Delegate to char[] path which correctly handles Unicode
            // (unlike getBytes(ISO_8859_1) which loses non-Latin-1 chars).
            streaming.update(str.toCharArray());
        }

        @Override
        public void update(Streaming streaming, String str, int off, int len) {
            char[] chars = new char[len];
            str.getChars(off, off + len, chars, 0);
            streaming.update(chars);
        }
    }

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
    //  Public API — String  (zero allocation when possible)
    // ==========================================================================

    /**
     * Hash a {@link String} without allocating a {@code byte[]} copy.
     * <p>
     * On JDK 17+ with {@code --add-opens}, accesses the internal {@code byte[] value}
     * field via {@link VarHandle}. On JDK 25+ without opens, falls back to
     * {@link String#toCharArray()}.
     * </p>
     * <p>
     * The strategy is selected once at class initialization — no runtime branching.
     * </p>
     */
    public static long hash(long seed, String str) {
        return STRING_HASHER.hash(seed, str);
    }

    /**
     * Hash a {@link String} with offset and length (in characters, not bytes).
     */
    public static long hash(long seed, String str, int off, int len) {
        return STRING_HASHER.hash(seed, str, off, len);
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
     */
    public static long hash(long seed, char[] chars) {
        return hash(seed, chars, 0, chars.length);
    }

    /**
     * Hash a {@code char[]} with offset and length.
     * <p>Auto-detects encoding: if all chars are Latin-1 (≤ 0xFF), each char is
     * treated as a single byte. Otherwise, each char is treated as 2 bytes
     * (UTF-16 little-endian). This ensures
     * {@code hash(seed, str.toCharArray()) == hash(seed, str)} for both
     * Latin-1 and UTF-16 strings.</p>
     */
    public static long hash(long seed, char[] chars, int off, int len) {
        // Scan for non-Latin-1 chars to determine encoding
        boolean utf16 = false;
        for (int i = 0; i < len; i++) {
            if ((chars[off + i] & 0xFFFF) > 0xFF) {
                utf16 = true;
                break;
            }
        }
        if (utf16) {
            return hashUtf16(seed, chars, off, len);
        }
        return hashLatin1(seed, chars, off, len);
    }

    /** Hash a char[] as Latin-1 (1 byte per char). */
    private static long hashLatin1(long seed, char[] chars, int off, int len) {
        long s = initSeed(seed);
        long secret1 = DEFAULT_SECRET[1];
        long secret2 = DEFAULT_SECRET[2];
        long secret3 = DEFAULT_SECRET[3];

        long a, b;

        if (len <= 16) {
            if (len >= 4) {
                a = ((long) charsToInt(chars, off) << 32) | (charsToInt(chars, off + ((len >> 3) << 2)) & 0xFFFFFFFFL);
                b = ((long) charsToInt(chars, off + len - 4) << 32)
                        | (charsToInt(chars, off + len - 4 - ((len >> 3) << 2)) & 0xFFFFFFFFL);
            } else if (len > 0) {
                a = charsToWyr3(chars, off, len);
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
                see0 = mix(charsToLong(chars, p) ^ secret1, charsToLong(chars, p + 8) ^ see0);
                see1 = mix(charsToLong(chars, p + 16) ^ secret2, charsToLong(chars, p + 24) ^ see1);
                see2 = mix(charsToLong(chars, p + 32) ^ secret3, charsToLong(chars, p + 40) ^ see2);
                p += 48;
                i -= 48;
            }
            see0 ^= see1 ^ see2;
            while (i > 16) {
                see0 = mix(charsToLong(chars, p) ^ secret1, charsToLong(chars, p + 8) ^ see0);
                i -= 16;
                p += 16;
            }
            a = charsToLong(chars, off + len - 16);
            b = charsToLong(chars, off + len - 8);
            s = see0;
        }

        return finish(a, b, s, len);
    }

    /** Hash a char[] as UTF-16 little-endian (2 bytes per char). */
    private static long hashUtf16(long seed, char[] chars, int off, int len) {
        long s = initSeed(seed);
        long secret1 = DEFAULT_SECRET[1];
        long secret2 = DEFAULT_SECRET[2];
        long secret3 = DEFAULT_SECRET[3];
        int byteLen = len * 2;

        long a, b;

        if (byteLen <= 16) {
            if (byteLen >= 4) {
                // Mirror the byte[] path's read32-interleave formulas (all int
                // reads land on even byte offsets, so charToIntLE applies).
                // This keeps hash(char[]) consistent with hash(byte[]) of the
                // UTF-16 little-endian bytes for every length <= 16.
                int q = (byteLen >> 3) << 2;
                a = ((long) charToIntLE(chars, off) << 32)
                        | (charToIntLE(chars, off + (q >> 1)) & 0xFFFFFFFFL);
                b = ((long) charToIntLE(chars, off + ((byteLen - 4) >> 1)) << 32)
                        | (charToIntLE(chars, off + ((byteLen - 4 - q) >> 1)) & 0xFFFFFFFFL);
            } else if (byteLen > 0) {
                // 1 char: read as 2 bytes, using the same wyr3 layout as the
                // byte[]/Latin-1 paths so hash(char[]) of a single UTF-16 char
                // agrees with hash(byte[]) of its little-endian bytes.
                a = ((chars[off] & 0xFFL) << 16)
                  | (((chars[off] >> 8) & 0xFFL) << 8)
                  | ((chars[off] >> 8) & 0xFFL);
                b = 0;
            } else {
                a = 0;
                b = 0;
            }
        } else {
            int i = byteLen;
            int p = off;
            long see0 = s;
            long see1 = s;
            long see2 = s;

            while (i > 48) {
                see0 = mix(charToLongLE(chars, p) ^ secret1, charToLongLE(chars, p + 4) ^ see0);
                see1 = mix(charToLongLE(chars, p + 8) ^ secret2, charToLongLE(chars, p + 12) ^ see1);
                see2 = mix(charToLongLE(chars, p + 16) ^ secret3, charToLongLE(chars, p + 20) ^ see2);
                p += 24;
                i -= 48;
            }
            see0 ^= see1 ^ see2;
            while (i > 16) {
                see0 = mix(charToLongLE(chars, p) ^ secret1, charToLongLE(chars, p + 4) ^ see0);
                i -= 16;
                p += 8;
            }
            a = charToLongLESafe(chars, off + len - 8, off + len);
            b = charToLongLESafe(chars, off + len - 4, off + len);
            s = see0;
        }

        return finish(a, b, s, byteLen);
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
     */
    public static long hash(long seed, CharSequence cs) {
        if (cs instanceof String) {
            return STRING_HASHER.hash(seed, (String) cs);
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
        if (len <= 16) {
            if (len >= 4) {
                a = ((long) csToInt(cs, 0) << 32) | (csToInt(cs, ((len >> 3) << 2)) & 0xFFFFFFFFL);
                b = ((long) csToInt(cs, len - 4) << 32)
                        | (csToInt(cs, len - 4 - ((len >> 3) << 2)) & 0xFFFFFFFFL);
            } else if (len > 0) {
                a = csToWyr3(cs, 0, len);
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

            while (i > 48) {
                seed0 = mix(csToLong(cs, pos) ^ secret1, csToLong(cs, pos + 8) ^ seed0);
                seed1 = mix(csToLong(cs, pos + 16) ^ secret2, csToLong(cs, pos + 24) ^ seed1);
                seed2 = mix(csToLong(cs, pos + 32) ^ secret3, csToLong(cs, pos + 40) ^ seed2);
                pos += 48;
                i -= 48;
            }
            seed0 ^= seed1 ^ seed2;
            while (i > 16) {
                seed0 = mix(csToLong(cs, pos) ^ secret1, csToLong(cs, pos + 8) ^ seed0);
                i -= 16;
                pos += 16;
            }
            a = csToLong(cs, len - 16);
            b = csToLong(cs, len - 8);
            s = seed0;
        }
        return finish(a, b, s, len);
    }

    // ==========================================================================
    //  Internal helpers — CharSequence & char[] packing
    // ==========================================================================

    private static long charToLongLE(char[] chars, int off) {
        return ((long) chars[off] & 0xFFFFL)
             | (((long) chars[off + 1] & 0xFFFFL) << 16)
             | (((long) chars[off + 2] & 0xFFFFL) << 32)
             | (((long) chars[off + 3] & 0xFFFFL) << 48);
    }

    /**
     * Bounds-safe variant of {@link #charToLongLE(char[], int)}.
     * Reads up to 4 chars as a little-endian long, padding missing chars with 0.
     * This prevents {@code ArrayIndexOutOfBoundsException} when fewer than 4
     * chars remain at the end of the array.
     */
    private static long charToLongLESafe(char[] chars, int off, int charLen) {
        long v = 0;
        int remaining = charLen - off;
        if (remaining <= 0) return 0;
        v |= ((long) chars[off] & 0xFFFFL);
        if (remaining <= 1) return v;
        v |= (((long) chars[off + 1] & 0xFFFFL) << 16);
        if (remaining <= 2) return v;
        v |= (((long) chars[off + 2] & 0xFFFFL) << 32);
        if (remaining <= 3) return v;
        v |= (((long) chars[off + 3] & 0xFFFFL) << 48);
        return v;
    }

    private static int charToIntLE(char[] chars, int off) {
        return (chars[off] & 0xFFFF) | ((chars[off + 1] & 0xFFFF) << 16);
    }

    /** Read 8 chars as 8 individual bytes packed into a little-endian long. */
    private static long charsToLong(char[] chars, int off) {
        return ((long) latin1Byte(chars[off]))
             | ((long) latin1Byte(chars[off + 1]) << 8)
             | ((long) latin1Byte(chars[off + 2]) << 16)
             | ((long) latin1Byte(chars[off + 3]) << 24)
             | ((long) latin1Byte(chars[off + 4]) << 32)
             | ((long) latin1Byte(chars[off + 5]) << 40)
             | ((long) latin1Byte(chars[off + 6]) << 48)
             | ((long) latin1Byte(chars[off + 7]) << 56);
    }

    /** Read 4 chars as 4 individual bytes packed into a little-endian int. */
    private static int charsToInt(char[] chars, int off) {
        return latin1Byte(chars[off])
             | (latin1Byte(chars[off + 1]) << 8)
             | (latin1Byte(chars[off + 2]) << 16)
             | (latin1Byte(chars[off + 3]) << 24);
    }

    /** Read up to 3 chars as individual bytes, matching wyr3(byte[]) layout. */
    private static long charsToWyr3(char[] chars, int off, int k) {
        return ((long) latin1Byte(chars[off]) << 16)
             | ((long) latin1Byte(chars[off + (k >> 1)]) << 8)
             | (long) latin1Byte(chars[off + k - 1]);
    }

    /**
     * Convert a char to a Latin-1 byte, matching {@code getBytes(ISO_8859_1)}
     * semantics: chars > 0xFF are replaced with {@code '?'} (0x3F).
     */
    private static int latin1Byte(char c) {
        return c <= 0xFF ? (c & 0xFF) : 0x3F;
    }

    // -- CharSequence helpers (Latin-1 single-byte encoding) -------------------

    /** Read 8 chars from a CharSequence as 8 Latin-1 bytes packed into a little-endian long. */
    private static long csToLong(CharSequence cs, int pos) {
        return ((long) latin1Byte(cs.charAt(pos)))
             | ((long) latin1Byte(cs.charAt(pos + 1)) << 8)
             | ((long) latin1Byte(cs.charAt(pos + 2)) << 16)
             | ((long) latin1Byte(cs.charAt(pos + 3)) << 24)
             | ((long) latin1Byte(cs.charAt(pos + 4)) << 32)
             | ((long) latin1Byte(cs.charAt(pos + 5)) << 40)
             | ((long) latin1Byte(cs.charAt(pos + 6)) << 48)
             | ((long) latin1Byte(cs.charAt(pos + 7)) << 56);
    }

    /** Read 4 chars from a CharSequence as 4 Latin-1 bytes packed into a little-endian int. */
    private static int csToInt(CharSequence cs, int pos) {
        return latin1Byte(cs.charAt(pos))
             | (latin1Byte(cs.charAt(pos + 1)) << 8)
             | (latin1Byte(cs.charAt(pos + 2)) << 16)
             | (latin1Byte(cs.charAt(pos + 3)) << 24);
    }

    /** Read up to 3 chars from a CharSequence as Latin-1 bytes, matching wyr3(byte[]) layout. */
    private static long csToWyr3(CharSequence cs, int pos, int k) {
        return ((long) latin1Byte(cs.charAt(pos)) << 16)
             | ((long) latin1Byte(cs.charAt(pos + (k >> 1))) << 8)
             | (long) latin1Byte(cs.charAt(pos + k - 1));
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

    @NotThreadSafe
    public static final class Streaming {
        private final long[] state = new long[3];
        private long totalLen;
        private final byte[] buf = new byte[48];
        private int bufLen;

        public Streaming(long seed) {
            reset(seed);
        }

        /**
         * Resets this streaming hasher instance for reuse.
         *
         * @param seed new seed value
         */
        public void reset(long seed) {
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

        /**
         * Feed a single byte into the streaming hash.
         * <p>
         * This is optimized for high-frequency delimiters in call sites that
         * would otherwise route through update(byte[], off, len) with len=1.
         * </p>
         */
        public void updateByte(byte b) {
            this.totalLen += 1;
            if (bufLen == 48) {
                round(buf, 0);
                bufLen = 0;
            }
            buf[bufLen++] = b;
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

        // ---- String (zero allocation when possible) -----------------------

        /**
         * Feed a {@link String} into the streaming hash.
         * <p>
         * On JDK 17+ with {@code --add-opens}, accesses the internal byte[]
         * directly (zero allocation). On JDK 25+ without opens, falls back to
         * {@link String#toCharArray()}.
         * </p>
         * <p>
         * The strategy is selected once at class initialization — no runtime branching.
         * </p>
         */
        public void update(String str) {
            STREAMING_STRING_UPDATER.update(this, str);
        }

        /**
         * Feed a range of characters from a {@link String}.
         * {@code off} and {@code len} are character-based offsets.
         */
        public void update(String str, int off, int len) {
            STREAMING_STRING_UPDATER.update(this, str, off, len);
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

            // Scan for non-Latin-1 chars to determine encoding
            boolean utf16 = false;
            for (int i = 0; i < len; i++) {
                if ((chars[off + i] & 0xFFFF) > 0xFF) {
                    utf16 = true;
                    break;
                }
            }

            if (utf16) {
                updateUtf16(chars, off, len);
            } else {
                updateLatin1(chars, off, len);
            }
        }

        private void updateLatin1(char[] chars, int off, int len) {
            if (len == 0) return;
            int charEnd = off + len;
            totalLen += len; // Latin-1: 1 byte per char

            // Drain existing buffer first
            if (bufLen > 0) {
                int bufAvail = 48 - bufLen;
                int charsToBuf = Math.min(len, bufAvail);
                for (int i = 0; i < charsToBuf; i++) {
                    buf[bufLen + i] = (byte) latin1Byte(chars[off + i]);
                }
                bufLen += charsToBuf;
                off += charsToBuf;
                if (bufLen == 48 && off < charEnd) {
                    // Round only when more input follows. If this update ends with
                    // the buffer exactly full, defer the round: the final 48-byte
                    // block must stay unrounded (like the byte[] path) so finalHash
                    // processes it in 16-byte steps and matches hash(byte[]).
                    round(buf, 0);
                    bufLen = 0;
                }
                if (off >= charEnd) return;
            }

            // Process full 48-char blocks directly from char[] as single bytes.
            // Strictly greater: the last 48 chars of the stream stay in buf,
            // unrounded, for finalHash to process as 16-byte steps.
            while (charEnd - off > 48) {
                round(chars, off);
                off += 48;
            }

            // Remaining chars go into the buffer
            int remaining = charEnd - off;
            if (remaining > 0) {
                // Tail handling: match byte[] path's <16-bytes-after-round logic
                if (remaining < 16 && off >= 48) {
                    // Copy the last 16 bytes of the last full block into buf[32..48]
                    for (int i = 0; i < 16; i++) {
                        buf[32 + i] = (byte) latin1Byte(chars[off - 16 + i]);
                    }
                }
                for (int i = 0; i < remaining; i++) {
                    buf[i] = (byte) latin1Byte(chars[off + i]);
                }
                bufLen = remaining;
            }
        }

        private void updateUtf16(char[] chars, int off, int len) {
            if (len == 0) return;
            int charEnd = off + len;
            totalLen += len * 2; // UTF-16: 2 bytes per char

            // Drain existing buffer first
            if (bufLen > 0) {
                int bufAvail = 48 - bufLen;
                int charsToBuf = Math.min(len, bufAvail / 2);
                for (int i = 0; i < charsToBuf; i++) {
                    char c = chars[off + i];
                    buf[bufLen + i * 2] = (byte) (c & 0xFF);
                    buf[bufLen + i * 2 + 1] = (byte) ((c >> 8) & 0xFF);
                }
                bufLen += charsToBuf * 2;
                off += charsToBuf;
                if (bufLen == 48 && off < charEnd) {
                    // Defer the round when this update ends with the buffer
                    // exactly full (see updateLatin1) so the final 48-byte block
                    // is processed by finalHash in 16-byte steps.
                    round(buf, 0);
                    bufLen = 0;
                } else if (bufLen == 47 && off < charEnd) {
                    // Odd pending byte count with more chars to come: the next
                    // char's low byte completes the 48-byte block. Split the
                    // char across the boundary — low byte finishes the block,
                    // high byte starts the new pending region (bufLen = 1).
                    char c = chars[off];
                    buf[47] = (byte) (c & 0xFF);
                    round(buf, 0);
                    buf[0] = (byte) ((c >> 8) & 0xFF);
                    bufLen = 1;
                    off++;
                }
                if (off >= charEnd) return;
            }

            if (bufLen == 0) {
                // Process full 24-char (48-byte) blocks. Strictly greater: the
                // last 24 chars of the stream stay packed in buf, unrounded,
                // for finalHash to process as 16-byte steps.
                while (charEnd - off > 24) {
                    // Pack 24 chars as 48 bytes (LE) into buf and round
                    for (int i = 0; i < 24; i++) {
                        char c = chars[off + i];
                        buf[i * 2] = (byte) (c & 0xFF);
                        buf[i * 2 + 1] = (byte) ((c >> 8) & 0xFF);
                    }
                    round(buf, 0);
                    off += 24;
                }

                // Remaining chars go into the buffer
                int remainingChars = charEnd - off;
                if (remainingChars > 0) {
                    int remainingBytes = remainingChars * 2;
                    // Tail handling: match byte[] path's <16-bytes-after-round logic.
                    // Since we pack chars into buf and round from buf, the last full
                    // block's bytes (including the tail prefix) are already in buf[32..47].
                    // No copy needed — finalHash will find them at buf[48-(16-remainingBytes)..47].
                    for (int i = 0; i < remainingChars; i++) {
                        char c = chars[off + i];
                        buf[i * 2] = (byte) (c & 0xFF);
                        buf[i * 2 + 1] = (byte) ((c >> 8) & 0xFF);
                    }
                    bufLen = remainingBytes;
                }
            } else {
                // bufLen == 1: an odd pending byte (from the char split above).
                // Append the rest char-by-char, splitting at the 48-byte boundary
                // when a char would straddle it.
                appendUtf16(chars, off, charEnd);
            }
        }

        /**
         * Append the UTF-16 little-endian bytes of {@code chars[off..charEnd)}
         * to the pending buffer, rounding full 48-byte blocks. Handles an odd
         * pending byte count by splitting a char across the block boundary
         * (low byte completes the block, high byte starts the new tail).
         */
        private void appendUtf16(char[] chars, int off, int charEnd) {
            while (off < charEnd) {
                int avail = 48 - bufLen;
                if (avail == 0) {
                    round(buf, 0);
                    bufLen = 0;
                    avail = 48;
                }
                if (avail == 1) {
                    // Only one byte slot left: split the char so its low byte
                    // completes the 48-byte block and its high byte becomes the
                    // first pending byte of the next region.
                    char c = chars[off++];
                    buf[47] = (byte) (c & 0xFF);
                    round(buf, 0);
                    buf[0] = (byte) ((c >> 8) & 0xFF);
                    bufLen = 1;
                    continue;
                }
                int charsToTake = Math.min(charEnd - off, avail / 2);
                for (int i = 0; i < charsToTake; i++) {
                    char c = chars[off + i];
                    buf[bufLen + i * 2] = (byte) (c & 0xFF);
                    buf[bufLen + i * 2 + 1] = (byte) ((c >> 8) & 0xFF);
                }
                bufLen += charsToTake * 2;
                off += charsToTake;
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
                STREAMING_STRING_UPDATER.update(this, (String) cs);
                return;
            }
            int len = cs.length();
            if (len == 0) return;
            totalLen += len; // CharSequence is treated as Latin-1 bytes
            for (int pos = 0; pos < len; ) {
                int avail = 48 - bufLen;
                if (avail == 0) {
                    round(buf, 0);
                    bufLen = 0;
                    avail = 48;
                }
                int chunk = Math.min(len - pos, avail);
                for (int i = 0; i < chunk; i++) {
                    buf[bufLen + i] = (byte) latin1Byte(cs.charAt(pos + i));
                }
                bufLen += chunk;
                pos += chunk;
            }
        }

        // ---- Internal: 48-byte round from byte[] -------------------------

        private void round(byte[] input, int p) {
            state[0] = mix(getLong(input, p) ^ DEFAULT_SECRET[1], getLong(input, p + 8) ^ state[0]);
            state[1] = mix(getLong(input, p + 16) ^ DEFAULT_SECRET[2], getLong(input, p + 24) ^ state[1]);
            state[2] = mix(getLong(input, p + 32) ^ DEFAULT_SECRET[3], getLong(input, p + 40) ^ state[2]);
        }

        // ---- Internal: 48-byte round from char[] (48 chars as single bytes)

        private void round(char[] chars, int charOff) {
            state[0] = mix(Wyhash64.charsToLong(chars, charOff) ^ DEFAULT_SECRET[1],
                    Wyhash64.charsToLong(chars, charOff + 8) ^ state[0]);
            state[1] = mix(Wyhash64.charsToLong(chars, charOff + 16) ^ DEFAULT_SECRET[2],
                    Wyhash64.charsToLong(chars, charOff + 24) ^ state[1]);
            state[2] = mix(Wyhash64.charsToLong(chars, charOff + 32) ^ DEFAULT_SECRET[3],
                    Wyhash64.charsToLong(chars, charOff + 40) ^ state[2]);
        }

        // ---- Finalise ----------------------------------------------------

        public long finalHash() {
            long _a = 0, _b = 0;
            // Local copies of the state — finalHash must not mutate the
            // streaming instance (it may be called again or the hasher reused
            // via reset()). Locals instead of a long[] avoid any allocation.
            long s0 = state[0], s1 = state[1], s2 = state[2];
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
                if (totalLen >= 48) {
                    s0 ^= s1 ^ s2;
                }

                if (inputLen >= 16) {
                    int i = 0;
                    while (i + 16 < inputLen) {
                        s0 = mix(getLong(input, i) ^ DEFAULT_SECRET[1], getLong(input, i + 8) ^ s0);
                        i += 16;
                    }
                    _a = getLong(input, inputLen - 16);
                    _b = getLong(input, inputLen - 8);
                } else {
                    // Fewer than 16 pending bytes. The final 16-byte window is
                    // split across two regions of buf: the last `rem` bytes of
                    // the last full 48-byte block (kept at buf[48-rem..48)) and
                    // the pending tail (buf[0..inputLen)). Read both longs
                    // directly from those regions — no scratch array allocation.
                    int rem = 16 - inputLen;
                    if (rem == 16) {
                        // No pending tail: the window is the last 16 bytes of the block.
                        _a = getLong(buf, 32);
                        _b = getLong(buf, 40);
                    } else if (rem > 8) {
                        // Tail <= 8 bytes: _a lies fully inside the cached prefix,
                        // _b wraps from the prefix into the tail.
                        _a = getLong(buf, 48 - rem);
                        int shift = 8 * (16 - rem);
                        _b = (getLong(buf, 40) >>> shift)
                           | ((getLong(buf, 0) & ((1L << shift) - 1)) << (8 * (rem - 8)));
                    } else if (rem == 8) {
                        _a = getLong(buf, 40);
                        _b = getLong(buf, 0);
                    } else {
                        // Tail >= 9 bytes: _b lies fully inside the tail,
                        // _a wraps from the cached prefix into the tail.
                        _b = getLong(buf, 8 - rem);
                        int shift = 8 * (8 - rem);
                        _a = (getLong(buf, 40) >>> shift)
                           | ((getLong(buf, 0) & ((1L << shift) - 1)) << (8 * rem));
                    }
                }
            }

            return finish(_a, _b, s0, totalLen);
        }
    }
}
