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

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class StackTraceTraversalBenchmark {

    private StackWalker stackWalker;

    @Setup
    public void setup() {
        stackWalker = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    }

    @Benchmark
    public int benchmarkThrowableStackTraceArray() {
        Throwable t = new Throwable();
        StackTraceElement[] trace = t.getStackTrace();
        int result = 1;

        for (StackTraceElement element : trace) {
            result = 31 * result + element.getClassName().hashCode();
            result = 31 * result + element.getMethodName().hashCode();
        }

        return result;
    }

    @Benchmark
    public int benchmarkThrowableStackTraceArrayWyhashZeroAlloc() {
        Throwable t = new Throwable();
        StackTraceElement[] trace = t.getStackTrace();
        Wyhash64.Streaming streaming = new Wyhash64.Streaming(0);

        for (StackTraceElement element : trace) {
            streaming.update(element.getClassName());
            streaming.update(element.getMethodName());
        }

        return (int) streaming.finalHash();
    }

    @Benchmark
    public int benchmarkThrowableStackTraceArrayWyhashFallback() {
        Throwable t = new Throwable();
        StackTraceElement[] trace = t.getStackTrace();
        Wyhash64.Streaming streaming = new Wyhash64.Streaming(0);

        for (StackTraceElement element : trace) {
            streaming.update(element.getClassName().getBytes(StandardCharsets.UTF_8));
            streaming.update(element.getMethodName().getBytes(StandardCharsets.UTF_8));
        }

        return (int) streaming.finalHash();
    }

    @Benchmark
    public int benchmarkStackWalkerEAFriendly() {
        return stackWalker.walk(stream -> stream
                .mapToInt(StackTraceTraversalBenchmark::frameHash)
                .sum());
    }

    @Benchmark
    public int benchmarkStackWalkerNonEAFriendly() {
        return stackWalker.walk(stream -> {
            int[] accumulator = {0};
            stream.forEach(frame -> accumulator[0] = 31 * accumulator[0] + frameHash(frame));
            return accumulator[0];
        });
    }

    private static int frameHash(StackWalker.StackFrame frame) {
        int h = frame.getClassName().hashCode();
        return 31 * h + frame.getMethodName().hashCode();
    }

    private void nestedCallLevel1() {
        nestedCallLevel2();
    }

    private void nestedCallLevel2() {
        nestedCallLevel3();
    }

    private void nestedCallLevel3() {
        throw new IllegalStateException("Benchmark exception");
    }
}
