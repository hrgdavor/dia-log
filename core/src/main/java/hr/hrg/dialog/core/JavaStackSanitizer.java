package hr.hrg.dialog.core;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
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
 *
 * <h2>Derivative generator markers</h2>
 * This file is also the input for {@code StackSanitizerDerivativeGenerator}, which derives three
 * sibling classes from it:
 * <ul>
 *   <li>{@code JavaStackTraceWriter}      — core, no-filter (drops {@code Predicate} and fallback)</li>
 *   <li>{@code JavaStackSanitizerLogback} — logback, filter-enabled ({@code IThrowableProxy} input)</li>
 *   <li>{@code JavaStackWriterLogback}    — logback, no-filter</li>
 * </ul>
 * The generator reads marker comment lines that annotate which variant owns the following line:
 * <pre>{@code
 *   // @core            next line: kept in JavaStackTraceWriter, commented out in the logback variants
 *   // @sanitizer       next line: kept in JavaStackSanitizerLogback, commented out in the writers
 *   // @writer          next line: kept in JavaStackWriterLogback, commented out elsewhere
 *   // @core,writer     next line: kept in JavaStackTraceWriter and JavaStackWriterLogback
 *   // @restore:x,y     next (already-commented) line: uncommented in variants x,y
 * }</pre>
 * Lines without a preceding marker are shared and kept in every variant. The generator also applies
 * the input/accessor substitutions for logback ({@code StackTraceElement[]} → {@code StackTraceElementProxy[]},
 * {@code el} → {@code elp.getStackTraceElement()}, {@code Throwable} → {@code IThrowableProxy},
 * {@code rootCause.getClass().getName()} → {@code rootCause.getClassName()}, etc.). Code not needed
 * in a variant is kept in the source but commented out in the generated derivative, never deleted.
 */
public class JavaStackSanitizer {

    public static final byte[] DOT_BYTES = {'.'};
    public static final byte[] NEWLINE_BYTES = {'\n'};
    public static final byte[] NEWLINE_JSON_BYTES = {'\\','n'};
    public static final byte[] LAMBDA_METHOD_BYTES = "lambda".getBytes(StandardCharsets.UTF_8);
    private static final byte DOT_BYTE = '.';
    private static final byte NEWLINE_BYTE = '\n';
    public static final StringByteExtractor.ByteWriter stringWriteStrategy = StringByteExtractor.getStrategy();
    public static final String LAMBDA_SUFFIX_FOR_CLASS = "$$Lambda$";
    public static final String LAMBDA_PREFIX_FOR_METHOD = "lambda$";

    /**
     * Builds a deterministic fingerprint for a throwable using a caller-supplied hasher.
     * <p>
     * The hash input begins with the exception class name and then appends the
     * sanitized frame sequence produced by {@link #addFromTrace(StackTraceElement[], Predicate, Wyhash64.Streaming)}.
     * <p>
     * <b>Reuse policy:</b> {@code stream} is a <i>caller-owned reusable</i> hasher —
     * pass a long-lived instance (e.g. a field of the logger/appender, like the
     * reusable number buffers); this method resets it to seed 0 internally. There is
     * deliberately <i>no</i> no-stream convenience overload and no hidden
     * {@link ThreadLocal} state: scratch state is passed as a parameter so the caller
     * controls allocation and reuse explicitly (project guideline: prefer reusable
     * objects as parameters over ThreadLocal — see {@code AGENTS.md}).
     * The only fast-path per-call allocation is the defensive {@code StackTraceElement[]}
     * copy that {@link Throwable#getStackTrace()} returns by JDK design; to avoid even
     * that copy, fingerprint an already-materialized trace with
     * {@link #fingerprintFromTrace(StackTraceElement[], Predicate, String, Wyhash64.Streaming)}.
     *
     * @param rootCause throwable whose stack trace is fingerprinted
     * @param filter frame class filter used to include application-relevant frames
     * @param stream reusable streaming hash sink, reset to seed 0 by this call
     * @return deterministic 64-bit hash
     */
    public static long fingerprint(Throwable rootCause, Predicate<String> filter, Wyhash64.Streaming stream) {
        stream.reset(0);

        // 1. Exception type
        // @core
        stream.update(rootCause.getClass().getName());
        // @restore:sanitizer,writer
        // stream.update(rootCause.getClassName());

        // @core
        addFromTrace(rootCause.getStackTrace(), filter, stream);
        // @restore:sanitizer
        // addFromTrace(rootCause.getStackTraceElementProxyArray(), filter, stream);
        // @restore:writer
        // addFromTrace(rootCause.getStackTraceElementProxyArray(), stream);

        return stream.finalHash();
    }

