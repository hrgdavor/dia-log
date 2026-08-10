package hr.hrg.dialog.logback;

import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import hr.hrg.dialog.core.JavaStackSanitizer;
import hr.hrg.dialog.core.Wyhash64;

import java.io.IOException;
import java.io.OutputStream;
import java.util.function.Predicate;

/**
 * Logback-proxy derivative of {@link JavaStackSanitizer}.
 * <p>
 * This class keeps sanitizer semantics (filter + fallback + normalization) while adapting
 * input from {@link IThrowableProxy} and {@link StackTraceElementProxy}.
 * It is the logback-side filter-enabled counterpart to {@link hr.hrg.dialog.core.JavaStackSanitizer}.
 */
public class JavaStackSanitizerLogback {

    /**
     * Builds a deterministic fingerprint for a logback throwable proxy.
     *
     * @param rootCause throwable proxy whose stack trace is fingerprinted
     * @param filter frame class filter used to include application-relevant frames
     * @return deterministic 64-bit hash
     */
    public static long fingerprint(IThrowableProxy rootCause, Predicate<String> filter) {
        Wyhash64.Streaming stream = new Wyhash64.Streaming(0);

        // 1. Exception type
        stream.update(rootCause.getClassName());

        addFromTrace(rootCause.getStackTraceElementProxyArray(), filter, stream);

        return stream.finalHash();
    }

    /**
     * Writes sanitized proxy frame content into a streaming hash sink.
     * <p>
     * If all frames are filtered out, falls back to top 3 raw class/method pairs.
     *
     * @param trace logback proxy stack trace elements
     * @param filter frame class filter
     * @param stream target hash stream
     */
    public static void addFromTrace(
            StackTraceElementProxy[] trace,
            Predicate<String> filter,
            Wyhash64.Streaming stream) {
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

    }

    /**
     * Writes the same sanitized proxy frame sequence as {@link #addFromTrace(StackTraceElementProxy[], Predicate, Wyhash64.Streaming)}
     * but into a string buffer.
     *
     * @param trace logback proxy stack trace elements
     * @param filter frame class filter
     * @param sb target buffer
     */
    public static void addFromTraceToStringBuffer(
            StackTraceElementProxy[] trace,
            Predicate<String> filter,
            StringBuffer sb) {
        final String LAMBDA_METHOD = "lambda";
        boolean isFirstFrame = true;

        for (StackTraceElementProxy elp : trace) {
            StackTraceElement el = elp.getStackTraceElement();
            String className = el.getClassName();
            if (!filter.test(className)) continue;

            sb.append('\n');
            isFirstFrame = false;

            String methodName = el.getMethodName();
            int lambdaClassIdx = className.indexOf(JavaStackSanitizer.LAMBDA_SUFFIX_FOR_CLASS);
            int classEnd = (lambdaClassIdx != -1) ? lambdaClassIdx : className.length();
            sb.append(className, 0, classEnd);
            sb.append('.');

            if (lambdaClassIdx != -1) {
                sb.append(LAMBDA_METHOD);
            } else if (methodName.startsWith(JavaStackSanitizer.LAMBDA_PREFIX_FOR_METHOD)) {
                int firstDollar = methodName.indexOf('$');
                if (firstDollar != -1) {
                    int start = firstDollar + 1;
                    int secondDollar = methodName.indexOf('$', firstDollar + 1);
                    int end = (secondDollar != -1) ? secondDollar : methodName.length();
                    sb.append(methodName, start, end);
                } else {
                    sb.append(methodName);
                }
            } else {
                sb.append(methodName);
            }
        }

        if (isFirstFrame) {
            int limit = Math.min(3, trace.length);
            for (int i = 0; i < limit; i++) {
                sb.append('\n');
                StackTraceElement el = trace[i].getStackTraceElement();
                sb.append(el.getClassName())
                        .append('.')
                        .append(el.getMethodName());
            }
        }
    }

    /**
     * Writes sanitized proxy frames to an output stream with raw newline separators.
     *
     * @param trace logback proxy stack trace elements
     * @param filter frame class filter
     * @param out target stream
     * @throws IOException if writing fails
     */
    public static void addFromTraceToOutputStream(
            StackTraceElementProxy[] trace,
            Predicate<String> filter,
            OutputStream out) throws IOException {
        addFromTraceToOutputStreamWithNewline(trace, filter, out, JavaStackSanitizer.NEWLINE_BYTES);
    }

    /**
     * Writes sanitized proxy frames to an output stream using JSON-escaped newline separators.
     *
     * @param trace logback proxy stack trace elements
     * @param filter frame class filter
     * @param out target stream
     * @throws IOException if writing fails
     */
    public static void addFromTraceToOutputStreamJson(
            StackTraceElementProxy[] trace,
            Predicate<String> filter,
            OutputStream out) throws IOException {
        addFromTraceToOutputStreamWithNewline(trace, filter, out, JavaStackSanitizer.NEWLINE_JSON_BYTES);
    }

    /**
     * Writes sanitized proxy frames using caller-provided newline bytes.
     * <p>
     * If all frames are filtered out, writes top 3 raw class/method pairs.
     *
     * @param trace logback proxy stack trace elements
     * @param filter frame class filter
     * @param out target stream
     * @param newlineBytes delimiter bytes placed before each frame
     * @throws IOException if writing fails
     */
    public static void addFromTraceToOutputStreamWithNewline(
            StackTraceElementProxy[] trace,
            Predicate<String> filter,
            OutputStream out,
            byte[] newlineBytes) throws IOException {

        boolean isFirstFrame = true;

        for (StackTraceElementProxy elp : trace) {
            StackTraceElement el = elp.getStackTraceElement();
            String className = el.getClassName();
            if (!filter.test(className)) continue;

            out.write(newlineBytes);
            isFirstFrame = false;

            String methodName = el.getMethodName();
            int lambdaClassIdx = className.indexOf(JavaStackSanitizer.LAMBDA_SUFFIX_FOR_CLASS);
            int classEnd = (lambdaClassIdx != -1) ? lambdaClassIdx : className.length();
            JavaStackSanitizer.stringWriteStrategy.write(out, className.substring(0, classEnd));
            out.write(JavaStackSanitizer.DOT_BYTES);

            if (lambdaClassIdx != -1) {
                out.write(JavaStackSanitizer.LAMBDA_METHOD_BYTES);
            } else if (methodName.startsWith(JavaStackSanitizer.LAMBDA_PREFIX_FOR_METHOD)) {
                int firstDollar = methodName.indexOf('$');
                if (firstDollar != -1) {
                    int start = firstDollar + 1;
                    int secondDollar = methodName.indexOf('$', firstDollar + 1);
                    int end = (secondDollar != -1) ? secondDollar : methodName.length();
                    JavaStackSanitizer.stringWriteStrategy.write(out, methodName.substring(start, end));
                } else {
                    JavaStackSanitizer.stringWriteStrategy.write(out, methodName);
                }
            } else {
                JavaStackSanitizer.stringWriteStrategy.write(out, methodName);
            }
        }

        if (isFirstFrame) {
            int limit = Math.min(3, trace.length);
            for (int i = 0; i < limit; i++) {
                out.write(newlineBytes);
                StackTraceElement el = trace[i].getStackTraceElement();
                JavaStackSanitizer.stringWriteStrategy.write(out, el.getClassName());
                out.write(JavaStackSanitizer.DOT_BYTES);
                JavaStackSanitizer.stringWriteStrategy.write(out, el.getMethodName());
            }
        }
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
