package hr.hrg.dialog.logback;

import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import hr.hrg.dialog.core.JavaStackSanitizer;
import hr.hrg.dialog.core.Wyhash64;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.function.Predicate;

/**
 * Sanitizes stack traces to produce deterministic, hashable frame strings.
 * <p>
 * Cleaning rules:
 * <ul>
 * <li>Drops {@code jdk.internal.*} and {@code sun.reflect.*} boilerplate frames</li>
 * <li>Normalizes JVM lambda identifiers ({@code $$Lambda$123/0x...} → {@code $$Lambda})</li>
 * <li>Strips line numbers for deterministic output across builds</li>
 * <li>Standardizes native method calls</li>
 * </ul>
 * The resulting frames are suitable for deduplication, grouping, and hashing
 * by both the JSON log writer and external tools (Elasticsearch, Loki, etc.).
 */
public class JavaStackSanitizerLogback {

    /** Crate method fingerprinting stack traces. If not app frames are found, fallback
     * by taking the top 3 frames from the raw stack trace (regardless of whether they are system/framework).
     *
     * @param rootCause
     * @return
     */
    public static long fingerprint(IThrowableProxy rootCause, Predicate<String> filter) {
        Wyhash64.Streaming stream = new Wyhash64.Streaming(0);

        // 1. Exception type
        stream.update(rootCause.getClass().getName());

        StackTraceElementProxy[] trace = rootCause.getStackTraceElementProxyArray();
        boolean isFirstFrame = true;

        for (StackTraceElementProxy elp : trace) {
            StackTraceElement el = elp.getStackTraceElement();
            String className = el.getClassName();
            if (!filter.test(className)) continue;

            isFirstFrame = false;
            stream.update(JavaStackSanitizer.NEWLINE_BYTES, 0, 1);
            String methodName = el.getMethodName();

            JavaStackSanitizer.addFromTraceElement(stream, className, methodName);
        }

        // Fallback: if all frames were skipped, hash top 3 (cleaned)
        if (isFirstFrame) {printTracesFallback(trace, stream);}

        return stream.finalHash();
    }

    private static void printTracesFallback(StackTraceElementProxy[] trace, Wyhash64.Streaming stream) {
        int limit = Math.min(3, trace.length);
        for (int i = 0; i < limit; i++) {
            stream.update(JavaStackSanitizer.NEWLINE_BYTES, 0, 1);
            StackTraceElement el = trace[i].getStackTraceElement();
            stream.update(el.getClassName());
            stream.update(JavaStackSanitizer.DOT_BYTES, 0, 1);
            stream.update(el.getMethodName());
        }
    }
}
