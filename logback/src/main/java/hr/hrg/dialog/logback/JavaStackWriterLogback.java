package hr.hrg.dialog.logback;

import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import hr.hrg.dialog.core.JavaStackSanitizer;
import hr.hrg.dialog.core.JavaStackTraceWriter;
import hr.hrg.dialog.core.Wyhash64;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Predicate;

/**
 * Logback-proxy no-filter derivative aligned with {@link JavaStackTraceWriter} semantics.
 * <p>
 * Technical derivation view:
 * <ul>
 * <li>Behavioral base is {@link JavaStackSanitizer} (normalization algorithm source of truth)</li>
 * <li>API and semantics align with {@link JavaStackTraceWriter} (no filter, no fallback)</li>
 * <li>Input shape is adapted to {@link IThrowableProxy}/{@link StackTraceElementProxy}</li>
 * </ul>
 * This is the logback-side filter-free counterpart to {@link hr.hrg.dialog.core.JavaStackTraceWriter}.
 */
public class JavaStackWriterLogback {

    public static final byte[] DOT_BYTES = JavaStackTraceWriter.DOT_BYTES;
    public static final byte[] NEWLINE_BYTES = JavaStackTraceWriter.NEWLINE_BYTES;
    public static final byte[] NEWLINE_JSON_BYTES = JavaStackTraceWriter.NEWLINE_JSON_BYTES;
    public static final byte[] LAMBDA_METHOD_BYTES = "lambda".getBytes(StandardCharsets.UTF_8);
    public static final String LAMBDA_SUFFIX_FOR_CLASS = JavaStackTraceWriter.LAMBDA_SUFFIX_FOR_CLASS;
    public static final String LAMBDA_PREFIX_FOR_METHOD = JavaStackTraceWriter.LAMBDA_PREFIX_FOR_METHOD;

    private static final byte DOT_BYTE = '.';
    private static final byte NEWLINE_BYTE = '\n';

    /**
     * Builds a deterministic fingerprint for a logback throwable proxy using all frames.
     * <p>
     * Parameter {@code filter} is accepted for API compatibility and intentionally ignored.
     *
     * @param rootCause throwable proxy whose stack trace is fingerprinted
     * @param filter ignored
     * @return deterministic 64-bit hash
     */
    public static long fingerprint(IThrowableProxy rootCause, Predicate<String> filter) {
        Wyhash64.Streaming stream = new Wyhash64.Streaming(0);

        stream.update(rootCause.getClassName());
        addFromTrace(rootCause.getStackTraceElementProxyArray(), stream);

        return stream.finalHash();
    }

    /**
     * Writes normalized proxy frame content for all frames into a streaming hash sink.
     *
     * @param trace logback proxy stack trace elements
     * @param stream target hash stream
     */
    public static void addFromTrace(
            StackTraceElementProxy[] trace,
            Wyhash64.Streaming stream) {

        for (StackTraceElementProxy elp : trace) {
            StackTraceElement el = elp.getStackTraceElement();
            String className = el.getClassName();

            stream.update(NEWLINE_BYTES, 0, 1);
            String methodName = el.getMethodName();

            JavaStackSanitizer.addFromTraceElement(stream, className, methodName);
        }
    }

