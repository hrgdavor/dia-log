package hr.hrg.dialog.core;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * Compares separate-pass and single-pass stacktrace output + fingerprint computation.
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class StacktraceWriteAndFingerprintBenchmark {

    private static final Predicate<String> ACCEPT_ALL = cls -> true;

    private Throwable throwable;
    private StackTraceElement[] trace;
    private String throwableClassName;
    private ReusableByteArrayOutputStream output;
    private Wyhash64.Streaming reusableStream;

    @Setup(org.openjdk.jmh.annotations.Level.Trial)
    public void setup() {
        throwable = createThrowable();
        trace = throwable.getStackTrace();
        throwableClassName = throwable.getClass().getName();
        output = new ReusableByteArrayOutputStream(32 * 1024);
        reusableStream = new Wyhash64.Streaming(0);
    }

    @Benchmark
    public void separatePassRawNewline(Blackhole blackhole) throws IOException {
        output.reset();
        JavaStackSanitizer.addFromTraceToOutputStream(trace, ACCEPT_ALL, output);
        long hash = JavaStackSanitizer.fingerprintFromTrace(trace, ACCEPT_ALL, throwableClassName, reusableStream);

        blackhole.consume(hash);
        blackhole.consume(output.size());
        blackhole.consume(output.tailChecksum());
    }

    @Benchmark
    public void singlePassRawNewline(Blackhole blackhole) throws IOException {
        output.reset();
        long hash = JavaStackSanitizer.addFromTraceToOutputStreamAndFingerprint(
            trace,
            ACCEPT_ALL,
            output,
            throwableClassName,
            reusableStream
        );

        blackhole.consume(hash);
        blackhole.consume(output.size());
        blackhole.consume(output.tailChecksum());
    }

    @Benchmark
    public void separatePassJsonEscapedNewline(Blackhole blackhole) throws IOException {
        output.reset();
        JavaStackSanitizer.addFromTraceToOutputStreamJson(trace, ACCEPT_ALL, output);
        long hash = JavaStackSanitizer.fingerprintFromTrace(trace, ACCEPT_ALL, throwableClassName, reusableStream);

        blackhole.consume(hash);
        blackhole.consume(output.size());
        blackhole.consume(output.tailChecksum());
    }

    @Benchmark
    public void singlePassJsonEscapedNewline(Blackhole blackhole) throws IOException {
        output.reset();
        long hash = JavaStackSanitizer.addFromTraceToOutputStreamJsonAndFingerprint(
            trace,
            ACCEPT_ALL,
            output,
            throwableClassName,
            reusableStream
        );

        blackhole.consume(hash);
        blackhole.consume(output.size());
        blackhole.consume(output.tailChecksum());
    }

    @Benchmark
    public void fingerprintOnlyPreparedTrace(Blackhole blackhole) {
        long hash = JavaStackSanitizer.fingerprintFromTrace(trace, ACCEPT_ALL, throwableClassName, reusableStream);
        blackhole.consume(hash);
    }

    @Benchmark
    public void writeOnlyRawNewline(Blackhole blackhole) throws IOException {
        output.reset();
        JavaStackSanitizer.addFromTraceToOutputStream(trace, ACCEPT_ALL, output);
        blackhole.consume(output.size());
        blackhole.consume(output.tailChecksum());
    }

    @Benchmark
    public void writeOnlyJsonEscapedNewline(Blackhole blackhole) throws IOException {
        output.reset();
        JavaStackSanitizer.addFromTraceToOutputStreamJson(trace, ACCEPT_ALL, output);
        blackhole.consume(output.size());
        blackhole.consume(output.tailChecksum());
    }

    private static Throwable createThrowable() {
        try {
            level1();
            return null;
        } catch (RuntimeException e) {
            return e;
        }
    }

    private static void level1() {
        level2();
    }

    private static void level2() {
        level3();
    }

    private static void level3() {
        try {
            throw new IllegalArgumentException("root-cause");
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("wrapper-runtime", e);
        }
    }

    public static class ReusableByteArrayOutputStream extends ByteArrayOutputStream {
        public ReusableByteArrayOutputStream(int size) {
            super(size);
        }

        public int tailChecksum() {
            if (count == 0) {
                return 0;
            }
            int first = buf[0] & 0xFF;
            int last = buf[count - 1] & 0xFF;
            return (count * 31) ^ (first << 8) ^ last;
        }
    }
}
