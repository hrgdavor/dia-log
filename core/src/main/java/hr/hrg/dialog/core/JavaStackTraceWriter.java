package hr.hrg.dialog.core;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Predicate;

/**
 * No-filter derivative of {@link JavaStackSanitizer} for {@link StackTraceElement[]} input.
 * <p>
 * This class keeps the same normalization rules as {@link JavaStackSanitizer} but removes
 * filtering and fallback logic from the public API, making it suitable for direct full-trace
 * serialization and hashing.
 * <p>
 * Conceptually this class can be referred to as JavaStackWriter and acts as the
 * semantic no-filter base for logback-side writer derivatives.
 */
public class JavaStackTraceWriter {

    public static final byte[] DOT_BYTES = {'.'};
    public static final byte[] NEWLINE_BYTES = {'\n'};
    public static final byte[] NEWLINE_JSON_BYTES = {'\\','n'};
    public static final byte[] LAMBDA_METHOD_BYTES = "lambda".getBytes(StandardCharsets.UTF_8);
    public static final StringByteExtractor.ByteWriter stringWriteStrategy = StringByteExtractor.getStrategy();
    public static final String LAMBDA_SUFFIX_FOR_CLASS = "$$Lambda$";
    public static final String LAMBDA_PREFIX_FOR_METHOD = "lambda$";

    private static final byte DOT_BYTE = '.';
    private static final byte NEWLINE_BYTE = '\n';
    /**
     * Builds a deterministic fingerprint for a throwable using all stack frames.
     * <p>
     * Parameter {@code filter} is accepted for API compatibility with sanitizer-derived code paths
     * and is intentionally ignored.
     *
     * @param rootCause throwable whose stack trace is fingerprinted
     * @param filter ignored
     * @return deterministic 64-bit hash
     */
    public static long fingerprint(Throwable rootCause, Predicate<String> filter) {
        Wyhash64.Streaming stream = new Wyhash64.Streaming(0);

        // 1. Exception type
        byte[] exBytes = rootCause.getClass().getName().getBytes(StandardCharsets.UTF_8);
        stream.update(exBytes, 0, exBytes.length);

        addFromTrace(rootCause.getStackTrace()/*, filter*/, stream);

        return stream.finalHash();
    }

    /**
     * Writes normalized frame content for all frames into a streaming hash sink.
     *
     * @param trace stack trace elements to process
     * @param stream target hash stream
     */
    public static void addFromTrace(
            StackTraceElement[] trace,
            //Predicate<String> filter,
            Wyhash64.Streaming stream) {
        // boolean isFirstFrame = true;

        for (StackTraceElement el : trace) {
            String className = el.getClassName();
            //if (!filter.test(className)) continue;

            // isFirstFrame = false;
            stream.update(NEWLINE_BYTES, 0, 1);

            String methodName = el.getMethodName();

            addFromTraceElement(stream, className, methodName);
        }

        // Fallback: if all frames were skipped, hash top 3 (cleaned)
        /*if (isFirstFrame) {
            int limit = Math.min(3, trace.length);
            for (int i = 0; i < limit; i++) {
                if (i > 0) stream.update(NEWLINE_BYTES, 0, 1);
                StackTraceElement el = trace[i];
                byte[] classBytes = el.getClassName().getBytes(StandardCharsets.UTF_8);
                stream.update(classBytes, 0, classBytes.length);
                stream.update(DOT_BYTES, 0, 1);
                byte[] methodBytes = el.getMethodName().getBytes(StandardCharsets.UTF_8);
                stream.update(methodBytes, 0, methodBytes.length);
            }
        }*/
    }