    /**
     * Writes normalized proxy frame content for all frames to a string buffer.
     *
     * @param trace logback proxy stack trace elements
     * @param sb target buffer
     */
    public static void addFromTraceToStringBuffer(
            StackTraceElementProxy[] trace,
            StringBuffer sb) {

        final String NEWLINE = "\n";
        final String DOT = ".";
        final String LAMBDA_METHOD = "lambda";

        for (StackTraceElementProxy elp : trace) {
            StackTraceElement el = elp.getStackTraceElement();
            String className = el.getClassName();

            sb.append(NEWLINE);

            String methodName = el.getMethodName();

            int lambdaClassIdx = className.indexOf(LAMBDA_SUFFIX_FOR_CLASS);
            int classEnd = (lambdaClassIdx != -1) ? lambdaClassIdx : className.length();
            sb.append(className, 0, classEnd);

            sb.append(DOT);

            if (lambdaClassIdx != -1) {
                sb.append(LAMBDA_METHOD);
            } else {
                if (methodName.startsWith(LAMBDA_PREFIX_FOR_METHOD)) {
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
        }
    }

    /**
     * Writes normalized proxy frame content for all frames to an output stream with raw newline separators.
     *
     * @param trace logback proxy stack trace elements
     * @param out target stream
     * @throws IOException if writing fails
     */
    public static void addFromTraceToOutputStream(
            StackTraceElementProxy[] trace,
            OutputStream out) throws IOException {
        addFromTraceToOutputStreamWithNewline(trace, out, NEWLINE_BYTES);
    }

    /**
     * Writes normalized proxy frame content for all frames to an output stream with JSON-escaped newline separators.
     *
     * @param trace logback proxy stack trace elements
     * @param out target stream
     * @throws IOException if writing fails
     */
    public static void addFromTraceToOutputStreamJson(
            StackTraceElementProxy[] trace,
            OutputStream out) throws IOException {
        addFromTraceToOutputStreamWithNewline(trace, out, NEWLINE_JSON_BYTES);
    }

    /**
     * Writes normalized proxy frame content for all frames using caller-provided newline bytes.
     *
     * @param trace logback proxy stack trace elements
     * @param out target stream
     * @param newlineBytes delimiter bytes placed before each frame
     * @throws IOException if writing fails
     */
    public static void addFromTraceToOutputStreamWithNewline(
            StackTraceElementProxy[] trace,
            OutputStream out,
            byte[] newlineBytes) throws IOException {

        for (StackTraceElementProxy elp : trace) {
            StackTraceElement el = elp.getStackTraceElement();
            String className = el.getClassName();

            out.write(newlineBytes);

            String methodName = el.getMethodName();

            int lambdaClassIdx = className.indexOf(LAMBDA_SUFFIX_FOR_CLASS);
            int classEnd = (lambdaClassIdx != -1) ? lambdaClassIdx : className.length();
            JavaStackTraceWriter.stringWriteStrategy.write(out, className.substring(0, classEnd));

            out.write(DOT_BYTES);

            if (lambdaClassIdx != -1) {
                out.write(LAMBDA_METHOD_BYTES);
            } else {
                if (methodName.startsWith(LAMBDA_PREFIX_FOR_METHOD)) {
                    int firstDollar = methodName.indexOf('$');
                    if (firstDollar != -1) {
                        int start = firstDollar + 1;
                        int secondDollar = methodName.indexOf('$', firstDollar + 1);
                        int end = (secondDollar != -1) ? secondDollar : methodName.length();
                        JavaStackTraceWriter.stringWriteStrategy.write(out, methodName.substring(start, end));
                    } else {
                        JavaStackTraceWriter.stringWriteStrategy.write(out, methodName);
                    }
                } else {
                    JavaStackTraceWriter.stringWriteStrategy.write(out, methodName);
                }
            }
        }
    }

    /**
     * Writes normalized proxy frame content for all frames with JSON-escaped newline separators
     * and computes the fingerprint in one pass.
     * <p>
     * This is the no-filter single-pass counterpart to
     * {@link JavaStackSanitizerLogback#addFromTraceToOutputStreamJsonAndFingerprint(StackTraceElementProxy[], Predicate, OutputStream, String)}.
     * Every frame is included (no filtering, no fallback), and the produced hash is identical to
     * the sanitizer path invoked with an accept-all predicate.
     *
     * @param trace logback proxy stack trace elements
     * @param out target stream
     * @param throwableClassName exception class name to include in hash seed, may be null
     * @return deterministic 64-bit hash
     * @throws IOException if writing fails
     */
    public static long addFromTraceToOutputStreamJsonAndFingerprint(
            StackTraceElementProxy[] trace,
            OutputStream out,
            String throwableClassName) throws IOException {
        Wyhash64.Streaming stream = new Wyhash64.Streaming(0);
        return addFromTraceToOutputStreamJsonAndFingerprint(trace, out, throwableClassName, stream);
    }

    /**
     * Writes normalized proxy frame content for all frames with JSON-escaped newline separators
     * and computes the fingerprint in one pass, using a caller-supplied reusable hasher.
     *
     * @param trace logback proxy stack trace elements
     * @param out target stream
     * @param throwableClassName exception class name to include in hash seed, may be null
     * @param stream reusable hasher instance, reset and reused on the hot path
     * @return deterministic 64-bit hash
     * @throws IOException if writing fails
     */
    public static long addFromTraceToOutputStreamJsonAndFingerprint(
            StackTraceElementProxy[] trace,
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
     * Writes normalized proxy frame content for all frames using caller-provided newline bytes
     * and computes the fingerprint in one pass.
     * <p>
     * For hash compatibility with {@link #fingerprint(IThrowableProxy, Predicate)} the hash
     * delimiter is always raw '\n'.
     *
     * @param trace logback proxy stack trace elements
     * @param out target stream
     * @param newlineBytes delimiter bytes placed before each frame
     * @param throwableClassName exception class name to include in hash seed, may be null
     * @param stream reusable hasher instance, reset and reused on the hot path
     * @return deterministic 64-bit hash
     * @throws IOException if writing fails
     */
    public static long addFromTraceToOutputStreamWithNewlineAndFingerprint(
            StackTraceElementProxy[] trace,
            OutputStream out,
            byte[] newlineBytes,
            String throwableClassName,
            Wyhash64.Streaming stream) throws IOException {

        stream.reset(0);
        if (throwableClassName != null) {
            stream.update(throwableClassName);
        }

        for (StackTraceElementProxy elp : trace) {
            StackTraceElement el = elp.getStackTraceElement();
            String className = el.getClassName();

            out.write(newlineBytes);
            stream.updateByte(NEWLINE_BYTE);

            String methodName = el.getMethodName();
            int lambdaClassIdx = className.indexOf(LAMBDA_SUFFIX_FOR_CLASS);
            int classEnd = (lambdaClassIdx != -1) ? lambdaClassIdx : className.length();
            JavaStackTraceWriter.stringWriteStrategy.write(out, className.substring(0, classEnd));
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
                    JavaStackTraceWriter.stringWriteStrategy.write(out, methodName.substring(start, end));
                    stream.update(methodName, start, end - start);
                } else {
                    JavaStackTraceWriter.stringWriteStrategy.write(out, methodName);
                    stream.update(methodName);
                }
            } else {
                JavaStackTraceWriter.stringWriteStrategy.write(out, methodName);
                stream.update(methodName);
            }
        }

        return stream.finalHash();
    }
}
