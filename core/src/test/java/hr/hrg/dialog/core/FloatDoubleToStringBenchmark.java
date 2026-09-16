package hr.hrg.dialog.core;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import hr.hrg.dialog.ryu.RyuFloat;
import hr.hrg.dialog.ryu.RyuDouble;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Float/Double toString comparison: all Java idiomatic ways of writing
 * float/double to textual representation.
 *
 * <p>Includes:
 * - Float.toString(float) + byte[]
 * - Double.toString(double) + byte[]
 * - Float.toString(float) + String.format("%f", ...)
 * - Double.toString(double) + String.format("%f", ...)
 * - RyuFloat.writeFloat() / RyuDouble.writeDouble() (direct buffer)
 * - JsonNumberWriter.writeFloat/writeDouble (delegates to Ryu)
 *
 * <p>Run:
 * {@code java -cp <classpath> org.openjdk.jmh.Main
 * hr.hrg.dialog.core.FloatDoubleToStringBenchmark -wi 3 -i 5 -f 1}
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class FloatDoubleToStringBenchmark {

    /** Value distribution driving the exponent/digit-count mix. */
    @Param({"tiny", "small", "medium", "large", "scientific", "negative"})
    public String distribution;

    private static final int N = 256;

    private float[] floatValues;
    private double[] doubleValues;
    private byte[] bufFloat;
    private byte[] bufDouble;
    private int indexFloat;
    private int indexDouble;

    @Benchmark
    public int floatToString(Blackhole bh) throws IOException {
        float value = floatValues[indexFloat++ & (N - 1)];
        byte[] bytes = Float.toString(value).getBytes(StandardCharsets.UTF_8);
        bh.consume(bytes[bytes.length - 1]);
        return bytes.length;
    }

    @Benchmark
    public int doubleToString(Blackhole bh) throws IOException {
        double value = doubleValues[indexDouble++ & (N - 1)];
        byte[] bytes = Double.toString(value).getBytes(StandardCharsets.UTF_8);
        bh.consume(bytes[bytes.length - 1]);
        return bytes.length;
    }

    @Benchmark
    public int floatToStringWithFormat(Blackhole bh) throws IOException {
        float value = floatValues[indexFloat++ & (N - 1)];
        byte[] bytes = String.format("%f", value).getBytes(StandardCharsets.UTF_8);
        bh.consume(bytes[bytes.length - 1]);
        return bytes.length;
    }

    @Benchmark
    public int doubleToStringWithFormat(Blackhole bh) throws IOException {
        double value = doubleValues[indexDouble++ & (N - 1)];
        byte[] bytes = String.format("%f", value).getBytes(StandardCharsets.UTF_8);
        bh.consume(bytes[bytes.length - 1]);
        return bytes.length;
    }

    @Benchmark
    public int ryuFloatWriteFloat(Blackhole bh) {
        int len = RyuFloat.writeFloat(floatValues[indexFloat++ & (N - 1)], bufFloat, 0);
        bh.consume(bufFloat[len - 1]);
        return len;
    }

    @Benchmark
    public int ryuDoubleWriteDouble(Blackhole bh) {
        int len = RyuDouble.writeDouble(doubleValues[indexDouble++ & (N - 1)], bufDouble, 0);
        bh.consume(bufDouble[len - 1]);
        return len;
    }

    @Benchmark
    public int jsonNumberWriterFloat(Blackhole bh) {
        int len = JsonNumberWriter.writeFloat(bufFloat, 0, floatValues[indexFloat++ & (N - 1)]);
        bh.consume(bufFloat[len - 1]);
        return len;
    }

    @Benchmark
    public int jsonNumberWriterDouble(Blackhole bh) {
        int len = JsonNumberWriter.writeDouble(bufDouble, 0, doubleValues[indexDouble++ & (N - 1)]);
        bh.consume(bufDouble[len - 1]);
        return len;
    }

    @Override
    public String toString() {
        return "FloatDoubleToStringBenchmark";
    }

    @Override
    public boolean equals(Object obj) {
        return obj == this;
    }

    @Setup(Level.Trial)
    public void setup() {
        floatValues = new float[N];
        doubleValues = new double[N];
        bufFloat = new byte[JsonNumberWriter.MAX_FLOAT_BYTES];
        bufDouble = new byte[JsonNumberWriter.MAX_DOUBLE_BYTES];
        Random rnd = new Random(0x5EED_CAFEL);
        for (int i = 0; i < N; i++) {
            floatValues[i] = switch (distribution) {
                case "tiny" -> rnd.nextFloat() / 100f;
                case "small" -> rnd.nextFloat() * 100f;
                case "medium" -> rnd.nextFloat() * 1_000_000f;
                case "large" -> rnd.nextFloat() * 1e8f;
                case "scientific" -> Float.MAX_VALUE;
                case "negative" -> -1 - rnd.nextFloat() * 1e8f;
                default -> rnd.nextFloat();
            };
            doubleValues[i] = switch (distribution) {
                case "tiny" -> rnd.nextDouble() / 1000.0;
                case "small" -> rnd.nextDouble() * 100.0;
                case "medium" -> rnd.nextDouble() * 1_000_000.0;
                case "large" -> rnd.nextDouble() * 1_000_000_000.0;
                case "scientific" -> Double.MAX_VALUE;
                case "negative" -> -1 - rnd.nextDouble() * 1e16;
                default -> rnd.nextDouble();
            };
        }
        indexFloat = 0;
        indexDouble = 0;
    }
}
