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
    private static String fingerprint(IThrowableProxy rootCause, Predicate<String> filter) {
        Wyhash64.Streaming stream = new Wyhash64.Streaming(0);

        // 1. Exception type
        byte[] exBytes = rootCause.getClass().getName().getBytes(StandardCharsets.UTF_8);
        stream.update(exBytes, 0, exBytes.length);
        stream.update(JavaStackSanitizer.NEWLINE, 0, 1);

        StackTraceElementProxy[] traceProxy = rootCause.getStackTraceElementProxyArray();
        StackTraceElement[] trace = new StackTraceElement[traceProxy.length];
        for(int i=0; i<traceProxy.length; i++){
            trace[i] = traceProxy[i].getStackTraceElement();
        }
        JavaStackSanitizer.addFromTrace(trace, filter, stream);

        long hash = stream.finalHash();
        return HexFormat.of().toHexDigits(hash);
    }

}
