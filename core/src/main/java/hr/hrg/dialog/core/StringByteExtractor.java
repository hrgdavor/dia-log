package hr.hrg.dialog.core;

import javax.annotation.concurrent.ThreadSafe;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Streams a {@link String}'s bytes to an {@link OutputStream}. When the JVM is
 * started with {@code --add-opens java.base/java.lang=ALL-UNNAMED}, reads the
 * backing {@code byte[]}/{@code coder} via {@code VarHandle} with zero copying;
 * otherwise falls back to a classic {@code getBytes()} path. Thread-safe.
 */
@ThreadSafe
public final class StringByteExtractor {

    // Functional interface designed to align with Future Java MethodHandles / ConstantBootstraps
    @FunctionalInterface
    public interface ByteWriter {
        void write(OutputStream out, String s) throws IOException;
    }

    /**
     * Statically evaluated holder. This static initialization block serves as the 
     * precise boundary that can be swapped with a Lazy Constant / Condy in future JDKs.
     */
    private static final class StrategyHolder {
        static final boolean HAS_ADD_OPENS;
        static final ByteWriter ACTIVE_STRATEGY;

        static {
            boolean addOpensSupported = false;
            ByteWriter strategy = StringByteExtractor::writeClassic; // Fallback default

            try {
                // Attempt to reflectively access String.value and String.coder
                MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(String.class, MethodHandles.lookup());
                VarHandle valueHandle = lookup.findVarHandle(String.class, "value", byte[].class);
                VarHandle coderHandle = lookup.findVarHandle(String.class, "coder", byte.class);

                // Sanity test VarHandles on a dummy string to ensure no JVM access security errors
                String testStr = "test";
                byte coder = (byte) coderHandle.get(testStr);
                byte[] val = (byte[]) valueHandle.get(testStr);

                if (coder == 0 && val != null && val.length == 4) {
                    addOpensSupported = true;
                    // Bind zero-allocation VarHandle closure
                    strategy = (out, str) -> writeVarHandle(out, str, valueHandle, coderHandle);
                }
            } catch (Throwable ignored) {
                // SecurityException, IllegalAccessException, or InaccessibleObjectException (--add-opens missing)
                addOpensSupported = false;
            }

            HAS_ADD_OPENS = addOpensSupported;
            ACTIVE_STRATEGY = strategy;
        }
    }

    private StringByteExtractor() {}

    // =========================================================================
    // SWAR word scanning (T1/T2)
    //
    // Ported from Apache Fory commit 585eb16f ("feat(java): optimize json
    // perf", PR #3871). A word of 8 Latin-1 bytes is loaded little-endian and
    // classified with one AND + one compare: any byte >= 0x80 sets the
    // corresponding high bit, so `(word & HIGH_BITS) == 0` proves all 8 bytes
    // are ASCII and can be emitted as one bulk chunk. Only dirty words pay the
    // per-byte cost. The load uses a byte-array view VarHandle (no --add-opens
    // needed) and is byte-order portable: byte i always lands in bits 8*i..8*i+7.
    // =========================================================================

    private static final VarHandle LE_WORD =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
    private static final long HIGH_BITS = 0x8080808080808080L;

    /**
     * Returns true if the JVM is running the String value/coder VarHandle probe
     * (add-opens) — retained for API compatibility.
     */
    public static boolean isVarHandleSupported() {
        return StrategyHolder.HAS_ADD_OPENS;
    }

    public static final ByteWriter getStrategy() {
        return StrategyHolder.ACTIVE_STRATEGY;
    }

    /**
     * Primary entry point. Writes ASCII string bytes to the output stream.
     */
    public static void writeAsciiDirect(OutputStream out, String s) throws IOException {
        if (s == null || s.isEmpty()) return;
        StrategyHolder.ACTIVE_STRATEGY.write(out, s);
    }

    // =========================================================================
    // IMPLEMENTATION 1: VarHandle (Zero Allocation)
    // =========================================================================

    public static void writeVarHandle(OutputStream out, String s, VarHandle valueHandle, VarHandle coderHandle) throws IOException {
        byte coder = (byte) coderHandle.get(s);

        if (coder == 0) { // LATIN1 (compact string): one byte per char
            byte[] internalValue = (byte[]) valueHandle.get(s);
            writeLatin1(out, internalValue);
        } else {
            // Safety fallback for non-Latin1 strings (UTF-16)
            writeClassic(out, s);
        }
    }

    /**
     * Writes a Latin-1 compact-string byte array to the output stream, encoding it to UTF-8 in a
     * single pass (no intermediate allocation). Latin-1 bytes are identical to UTF-8 only for ASCII
     * (0x00-0x7F); Latin-1 extended chars (0x80-0xFF) are expanded to 2-byte UTF-8 sequences.
     * <p>
     * T1: 8-byte words are scanned with one high-bit test; clean ASCII words extend a bulk
     * {@code write(byte[], off, len)} chunk (the documented ~51x cheaper form), dirty words pay the
     * per-byte cost. T2: the word loop naturally specializes short strings (a single word plus a
     * &lt;8-byte per-byte tail).
     * <p>
     * When {@code out} is a {@link ReusableByteArrayOutputStream} the bytes are copied straight
     * into its backing array through the direct-buffer API (T4) instead of OutputStream calls.
     *
     * @param out target stream
     * @param latin1Bytes the internal {@code byte[]} of a Latin-1 compact string (coder == 0)
     * @throws IOException if writing fails
     */
    public static void writeLatin1(OutputStream out, byte[] latin1Bytes) throws IOException {
        if (out instanceof ReusableByteArrayOutputStream rbo) {
            writeLatin1Direct(rbo, latin1Bytes);
            return;
        }
        writeLatin1Stream(out, latin1Bytes, 0, latin1Bytes.length);
    }