    /**
     * Builds a deterministic fingerprint from a prepared stack trace into a reusable hasher.
     * <p>
     * This variant avoids calling {@link Throwable#getStackTrace()} and uses
     * zero-allocation-capable {@link Wyhash64.Streaming#update(String)} updates
     * for normalized class/method segments. Like all fingerprint entry points,
     * {@code stream} is a caller-owned reusable hasher (no hidden ThreadLocal
     * state); it is reset to seed 0 by this call.
     *
     * @param trace stack trace elements to process
     * @param filter frame class filter used to include application-relevant frames
     * @param throwableClassName exception class name to prepend to the hash payload, may be null
     * @param stream reusable streaming hash sink
     * @return deterministic 64-bit hash
     */
    // @source
    public static long fingerprintFromTrace(
            StackTraceElement[] trace,
            Predicate<String> filter,
            String throwableClassName,
            Wyhash64.Streaming stream) {
        stream.reset(0);

        if (throwableClassName != null) {
            stream.update(throwableClassName);
        }

        // @sanitizer
        boolean isFirstFrame = true;

        // @core
        for (StackTraceElement el : trace) {
        // @restore:sanitizer,writer
        // for (StackTraceElementProxy elp : trace) {
        // @restore:sanitizer,writer
        //     StackTraceElement el = elp.getStackTraceElement();
            String className = el.getClassName();
            // @sanitizer
            if (!filter.test(className)) continue;

            // @sanitizer
            isFirstFrame = false;
            stream.updateByte(NEWLINE_BYTE);

            String methodName = el.getMethodName();
            int lambdaClassIdx = className.indexOf(LAMBDA_SUFFIX_FOR_CLASS);
            int classEnd = (lambdaClassIdx != -1) ? lambdaClassIdx : className.length();

            stream.update(className, 0, classEnd);
            stream.update(DOT_BYTES, 0, 1);

            if (lambdaClassIdx != -1) {
                stream.update(LAMBDA_METHOD_BYTES, 0, LAMBDA_METHOD_BYTES.length);
            } else {
                int start = 0;
                int end = methodName.length();

                if (methodName.startsWith(LAMBDA_PREFIX_FOR_METHOD)) {
                    int firstDollar = methodName.indexOf('$');
                    if (firstDollar != -1) {
                        start = firstDollar + 1;
                        int secondDollar = methodName.indexOf('$', firstDollar + 1);
                        if (secondDollar != -1) {
                            end = secondDollar;
                        }
                    }
                }

                stream.update(methodName, start, end - start);
            }
        }

        // @sanitizer:begin
        if (isFirstFrame) {
            int limit = Math.min(3, trace.length);
            for (int i = 0; i < limit; i++) {
                stream.updateByte(NEWLINE_BYTE);
                StackTraceElement el = trace[i];
                stream.update(el.getClassName());
                stream.updateByte(DOT_BYTE);
                stream.update(el.getMethodName());
            }
        }
        // @sanitizer:end

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
        // @sanitizer
        boolean isFirstFrame = true;

        // @core
        for (StackTraceElement el : trace) {
        // @restore:sanitizer,writer
        // for (StackTraceElementProxy elp : trace) {
        // @restore:sanitizer,writer
        //     StackTraceElement el = elp.getStackTraceElement();
            String className = el.getClassName();
            // @sanitizer
            if (!filter.test(className)) continue;

            // @sanitizer
            isFirstFrame = false;
            stream.updateByte(NEWLINE_BYTE);
            String methodName = el.getMethodName();

            // @core
            addFromTraceElement(stream, className, methodName);
            // @restore:sanitizer,writer
            // JavaStackSanitizer.addFromTraceElement(stream, className, methodName);
        }

        // Fallback: if all frames were skipped, hash top 3 (cleaned)
        // @sanitizer
        if (isFirstFrame) {printTracesFallback(trace, stream);}
    }

