package hr.hrg.dialog.core;

import java.util.Arrays;
import java.util.List;
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

    /**
     * Returns individually sanitized frame strings from a throwable's stack trace.
     * Each frame is cleaned to be deterministic: no line numbers, no lambda IDs,
     * no reflection boilerplate.
     *
     * @param throwable the exception to sanitize
     * @param maxFrames maximum number of frames to include
     * @return list of cleaned frame strings like {@code "com.example.MyClass.method"}
     */
    public static List<String> getSanitizedFrames(Throwable throwable, int maxFrames) {
        if (throwable == null) return List.of();
        return getSanitizedFrames(throwable.getStackTrace(), maxFrames);
    }

    /**
     * Returns individually sanitized frame strings from a stack trace array.
     *
     * @param frames    the raw stack trace elements
     * @param maxFrames maximum number of frames to include
     * @return list of cleaned frame strings
     */
    public static List<String> getSanitizedFrames(StackTraceElement[] frames, int maxFrames) {
        if (frames == null || frames.length == 0) return List.of();
        return Arrays.stream(frames)
            .filter(f -> !f.getClassName().startsWith("jdk.internal."))
            .filter(f -> !f.getClassName().startsWith("sun.reflect."))
            .limit(maxFrames)
            .map(JavaStackSanitizer::cleanFrame)
            .collect(Collectors.toList());
    }

    /**
     * Returns a pipe-delimited fingerprint of the sanitized stack trace.
     * Suitable for hashing and deduplication.
     *
     * @param throwable the exception to fingerprint
     * @param maxFrames maximum number of frames to include
     * @return pipe-delimited sanitized frames, e.g.
     *         {@code "com.example.MyClass.method|com.example.Main.main"}
     */
    public static String getFingerprint(Throwable throwable, int maxFrames) {
        if (throwable == null) return "";
        return getSanitizedFrames(throwable, maxFrames).stream()
            .collect(Collectors.joining("|"));
    }

    private static String cleanFrame(StackTraceElement frame) {
        String className = frame.getClassName();

        // Clear out dynamic JVM Lambda identifiers
        if (className.contains("$$Lambda")) {
            className = className.replaceAll("\\$\\$Lambda\\$[0-9]+/.*", "$$Lambda");
        }

        String methodName = frame.getMethodName();

        // Standardize native calls, omit line numbers
        if (frame.isNativeMethod()) {
            return className + "." + methodName + "(native)";
        }

        return className + "." + methodName;
    }
}
