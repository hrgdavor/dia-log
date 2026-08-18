package hr.hrg.dialog.core;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
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
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * Allocation-focused benchmark for the core zero-allocation hot paths.
 * <p>
 * Run with the gc profiler to get bytes/op:
 * <pre>
 * java -cp &lt;test classpath&gt; org.openjdk.jmh.Main AllocationBenchmark \
 *     -prof gc -wi 2 -i 3 -f 1 -t 1
 * </pre>
 * Requires {@code --add-opens java.base/java.lang=ALL-UNNAMED} to exercise the
 * VarHandle fast paths (otherwise the String/escaping fallbacks allocate).
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
@State(Scope.Thread)
public class AllocationBenchmark {

    private static final Predicate<String> ACCEPT_ALL = cls -> true;

    private String latin1;
    private String utf16;
    private char[] latin1Chars;
    private byte[] bytes;
    private Throwable throwable;
    private StackTraceElement[] trace;
    private String throwableClassName;
    private Wyhash64.Streaming reusableStream;
    private ByteArrayOutputStream output;
    private final byte[] floatBuffer = JsonNumberWriter.makeFloatBuffer();
    private final byte[] doubleBuffer = JsonNumberWriter.makeDoubleBuffer();

    @Setup(Level.Trial)
    public void setup() throws Exception {
        latin1 = "com.example.MyService.doStuff(userId=42) tenant=acme";
        utf16 = "héllo wörld 🚀 Ñoño — user {userId} in tenant {tenant}";
        latin1Chars = "the quick brown fox jumps over the lazy dog".toCharArray();
        bytes = "the quick brown fox jumps over the lazy dog".getBytes(StandardCharsets.UTF_8);
        throwable = createThrowable();
        trace = throwable.getStackTrace();
        throwableClassName = throwable.getClass().getName();
        reusableStream = new Wyhash64.Streaming(0);
        output = new ByteArrayOutputStream();
    }

    // ---- Wyhash64 ----------------------------------------------------------

    @Benchmark
    public long hashStringLatin1() {
        return Wyhash64.hash(0, latin1);
    }

    @Benchmark
    public long hashStringUtf16() {
        return Wyhash64.hash(0, utf16);
    }

    @Benchmark
    public long hashCharArray() {
        return Wyhash64.hash(0, latin1Chars);
    }

    @Benchmark
    public long hashByteArray() {
        return Wyhash64.hash(0, bytes);
    }

    @Benchmark
    public long streamingReused() {
        reusableStream.reset(0);
        reusableStream.update(latin1);
        return reusableStream.finalHash();
    }

    @Benchmark
    public long streamingNewPerCall() {
        Wyhash64.Streaming s = new Wyhash64.Streaming(0);
        s.update(latin1);
        return s.finalHash();
    }

    // ---- string / number output --------------------------------------------

    @Benchmark
    public int escapedJsonString() throws IOException {
        output.reset();
        EscapedJsonStringWriter.writeJsonStringOrNull(output, latin1);
        return output.size();
    }

    @Benchmark
    public int stringBytes() throws IOException {
        output.reset();
        StringByteExtractor.getStrategy().write(output, latin1);
        return output.size();
    }

    @Benchmark
    public int floatWrite() throws IOException {
        output.reset();
        JsonNumberWriter.writeFloat(output, floatBuffer, 1.2345678f);
        return output.size();
    }

    @Benchmark
    public int doubleWrite() throws IOException {
        output.reset();
        JsonNumberWriter.writeDouble(output, doubleBuffer, 1.2345678901234567d);
        return output.size();
    }

    // ---- stack-trace fingerprinting ----------------------------------------
    // There are deliberately NO convenience overloads: the fingerprint entry
    // points require a caller-owned reusable hasher, so the only fingerprint
    // rows are the reused-hasher variants plus the isolation rows below.

    @Benchmark
    public long fingerprintReusedStream() {
        return JavaStackSanitizer.fingerprint(throwable, ACCEPT_ALL, reusableStream);
    }

    @Benchmark
    public void fingerprintConsume(Blackhole blackhole) {
        blackhole.consume(JavaStackSanitizer.fingerprint(throwable, ACCEPT_ALL, reusableStream));
        blackhole.consume(trace.length);
        blackhole.consume(throwableClassName.length());
    }

    // Isolation rows for the fingerprint breakdown: they pin down exactly where
    // the bytes in fingerprint(Throwable, ...) come from.
    @Benchmark
    public int getStackTraceCloneOnly() {
        // JDK behavior: Throwable.getStackTrace() returns a defensive copy.
        return throwable.getStackTrace().length;
    }

    @Benchmark
    public long fingerprintFromTraceReused() {
        // The zero-allocation alternative: fingerprint a prepared trace into a
        // caller-owned hasher — no getStackTrace() copy, no hasher allocation.
        return JavaStackSanitizer.fingerprintFromTrace(trace, ACCEPT_ALL, throwableClassName, reusableStream);
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
        throw new IllegalStateException("root-cause");
    }
}
