package hr.hrg.dialog.core;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

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
public class JavaStackSanitizer {

    public static final byte[] DOT = {'.'};
    public static final byte[] NEWLINE = {'\n'};
    public static final byte[] LAMBDA_METHOD = "lambda".getBytes(StandardCharsets.UTF_8);

    /**
     * Create method fingerprinting stack traces. If not app frames are found, fallback
     * by taking the top 3 frames from the raw stack trace (regardless of whether they are system/framework).
     *
     * @param rootCause
     * @return
     */
    private static String fingerprint(Throwable rootCause, Predicate<String> filter) {
        Wyhash64.Streaming stream = new Wyhash64.Streaming(0);

        // 1. Exception type
        byte[] exBytes = rootCause.getClass().getName().getBytes(StandardCharsets.UTF_8);
        stream.update(exBytes, 0, exBytes.length);
        stream.update(NEWLINE, 0, 1);

        addFromTrace(rootCause.getStackTrace(), filter, stream);

        long hash = stream.finalHash();
        return HexFormat.of().toHexDigits(hash);
    }

    public static void addFromTrace(StackTraceElement[] trace , Predicate<String> filter, Wyhash64.Streaming stream) {
        boolean isFirstFrame = true;

        for (StackTraceElement el : trace) {
            String className = el.getClassName();
            if (!filter.test(className)) continue;

            // Delimiter between frames
            if (!isFirstFrame) {
                stream.update(NEWLINE, 0, 1);
            }
            isFirstFrame = false;

            String methodName = el.getMethodName();

            // -------- Class name (strip $$Lambda$ suffix) --------
            int lambdaClassIdx = className.indexOf("$$Lambda$");
            int classEnd = (lambdaClassIdx != -1) ? lambdaClassIdx : className.length();
            byte[] classBytes = className.getBytes(StandardCharsets.UTF_8);
            // Feed only the enclosing class part (skip synthetic suffix)
            stream.update(classBytes, 0, classEnd);

            stream.update(DOT, 0, 1);

            // -------- Method name (extract original from lambda$...) --------
            if (lambdaClassIdx != -1) {
                // Method reference → group by enclosing class + "lambda"
                stream.update(LAMBDA_METHOD, 0, LAMBDA_METHOD.length);
            } else {
                byte[] methodBytes = methodName.getBytes(StandardCharsets.UTF_8);
                int start = 0;
                int end = methodBytes.length;

                if (methodName.startsWith("lambda$")) {
                    // pattern: lambda$originalMethod$number → extract "originalMethod"
                    int firstDollar = methodName.indexOf('$');
                    if (firstDollar != -1) {
                        int secondDollar = methodName.indexOf('$', firstDollar + 1);
                        start = firstDollar + 1;
                        if (secondDollar != -1) {
                            end = secondDollar; // feed only between the first and second '$'
                        }
                        // else feed until end of string
                    }
                }
                stream.update(methodBytes, start, end - start);
            }
        }

        // Fallback: if all frames were skipped, hash top 3 (cleaned)
        if (isFirstFrame) {
            int limit = Math.min(3, trace.length);
            for (int i = 0; i < limit; i++) {
                if (i > 0) stream.update(NEWLINE, 0, 1);
                StackTraceElement el = trace[i];
                byte[] fb = (el.getClassName() + "." + el.getMethodName())
                        .getBytes(StandardCharsets.UTF_8); // fallback only – rare
                stream.update(fb, 0, fb.length);
            }
        }
    }

}
