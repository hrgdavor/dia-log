package hr.hrg.dialog.core;

import hr.hrg.dialog.core.perf.JeaiiiPairsWriter;

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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Integral number toString comparison: all Java idiomatic ways of writing
 * int/long to textual representation.
 *
 * <p>Includes:
 * - Integer.toString(int) + byte[]
 * - Long.toString(long) + byte[]
 * - JsonNumberWriter.writeInt/Long (direct buffer, no allocation)
 * - JeaiiiFastWriter.writeIntToBytes/writeLongToBytes (direct buffer)
 * - ClassicJsonNumberWriter (digit-by-digit, scratch buffer)
 *
 * <p>Run:
 * {@code java -cp <classpath> org.openjdk.jmh.Main
 * hr.hrg.dialog.core.IntegralNumberToStringBenchmark -wi 3 -i 5 -f 1}
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class IntegralNumberToStringBenchmark {

    /** Value distribution driving the digit-count mix. */
    @Param({"tiny", "small", "medium", "timestamp", "full", "negative"})
    public String distribution;

    private static final int N = 256;

    private int[] intValues;
    private long[] longValues;
    private byte[] bufInt;
    private byte[] bufLong;
    private int indexInt;
    private int indexLong;

    @Benchmark
    public int integerToString(Blackhole bh) throws IOException {
        int value = intValues[indexInt++ & (N - 1)];
        byte[] bytes = Integer.toString(value).getBytes(StandardCharsets.UTF_8);
        bh.consume(bytes[bytes.length - 1]);
        return bytes.length;
    }

    @Benchmark
    public int longToString(Blackhole bh) throws IOException {
        long value = longValues[indexLong++ & (N - 1)];
        byte[] bytes = Long.toString(value).getBytes(StandardCharsets.UTF_8);
        bh.consume(bytes[bytes.length - 1]);
        return bytes.length;
    }

    @Benchmark
    public int jsonNumberWriterInt(Blackhole bh) {
        int pos = JsonNumberWriter.writeInt(bufInt, 0, intValues[indexInt++ & (N - 1)]);
        bh.consume(bufInt[pos - 1]);
        return pos;
    }

    @Benchmark
    public int jsonNumberWriterLong(Blackhole bh) {
        int pos = JsonNumberWriter.writeLong(bufLong, 0, longValues[indexLong++ & (N - 1)]);
        bh.consume(bufLong[pos - 1]);
        return pos;
    }

    @Benchmark
    public int jeaiiiFastWriterInt(Blackhole bh) {
        int len = JeaiiiFastWriter.writeIntToBytes(bufInt, 0, intValues[indexInt++ & (N - 1)]);
        bh.consume(bufInt[len - 1]);
        return len;
    }

    @Benchmark
    public int jeaiiiFastWriterLong(Blackhole bh) {
        int len = JeaiiiFastWriter.writeLongToBytes(bufLong, 0, longValues[indexLong++ & (N - 1)]);
        bh.consume(bufLong[len - 1]);
        return len;
    }

    @Benchmark
    public int jeaiiiPairsWriterInt(Blackhole bh) {
        int len = JeaiiiPairsWriter.writeIntToBytes(bufInt, 0, intValues[indexInt++ & (N - 1)]);
        bh.consume(bufInt[len - 1]);
        return len;
    }

    @Benchmark
    public int jeaiiiPairsWriterLong(Blackhole bh) {
        int len = JeaiiiPairsWriter.writeLongToBytes(bufLong, 0, longValues[indexLong++ & (N - 1)]);
        bh.consume(bufLong[len - 1]);
        return len;
    }

    // Note: classicJsonNumberWriter benchmarks removed - use Integer/Long.toString() as baseline

    @Override
    public String toString() {
        return "IntegralNumberToStringBenchmark";
    }

    @Override
    public boolean equals(Object obj) {
        return obj == this;
    }

    @Benchmark
    public int integerToStringWithFormat(Blackhole bh) {
        // Alternative: String.format("%d", value)
        int value = intValues[indexInt++ & (N - 1)];
        byte[] bytes = String.format("%d", value).getBytes(StandardCharsets.UTF_8);
        bh.consume(bytes[bytes.length - 1]);
        return bytes.length;
    }

    @Benchmark
    public int longToStringWithFormat(Blackhole bh) {
        // Alternative: String.format("%d", value)
        long value = longValues[indexLong++ & (N - 1)];
        byte[] bytes = String.format("%d", value).getBytes(StandardCharsets.UTF_8);
        bh.consume(bytes[bytes.length - 1]);
        return bytes.length;
    }

    @Setup(Level.Trial)
    public void setup() {
        intValues = new int[N];
        longValues = new long[N];
        bufInt = new byte[JsonNumberWriter.MAX_INT_BYTES];
        bufLong = new byte[JsonNumberWriter.MAX_LONG_BYTES];
        Random rnd = new Random(0x5EED_CAFEL);
        for (int i = 0; i < N; i++) {
            intValues[i] = switch (distribution) {
                case "tiny" -> rnd.nextInt(10);
                case "small" -> rnd.nextInt(100);
                case "medium" -> rnd.nextInt(1_000_000);
                case "timestamp" -> rnd.nextInt() / 1000; // 13-digit ms timestamps
                case "full" -> rnd.nextInt();
                case "negative" -> -1 - rnd.nextInt(Integer.MAX_VALUE);
                default -> rnd.nextInt();
            };
            longValues[i] = switch (distribution) {
                case "tiny" -> rnd.nextLong(100);
                case "small" -> rnd.nextLong(1_000_000_000L);
                case "medium" -> rnd.nextLong(100_000_000L);
                case "timestamp" -> rnd.nextLong(1_000_000_000_000L); // 13-digit ms timestamps
                case "full" -> rnd.nextLong();
                case "negative" -> -1 - rnd.nextLong(Long.MAX_VALUE);
                default -> rnd.nextLong();
            };
        }
        indexInt = 0;
        indexLong = 0;
    }
}