    private static void writeLatin1Stream(OutputStream out, byte[] bytes, int from, int to) throws IOException {
        int segmentStart = from;
        int i = from;
        int wordEnd = to - 7;
        for (; i < wordEnd; i += 8) {
            long word = (long) LE_WORD.get(bytes, i);
            if ((word & HIGH_BITS) == 0) {
                continue; // 8 clean ASCII bytes, still part of the current segment
            }
            if (i > segmentStart) {
                out.write(bytes, segmentStart, i - segmentStart);
            }
            // Dirty word: emit escapes for the 0x80..0xFF bytes, batching any clean runs inside it.
            int runStart = i;
            for (int j = i; j < i + 8; j++) {
                int v = bytes[j] & 0xFF;
                if (v >= 0x80) {
                    if (j > runStart) {
                        out.write(bytes, runStart, j - runStart);
                    }
                    out.write(0xC0 | (v >> 6));       // 110xxxxx
                    out.write(0x80 | (v & 0x3F));     // 10xxxxxx
                    runStart = j + 1;
                }
            }
            if (i + 8 > runStart) {
                out.write(bytes, runStart, i + 8 - runStart);
            }
            segmentStart = i + 8;
        }
        // Tail (< 8 bytes): per-byte
        for (; i < to; i++) {
            int v = bytes[i] & 0xFF;
            if (v >= 0x80) {
                if (i > segmentStart) {
                    out.write(bytes, segmentStart, i - segmentStart);
                }
                out.write(0xC0 | (v >> 6));       // 110xxxxx
                out.write(0x80 | (v & 0x3F));     // 10xxxxxx
                segmentStart = i + 1;
            }
        }
        if (segmentStart < to) {
            out.write(bytes, segmentStart, to - segmentStart);
        }
    }

    private static void writeLatin1Direct(ReusableByteArrayOutputStream rbo, byte[] bytes) throws IOException {
        int to = bytes.length;
        int i = 0;
        int wordEnd = to - 7;
        int segmentStart = 0;
        for (; i < wordEnd; i += 8) {
            long word = (long) LE_WORD.get(bytes, i);
            if ((word & HIGH_BITS) == 0) {
                continue; // clean ASCII word stays inside the pending segment
            }
            // Dirty word: copy the pending clean segment, then emit escapes for the
            // 0x80..0xFF bytes, batching any clean runs inside the word.
            copyDirect(rbo, bytes, segmentStart, i);
            int runStart = i;
            for (int j = i; j < i + 8; j++) {
                int v = bytes[j] & 0xFF;
                if (v >= 0x80) {
                    copyDirect(rbo, bytes, runStart, j);
                    int pos = rbo.position();
                    byte[] buf = rbo.buffer();
                    if (pos + 2 > buf.length) {
                        rbo.grow(pos + 2);
                        buf = rbo.buffer();
                    }
                    buf[pos] = (byte) (0xC0 | (v >> 6));   // 110xxxxx
                    buf[pos + 1] = (byte) (0x80 | (v & 0x3F)); // 10xxxxxx
                    rbo.setPosition(pos + 2);
                    runStart = j + 1;
                }
            }
            copyDirect(rbo, bytes, runStart, i + 8);
            segmentStart = i + 8;
        }
        // Tail (< 8 bytes): per-byte
        for (; i < to; i++) {
            int v = bytes[i] & 0xFF;
            if (v >= 0x80) {
                copyDirect(rbo, bytes, segmentStart, i);
                int pos = rbo.position();
                byte[] buf = rbo.buffer();
                if (pos + 2 > buf.length) {
                    rbo.grow(pos + 2);
                    buf = rbo.buffer();
                }
                buf[pos] = (byte) (0xC0 | (v >> 6));   // 110xxxxx
                buf[pos + 1] = (byte) (0x80 | (v & 0x3F)); // 10xxxxxx
                rbo.setPosition(pos + 2);
                segmentStart = i + 1;
            }
        }
        if (segmentStart < to) {
            copyDirect(rbo, bytes, segmentStart, to);
        }
    }

    private static void copyDirect(ReusableByteArrayOutputStream rbo, byte[] src, int from, int to) {
        int len = to - from;
        if (len <= 0) {
            return;
        }
        int pos = rbo.position();
        byte[] buf = rbo.buffer();
        if (pos + len > buf.length) {
            rbo.grow(pos + len);
            buf = rbo.buffer();
        }
        System.arraycopy(src, from, buf, pos, len);
        rbo.setPosition(pos + len);
    }

    // =========================================================================
    // IMPLEMENTATION 2: Classic Fallback
    // =========================================================================

    public static void writeClassic(OutputStream out, String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        out.write(bytes, 0, bytes.length);
    }
}