    // @sanitizer
    private static void printTracesFallback(StackTraceElement[] trace, Wyhash64.Streaming stream) {
        int limit = Math.min(3, trace.length);
        for (int i = 0; i < limit; i++) {
            stream.updateByte(NEWLINE_BYTE);
            StackTraceElement el = trace[i];
            stream.update(el.getClassName());
            stream.updateByte(DOT_BYTE);
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
    // @core
    public static void addFromTraceElement(Wyhash64.Streaming stream, String className, String methodName) {
        // -------- Class name (strip $$Lambda$ suffix) --------
        int lambdaClassIdx = className.indexOf(LAMBDA_SUFFIX_FOR_CLASS);
        int classEnd = (lambdaClassIdx != -1) ? lambdaClassIdx : className.length();
        // Feed only the enclosing class part (skip synthetic suffix).
        stream.update(className, 0, classEnd);

        stream.updateByte(DOT_BYTE);

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

        // @sanitizer
        boolean isFirstFrame = true;

        // @core
        for (StackTraceElement el : trace) {
        // @restore:sanitizer,writer
        // for (StackTraceElementProxy elp : trace) {
        // @restore:sanitizer,writer
        //     StackTraceElement el = elp.getStackTraceElement();
            String className = el.getClassName();

            // Apply filter; skip frames that don't match
            // @sanitizer:begin
            if (!filter.test(className)) {
                continue;
            }
            // @sanitizer:end

            // Delimiter before each frame (matches streaming hash behaviour)
            sb.append(NEWLINE);
            // @sanitizer
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
        // @sanitizer:begin
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
        // @sanitizer:end
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
         * Writes sanitized frames to output and computes fingerprint using a reusable hasher.
         * {@code stream} is a caller-owned reusable hasher (no hidden ThreadLocal state),
         * reset to seed 0 by this call.
         */
        // @source
        public static long addFromTraceToOutputStreamAndFingerprint(
            StackTraceElement[] trace,
            Predicate<String> filter,
            OutputStream out,
            String throwableClassName,
            Wyhash64.Streaming stream) throws IOException {
            return addFromTraceToOutputStreamWithNewlineAndFingerprint(trace, filter, out, NEWLINE_BYTES, throwableClassName, stream);
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
         * Writes sanitized frames with JSON-escaped newlines and computes fingerprint using a reusable hasher.
         * {@code stream} is a caller-owned reusable hasher (no hidden ThreadLocal state),
         * reset to seed 0 by this call.
         */
        public static long addFromTraceToOutputStreamJsonAndFingerprint(
            StackTraceElement[] trace,
            Predicate<String> filter,
            OutputStream out,
            String throwableClassName,
            Wyhash64.Streaming stream) throws IOException {
            return addFromTraceToOutputStreamWithNewlineAndFingerprint(trace, filter, out, NEWLINE_JSON_BYTES, throwableClassName, stream);
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

        // @sanitizer
        boolean isFirstFrame = true;

        // @core
        for (StackTraceElement el : trace) {
        // @restore:sanitizer,writer
        // for (StackTraceElementProxy elp : trace) {
        // @restore:sanitizer,writer
        //     StackTraceElement el = elp.getStackTraceElement();
            String className = el.getClassName();

            // Apply filter; skip frames that don't match
            // @sanitizer:begin
            if (!filter.test(className)) {
                continue;
            }
            // @sanitizer:end

            // Delimiter before each frame (matches streaming hash behaviour)
            out.write(newlineBytes);
            // @sanitizer
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
        // @sanitizer:begin
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
        // @sanitizer:end
    }

    /**
     * Writes sanitized frames using caller-provided newline bytes and computes fingerprint in one pass.
     * Uses a caller-supplied reusable hasher instance ({@code stream} is caller-owned,
     * no hidden ThreadLocal state, reset to seed 0 by this call).
     */
    public static long addFromTraceToOutputStreamWithNewlineAndFingerprint(
            StackTraceElement[] trace,
            Predicate<String> filter,
            OutputStream out,
            byte[] newlineBytes,
            String throwableClassName,
            Wyhash64.Streaming stream) throws IOException {

        stream.reset(0);
        if (throwableClassName != null) {
            stream.update(throwableClassName);
        }

        // @sanitizer
        boolean isFirstFrame = true;

        // @core
        for (StackTraceElement el : trace) {
        // @restore:sanitizer,writer
        // for (StackTraceElementProxy elp : trace) {
        // @restore:sanitizer,writer
        //     StackTraceElement el = elp.getStackTraceElement();
            String className = el.getClassName();

            // @sanitizer:begin
            if (!filter.test(className)) {
                continue;
            }
            // @sanitizer:end

            out.write(newlineBytes);
            // @sanitizer
            isFirstFrame = false;

            stream.updateByte(NEWLINE_BYTE);

            String methodName = el.getMethodName();

            int lambdaClassIdx = className.indexOf(LAMBDA_SUFFIX_FOR_CLASS);
            int classEnd = (lambdaClassIdx != -1) ? lambdaClassIdx : className.length();
            stringWriteStrategy.write(out,className.substring(0, classEnd));
            stream.update(className, 0, classEnd);

            out.write(DOT_BYTES);
            stream.updateByte(DOT_BYTE);

            if (lambdaClassIdx != -1) {
                out.write(LAMBDA_METHOD_BYTES);
                stream.update(LAMBDA_METHOD_BYTES, 0, LAMBDA_METHOD_BYTES.length);
            } else {
                int start = 0;
                int end = methodName.length();

                if (methodName.startsWith(LAMBDA_PREFIX_FOR_METHOD)) {
                    int firstDollar = methodName.indexOf('$');
                    if (firstDollar != -1) {
                        start = firstDollar + 1;
                        int secondDollar = methodName.indexOf('$', firstDollar + 1);
                        if (secondDollar != -1) {
                            end = secondDollar;
                        }
                    }
                }

                stringWriteStrategy.write(out,methodName.substring(start, end));
                stream.update(methodName, start, end - start);
            }
        }

        // @sanitizer:begin
        if (isFirstFrame) {
            int limit = Math.min(3, trace.length);
            for (int i = 0; i < limit; i++) {
                out.write(newlineBytes);
                stream.updateByte(NEWLINE_BYTE);
                StackTraceElement el = trace[i];
                String className = el.getClassName();
                String methodName = el.getMethodName();
                stringWriteStrategy.write(out,className);
                out.write(DOT_BYTES);
                stringWriteStrategy.write(out,methodName);
                stream.update(className);
                stream.updateByte(DOT_BYTE);
                stream.update(methodName);
            }
        }
        // @sanitizer:end

        return stream.finalHash();
    }
}
