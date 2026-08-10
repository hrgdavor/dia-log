package hr.hrg.dialog.core;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.function.Predicate;

/**
 * Canonical stack trace sanitization implementation.
 * <p>
 * This class is the source of truth for deterministic frame normalization used
 * for error grouping and hash generation. Other classes in this project are
 * derivatives that reuse or mirror this behavior for different input types and
 * API shapes.
 * In architecture docs this canonical role is also referred to as JavaStackTraceSanitizer.
 * <p>
 * Exposed operations include:
 * <ul>
 * <li>Full fingerprint creation from a {@link Throwable}</li>
 * <li>Streaming frame emission into {@link Wyhash64.Streaming}</li>
 * <li>Equivalent textual emission to {@link StringBuffer}</li>
 * <li>Equivalent textual emission to {@link OutputStream}</li>
 * </ul>
 * <p>
 * Normalization rules:
 * <ul>
 * <li>Caller-provided filter decides which classes are included</li>
 * <li>Class names are truncated at {@link #LAMBDA_SUFFIX_FOR_CLASS}</li>
 * <li>Lambda method names are normalized to either {@code lambda} or extracted original method names</li>
 * <li>Line numbers are ignored by using only class and method names</li>
 * </ul>
 */
public class JavaStackSanitizer {

    public static final byte[] DOT_BYTES = {'.'};
    public static final byte[] NEWLINE_BYTES = {'\n'};
    public static final byte[] NEWLINE_JSON_BYTES = {'\\','n'};
    public static final byte[] LAMBDA_METHOD_BYTES = "lambda".getBytes(StandardCharsets.UTF_8);
    public static final StringByteExtractor.ByteWriter stringWriteStrategy = StringByteExtractor.getStrategy();
    public static final String LAMBDA_SUFFIX_FOR_CLASS = "$$Lambda$";
    public static final String LAMBDA_PREFIX_FOR_METHOD = "lambda$";

    /**
     * Builds a deterministic fingerprint for a throwable.
     * <p>
     * The hash input begins with the exception class name and then appends the
     * sanitized frame sequence produced by {@link #addFromTrace(StackTraceElement[], Predicate, Wyhash64.Streaming)}.
     *
     * @param rootCause throwable whose stack trace is fingerprinted
     * @param filter frame class filter used to include application-relevant frames
     * @return deterministic 64-bit hash
     */
    public static long fingerprint(Throwable rootCause, Predicate<String> filter) {
        Wyhash64.Streaming stream = new Wyhash64.Streaming(0);

        // 1. Exception type
        byte[] exBytes = rootCause.getClass().getName().getBytes(StandardCharsets.UTF_8);
        stream.update(exBytes, 0, exBytes.length);

        addFromTrace(rootCause.getStackTrace(), filter, stream);

        return stream.finalHash();
    }

    /**
     * Writes sanitized frame content into a streaming hash sink.
     * <p>
     * If all frames are filtered out, falls back to top 3 raw class/method pairs
     * so hashing remains stable and non-empty.
     *
     * @param trace stack trace elements to process
     * @param filter frame class filter
     * @param stream target hash stream
     */
    public static void addFromTrace(
            StackTraceElement[] trace,
            Predicate<String> filter,
            Wyhash64.Streaming stream) {
        boolean isFirstFrame = true;

        for (StackTraceElement el : trace) {
            String className = el.getClassName();
            if (!filter.test(className)) continue;

            isFirstFrame = false;
            stream.update(NEWLINE_BYTES, 0, 1);
            String methodName = el.getMethodName();

            addFromTraceElement(stream, className, methodName);
        }

        // Fallback: if all frames were skipped, hash top 3 (cleaned)
        if (isFirstFrame) {printTracesFallback(trace, stream);}
    }

    private static void printTracesFallback(StackTraceElement[] trace, Wyhash64.Streaming stream) {
        int limit = Math.min(3, trace.length);
        for (int i = 0; i < limit; i++) {
            stream.update(NEWLINE_BYTES, 0, 1);
            StackTraceElement el = trace[i];
            stream.update(el.getClassName());
            stream.update(DOT_BYTES, 0, 1);
            stream.update(el.getMethodName());
        }
    }

