package hr.hrg.dialog.core.perf;

import java.io.IOException;
import java.io.OutputStream;

/**
 * The pre-optimization field-prefix writer (T6 baseline).
 *
 * <p>Before T6, {@code JsonLogWriter} wrote every fixed field prefix as a
 * precomputed {@code byte[]} through two {@code OutputStream} calls:
 * {@code out.write(',')} followed by {@code out.write(keyBytes)} (and
 * {@code out.write('{')} + {@code out.write(keyBytes)} for the first field).
 * That behavior is preserved here for correctness comparison and benchmarking
 * against the packed-{@code long} direct-buffer path
 * ({@code hr.hrg.dialog.logback.JsonLogWriter} + T4 helpers on
 * {@code hr.hrg.dialog.core.ReusableByteArrayOutputStream}).
 */
public final class ClassicFieldPrefixes {

    private ClassicFieldPrefixes() {}

    /** Old behavior: '{' then the first field's prefix bytes. */
    public static void writeObjectStartAndField(OutputStream out, byte[] keyBytes) throws IOException {
        out.write('{');
        out.write(keyBytes);
    }

    /** Old behavior: ',' then the field's prefix bytes. */
    public static void writeFieldPrefix(OutputStream out, byte[] keyBytes) throws IOException {
        out.write(',');
        out.write(keyBytes);
    }
}
