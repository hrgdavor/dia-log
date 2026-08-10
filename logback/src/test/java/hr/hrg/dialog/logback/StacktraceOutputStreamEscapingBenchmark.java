package hr.hrg.dialog.logback;

import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.classic.spi.ThrowableProxy;
import hr.hrg.dialog.core.EscapedJsonStringWriter;
import hr.hrg.dialog.core.JavaStackTraceWriter;
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
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Compare stacktrace JSON-string writing paths:
 * - Optimized direct output-stream writer with escaped newlines
 * - printStackTrace() to StringWriter, then escaped JSON string writing
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class StacktraceOutputStreamEscapingBenchmark {

    private Throwable rawThrowable;
    private ThrowableProxy throwableProxy;
    private StackTraceElementProxy[] proxyFrames;
    private byte[] throwableClassBytes;
    private ReusableByteArrayOutputStream output;

    @Setup(org.openjdk.jmh.annotations.Level.Trial)
    public void setup() {
        rawThrowable = createThrowable();
        throwableProxy = new ThrowableProxy(rawThrowable);
        proxyFrames = throwableProxy.getStackTraceElementProxyArray();
        throwableClassBytes = throwableProxy.getClassName() == null
            ? null
            : throwableProxy.getClassName().getBytes(StandardCharsets.UTF_8);
        output = new ReusableByteArrayOutputStream(32 * 1024);
    }

    @Benchmark
    public void optimizedOutputStreamEscapedNewlines(Blackhole blackhole) throws IOException {
        output.reset();
        output.write('"');

        StackTraceElement[] arr = new StackTraceElement[proxyFrames.length];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = proxyFrames[i].getStackTraceElement();
        }

        if (throwableClassBytes != null) {
            output.write(throwableClassBytes);
        }
        JavaStackTraceWriter.addFromTraceToOutputStreamJson(arr, output);

        output.write('"');
        blackhole.consume(output.size());
        blackhole.consume(output.tailChecksum());
    }

    @Benchmark
    public void printStackTraceThenEscapedJsonStringWriter(Blackhole blackhole) throws IOException {
        output.reset();

        StringWriter sw = new StringWriter(4096);
        try (PrintWriter pw = new PrintWriter(sw)) {
            rawThrowable.printStackTrace(pw);
        }

        EscapedJsonStringWriter.writeJsonStringOrNull(output, sw.toString());
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