    /**
     * Writes one normalized frame representation into the hash stream.
     * <p>
     * Resulting shape is className.methodName with lambda normalization applied.
     *
     * @param stream target hash stream
     * @param className input class name
     * @param methodName input method name
     */
    public static void addFromTraceElement(Wyhash64.Streaming stream, String className, String methodName) {
        // -------- Class name (strip $$Lambda$ suffix) --------
        int lambdaClassIdx = className.indexOf(LAMBDA_SUFFIX_FOR_CLASS);
        int classEnd = (lambdaClassIdx != -1) ? lambdaClassIdx : className.length();
        byte[] classBytes = className.getBytes(StandardCharsets.UTF_8);
        // Feed only the enclosing class part (skip synthetic suffix)
        stream.update(classBytes, 0, classEnd);

        stream.update(DOT_BYTES, 0, 1);

        // -------- Method name (extract original from lambda$...) --------
        if (lambdaClassIdx != -1) {
            // Method reference → group by enclosing class + "lambda"
            stream.update(LAMBDA_METHOD_BYTES, 0, LAMBDA_METHOD_BYTES.length);
        } else {
            int start = 0;
            int end = methodName.length();

            if (methodName.startsWith(LAMBDA_PREFIX_FOR_METHOD)) {
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
            stream.update(methodName, start, end - start);
        }
    }

    /**
     * Writes the same sanitized frame sequence as {@link #addFromTrace(StackTraceElement[], Predicate, Wyhash64.Streaming)}
     * but into a string buffer.
     *
     * @param trace stack trace elements to process
     * @param filter frame class filter
     * @param sb target buffer
     */
    public static void addFromTraceToStringBuffer(
            StackTraceElement[] trace,
            Predicate<String> filter,
            StringBuffer sb) {

        // Constants used in the original hashing method (as strings)
        final String NEWLINE = "\n";
        final String DOT = ".";
        final String LAMBDA_METHOD = "lambda";

        boolean isFirstFrame = true;

        for (StackTraceElement el : trace) {
            String className = el.getClassName();

            // Apply filter; skip frames that don't match
            if (!filter.test(className)) {
                continue;
            }

            // Delimiter before each frame (matches streaming hash behaviour)
            sb.append(NEWLINE);
            isFirstFrame = false;

            String methodName = el.getMethodName();

            // -------- Class name (strip $$Lambda$ suffix) --------
            int lambdaClassIdx = className.indexOf(LAMBDA_SUFFIX_FOR_CLASS);
            int classEnd = (lambdaClassIdx != -1) ? lambdaClassIdx : className.length();
            sb.append(className, 0, classEnd);  // append only the enclosing class part

            sb.append(DOT);

            // -------- Method name (extract original from lambda$...) --------
            if (lambdaClassIdx != -1) {
                // Method reference → group by enclosing class + "lambda"
                sb.append(LAMBDA_METHOD);
            } else {
                if (methodName.startsWith(LAMBDA_PREFIX_FOR_METHOD)) {
                    // pattern: lambda$originalMethod$number → extract "originalMethod"
                    int firstDollar = methodName.indexOf('$');
                    if (firstDollar != -1) {
                        int start = firstDollar + 1;
                        int secondDollar = methodName.indexOf('$', firstDollar + 1);
                        int end = (secondDollar != -1) ? secondDollar : methodName.length();
                        sb.append(methodName, start, end);
                    } else {
                        // fallback (should not happen for "lambda$..." strings)
                        sb.append(methodName);
                    }
                } else {
                    // regular method name
                    sb.append(methodName);
                }
            }
        }

        // Fallback: if all frames were skipped, hash top 3 (cleaned)
        if (isFirstFrame) {
            int limit = Math.min(3, trace.length);
            for (int i = 0; i < limit; i++) {
                sb.append(NEWLINE);
                StackTraceElement el = trace[i];
                sb.append(el.getClassName())
                        .append(DOT)
                        .append(el.getMethodName());
            }
        }
    }

    /**
     * Writes sanitized frames to an output stream with raw newline separators.
     *
     * @param trace stack trace elements to process
     * @param filter frame class filter
     * @param out target stream
     * @throws IOException if writing fails
     */
    public static void addFromTraceToOutputStream(
            StackTraceElement[] trace,
            Predicate<String> filter,
            OutputStream out) throws IOException {
        addFromTraceToOutputStreamWithNewline(trace, filter,out, NEWLINE_BYTES);
    }

    /**
     * Writes sanitized frames to an output stream using JSON-escaped newline separators.
     *
     * @param trace stack trace elements to process
     * @param filter frame class filter
     * @param out target stream
     * @throws IOException if writing fails
     */
    public static void addFromTraceToOutputStreamJson(
            StackTraceElement[] trace,
            Predicate<String> filter,
            OutputStream out) throws IOException {
        addFromTraceToOutputStreamWithNewline(trace, filter,out, NEWLINE_JSON_BYTES);
    }

    /**
     * Writes sanitized frames to an output stream using caller-provided newline bytes.
     * <p>
     * If all frames are filtered out, writes top 3 raw class/method pairs.
     *
     * @param trace stack trace elements to process
     * @param filter frame class filter
     * @param out target stream
     * @param newlineBytes delimiter bytes placed before each frame
     * @throws IOException if writing fails
     */
    public static void addFromTraceToOutputStreamWithNewline(
            StackTraceElement[] trace,
            Predicate<String> filter,
            OutputStream out,
            byte[] newlineBytes) throws IOException {

        boolean isFirstFrame = true;

        for (StackTraceElement el : trace) {
            String className = el.getClassName();

            // Apply filter; skip frames that don't match
            if (!filter.test(className)) {
                continue;
            }

            // Delimiter before each frame (matches streaming hash behaviour)
            out.write(newlineBytes);
            isFirstFrame = false;

            String methodName = el.getMethodName();

            // -------- Class name (strip $$Lambda$ suffix) --------
            int lambdaClassIdx = className.indexOf(LAMBDA_SUFFIX_FOR_CLASS);
            int classEnd = (lambdaClassIdx != -1) ? lambdaClassIdx : className.length();
            // Write only the enclosing class part
            stringWriteStrategy.write(out,className.substring(0, classEnd));

            out.write(DOT_BYTES);

            // -------- Method name (extract original from lambda$...) --------
            if (lambdaClassIdx != -1) {
                // Method reference → group by enclosing class + "lambda"
                out.write(LAMBDA_METHOD_BYTES);
            } else {
                if (methodName.startsWith(LAMBDA_PREFIX_FOR_METHOD)) {
                    // pattern: lambda$originalMethod$number → extract "originalMethod"
                    int firstDollar = methodName.indexOf('$');
                    if (firstDollar != -1) {
                        int start = firstDollar + 1;
                        int secondDollar = methodName.indexOf('$', firstDollar + 1);
                        int end = (secondDollar != -1) ? secondDollar : methodName.length();
                        stringWriteStrategy.write(out,methodName.substring(start, end));
                    } else {
                        // fallback (should not happen for "lambda$..." strings)
                        stringWriteStrategy.write(out,methodName);
                    }
                } else {
                    // regular method name
                    stringWriteStrategy.write(out,methodName);
                }
            }
        }

        // Fallback: if all frames were skipped, write top 3 (cleaned)
        if (isFirstFrame) {
            int limit = Math.min(3, trace.length);
            for (int i = 0; i < limit; i++) {
                out.write(newlineBytes);
                StackTraceElement el = trace[i];
                stringWriteStrategy.write(out,el.getClassName());
                out.write(DOT_BYTES);
                stringWriteStrategy.write(out,el.getMethodName());
            }
        }
    }
}