    private static void addFromTraceElement(Wyhash64.Streaming stream, String className, String methodName) {
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
     * Writes normalized frame content for all frames to a string buffer.
     *
     * @param trace stack trace elements to process
     * @param sb target buffer
     */
    public static void addFromTraceToStringBuffer(
            StackTraceElement[] trace,
            //Predicate<String> filter,
            StringBuffer sb) {

        // Constants used in the original hashing method (as strings)
        final String NEWLINE = "\n";
        final String DOT = ".";
        final String LAMBDA_METHOD = "lambda";

        // boolean isFirstFrame = true;

        for (StackTraceElement el : trace) {
            String className = el.getClassName();

            // Apply filter; skip frames that don't match
            //if (!filter.test(className)) {
            //    continue;
            //}

            // Delimiter before each frame (matches streaming hash behaviour)
            sb.append(NEWLINE);
            // isFirstFrame = false;

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
        /*if (isFirstFrame) {
            int limit = Math.min(3, trace.length);
            for (int i = 0; i < limit; i++) {
                if (i > 0) {
                    sb.append(NEWLINE);
                }
                StackTraceElement el = trace[i];
                sb.append(el.getClassName())
                        .append(DOT)
                        .append(el.getMethodName());
            }
        }*/
    }

    /**
     * Writes normalized frame content for all frames to an output stream with raw newline separators.
     *
     * @param trace stack trace elements to process
     * @param out target stream
     * @throws IOException if writing fails
     */
    public static void addFromTraceToOutputStream(
            StackTraceElement[] trace,
            //Predicate<String> filter,
            OutputStream out) throws IOException {
        addFromTraceToOutputStreamWithNewline(trace/*, filter*/,out, NEWLINE_BYTES);
    }

    /**
     * Writes normalized frame content for all frames to an output stream with JSON-escaped newline separators.
     *
     * @param trace stack trace elements to process
     * @param out target stream
     * @throws IOException if writing fails
     */
    public static void addFromTraceToOutputStreamJson(
            StackTraceElement[] trace,
//            Predicate<String> filter,
            OutputStream out) throws IOException {
        addFromTraceToOutputStreamWithNewline(trace/*, filter*/,out, NEWLINE_JSON_BYTES);
    }

    /**
     * Writes normalized frame content for all frames using caller-provided newline bytes.
     *
     * @param trace stack trace elements to process
     * @param out target stream
     * @param newlineBytes delimiter bytes placed before each frame
     * @throws IOException if writing fails
     */
    public static void addFromTraceToOutputStreamWithNewline(
            StackTraceElement[] trace,
            //Predicate<String> filter,
            OutputStream out,
            byte[] newlineBytes) throws IOException {

        // boolean isFirstFrame = true;

        for (StackTraceElement el : trace) {
            String className = el.getClassName();

            // Apply filter; skip frames that don't match
            /*if (!filter.test(className)) {
                continue;
            }*/

            // Delimiter before each frame (matches streaming hash behaviour)
            out.write(newlineBytes);
            // isFirstFrame = false;

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
        /*if (isFirstFrame) {
            int limit = Math.min(3, trace.length);
            for (int i = 0; i < limit; i++) {
                if (i > 0) {
                    out.write(newlineBytes);
                }
                StackTraceElement el = trace[i];
                String full = el.getClassName() + "." + el.getMethodName();
                stringWriteStrategy.write(out,el.getClassName());
                out.write(DOT_BYTES);
                stringWriteStrategy.write(out,el.getMethodName());
            }
        }*/
    }

    /**
     * Writes normalized frame content for all frames with JSON-escaped newline separators
     * and computes the fingerprint in one pass.
     * <p>
     * This is the no-filter single-pass counterpart to
     * {@link JavaStackSanitizer#addFromTraceToOutputStreamJsonAndFingerprint(StackTraceElement[], Predicate, OutputStream, String)}.
     * Every frame is included (no filtering, no fallback), and the produced hash is identical to
     * the sanitizer path invoked with an accept-all predicate.
     *
     * @param trace stack trace elements to process
     * @param out target stream
     * @param throwableClassName exception class name to include in hash seed, may be null
     * @return deterministic 64-bit hash
     * @throws IOException if writing fails
     */
    public static long addFromTraceToOutputStreamJsonAndFingerprint(
            StackTraceElement[] trace,
            OutputStream out,
            String throwableClassName) throws IOException {
        Wyhash64.Streaming stream = new Wyhash64.Streaming(0);
        return addFromTraceToOutputStreamJsonAndFingerprint(trace, out, throwableClassName, stream);
    }

    /**
     * Writes normalized frame content for all frames with JSON-escaped newline separators
     * and computes the fingerprint in one pass, using a caller-supplied reusable hasher.
     *
     * @param trace stack trace elements to process
     * @param out target stream
     * @param throwableClassName exception class name to include in hash seed, may be null
     * @param stream reusable hasher instance, reset and reused on the hot path
     * @return deterministic 64-bit hash
     * @throws IOException if writing fails
     */
    public static long addFromTraceToOutputStreamJsonAndFingerprint(
            StackTraceElement[] trace,
            OutputStream out,
            String throwableClassName,
            Wyhash64.Streaming stream) throws IOException {
        return addFromTraceToOutputStreamWithNewlineAndFingerprint(
                trace,
                out,
                NEWLINE_JSON_BYTES,
                throwableClassName,
                stream
        );
    }

    /**
     * Writes normalized frame content for all frames using caller-provided newline bytes
     * and computes the fingerprint in one pass.
     * <p>
     * For hash compatibility with {@link #fingerprint(Throwable, Predicate)} the hash
     * delimiter is always raw '\n'.
     *
     * @param trace stack trace elements to process
     * @param out target stream
     * @param newlineBytes delimiter bytes placed before each frame
     * @param throwableClassName exception class name to include in hash seed, may be null
     * @param stream reusable hasher instance, reset and reused on the hot path
     * @return deterministic 64-bit hash
     * @throws IOException if writing fails
     */
    public static long addFromTraceToOutputStreamWithNewlineAndFingerprint(
            StackTraceElement[] trace,
            OutputStream out,
            byte[] newlineBytes,
            String throwableClassName,
            Wyhash64.Streaming stream) throws IOException {

        stream.reset(0);
        if (throwableClassName != null) {
            stream.update(throwableClassName);
        }

        for (StackTraceElement el : trace) {
            String className = el.getClassName();

            out.write(newlineBytes);
            stream.updateByte(NEWLINE_BYTE);

            String methodName = el.getMethodName();
            int lambdaClassIdx = className.indexOf(LAMBDA_SUFFIX_FOR_CLASS);
            int classEnd = (lambdaClassIdx != -1) ? lambdaClassIdx : className.length();
            stringWriteStrategy.write(out, className.substring(0, classEnd));
            stream.update(className, 0, classEnd);

            out.write(DOT_BYTES);
            stream.updateByte(DOT_BYTE);

            if (lambdaClassIdx != -1) {
                out.write(LAMBDA_METHOD_BYTES);
                stream.update(LAMBDA_METHOD_BYTES, 0, LAMBDA_METHOD_BYTES.length);
            } else if (methodName.startsWith(LAMBDA_PREFIX_FOR_METHOD)) {
                int firstDollar = methodName.indexOf('$');
                if (firstDollar != -1) {
                    int start = firstDollar + 1;
                    int secondDollar = methodName.indexOf('$', firstDollar + 1);
                    int end = (secondDollar != -1) ? secondDollar : methodName.length();
                    stringWriteStrategy.write(out, methodName.substring(start, end));
                    stream.update(methodName, start, end - start);
                } else {
                    stringWriteStrategy.write(out, methodName);
                    stream.update(methodName);
                }
            } else {
                stringWriteStrategy.write(out, methodName);
                stream.update(methodName);
            }
        }

        return stream.finalHash();
    }
}
