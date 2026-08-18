package hr.hrg.dialog.core.perf;

import java.io.IOException;
import java.io.OutputStream;

/**
 * The pre-optimization "writer talks to the buffer through the
 * {@link OutputStream} interface" path (T3/T4 baseline).
 *
 * <p>Before T4, every chunk of an event was written with a virtual
 * {@code OutputStream.write(byte[], int, int)} call (or per-byte
 * {@code write(int)}), each of which re-checks capacity, and the buffer kept
 * its own cursor. The optimized equivalents on
 * {@code hr.hrg.dialog.core.ReusableByteArrayOutputStream} — {@code writeRaw},
 * {@code writeLongPrefixLE}, {@code writeIntPrefixLE} and the
 * {@code position()}/{@code setPosition()} direct cursor — perform the same
 * work with an inlined capacity check and no per-call virtual dispatch.
 */
public final class StreamMediatedWriter {

    private StreamMediatedWriter() {}

    /** Old behavior: one virtual call per chunk. */
    public static void writeChunk(OutputStream out, byte[] data, int off, int len) throws IOException {
        out.write(data, off, len);
    }

    /** Old behavior: one virtual call per byte. */
    public static void writeByte(OutputStream out, int b) throws IOException {
        out.write(b);
    }
}
