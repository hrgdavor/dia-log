package hr.hrg.dialog.core.perf;

import java.io.IOException;
import java.io.OutputStream;

/**
 * The pre-optimization implementation of
 * {@code StringByteExtractor.writeLatin1}, kept verbatim (in shape) for
 * performance comparison.
 *
 * <p>This is the per-byte scan that T1 replaced: each byte is tested
 * individually for {@code >= 0x80}, clean ASCII runs are flushed as bulk
 * writes, and 0x80..0xFF bytes are expanded to 2-byte UTF-8. Kept in
 * {@code src/test} as the baseline for the new 8-byte SWAR word scan (see
 * {@code hr.hrg.dialog.core.StringByteExtractor}).
 */
public final class ClassicStringByteExtractor {

    private ClassicStringByteExtractor() {}

    /** Old per-byte Latin-1 to UTF-8 writer (the T1/T2 baseline). */
    public static void writeLatin1(OutputStream out, byte[] latin1Bytes) throws IOException {
        int segmentStart = 0;
        for (int i = 0; i < latin1Bytes.length; i++) {
            int v = latin1Bytes[i] & 0xFF;
            if (v >= 0x80) {
                if (i > segmentStart) {
                    out.write(latin1Bytes, segmentStart, i - segmentStart);
                }
                out.write(0xC0 | (v >> 6));       // 110xxxxx
                out.write(0x80 | (v & 0x3F));     // 10xxxxxx
                segmentStart = i + 1;
            }
        }
        if (segmentStart < latin1Bytes.length) {
            out.write(latin1Bytes, segmentStart, latin1Bytes.length - segmentStart);
        }
    }
}
