package hr.hrg.dialog.core;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.charset.StandardCharsets;

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

    /**
     * Returns true if --add-opens java.base/java.lang=ALL-UNNAMED is active.
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
     *
     * @param out target stream
     * @param latin1Bytes the internal {@code byte[]} of a Latin-1 compact string (coder == 0)
     * @throws IOException if writing fails
     */
    public static void writeLatin1(OutputStream out, byte[] latin1Bytes) throws IOException {
        for (byte b : latin1Bytes) {
            int v = b & 0xFF;
            if (v < 0x80) {
                out.write(v);
            } else {
                out.write(0xC0 | (v >> 6));       // 110xxxxx
                out.write(0x80 | (v & 0x3F));     // 10xxxxxx
            }
        }
    }

    // =========================================================================
    // IMPLEMENTATION 2: Classic Fallback
    // =========================================================================

    public static void writeClassic(OutputStream out, String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        out.write(bytes, 0, bytes.length);
    }
}