package hr.hrg.dialog.core;

import hr.hrg.dialog.core.perf.ClassicJsonNumberWriter;
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
 * Long toString comparison: all Java idiomatic ways of writing long to textual
 * representation.
 *
 * <p>Includes:
 * - Long.toString(long) + byte[] (JDK baseline, allocates)
 * - JsonNumberWriter.writeLong (direct buffer, no allocation)
 * - JeaiiiFastWriter.writeLongToBytes (division-free, fastest)
 * - JeaiiiPairsWriter.writeLongToBytes (two-digit pair variant)
 * - String.format("%d", ...) (slower format)
 * - ClassicJsonNumberWriter.writeLong (digit-by-digit, scratch buffer)
 *
 * <p>Run:
 * {@code java -cp <classpath> org.openjdk.jmh.Main
 * hr.hrg.dialog.core.LongToStringBenchmark -wi 3 -i 5 -f 1}
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class LongToStringBenchmark {

    /** Value distribution driving the digit-count mix. */
    @Param({"tiny", "medium", "timestamp", "full", "negative"})
    public String distribution;

    private static final int N = 256;

    private long[] values;
    private byte[] buf;
    private int index;

    @Setup(Level.Trial)
    public void setup() {
        values = new long[N];
        buf = new byte[JsonNumberWriter.MAX_LONG_BYTES];
        Random rnd = new Random(0x5EED_CAFEL);
        for (int i = 0; i < N; i++) {
            values[i] = switch (distribution) {
                case "tiny" -> rnd.nextLong(100);
                case "medium" -> rnd.nextLong(1_000_000_000L);
                case "timestamp" -> rnd.nextLong(1_000_000_000_000L); // 13-digit ms timestamps
                case "negative" -> -1 - rnd.nextLong(Long.MAX_VALUE);
                default -> rnd.nextLong();
            };
        }
        index = 0;
    }

    @Benchmark
    public int longToString(Blackhole bh) throws IOException {
        long value = values[index++ & (N - 1)];
        byte[] bytes = Long.toString(value).getBytes(StandardCharsets.UTF_8);
        bh.consume(bytes[bytes.length - 1]);
        return bytes.length;
    }

    @Benchmark
    public int jsonNumberWriter(Blackhole bh) {
        int pos = JsonNumberWriter.writeLong(buf, 0, values[index++ & (N - 1)]);
        bh.consume(buf[pos - 1]);
        return pos;
    }

    @Benchmark
    public int jeaiiiFastWriter(Blackhole bh) {
        int len = JeaiiiFastWriter.writeLongToBytes(buf, 0, values[index++ & (N - 1)]);
        bh.consume(buf[len - 1]);
        return len;
    }

    @Benchmark
    public int jeaiiiPairsWriter(Blackhole bh) {
        int len = JeaiiiPairsWriter.writeLongToBytes(buf, 0, values[index++ & (N - 1)]);
        bh.consume(buf[len - 1]);
        return len;
    }

    @Benchmark
    public int stringFormat(Blackhole bh) {
        long value = values[index++ & (N - 1)];
        byte[] bytes = String.format("%d", value).getBytes(StandardCharsets.UTF_8);
        bh.consume(bytes[bytes.length - 1]);
        return bytes.length;
    }

    @Benchmark
    public int classicJsonNumberWriter(Blackhole bh) throws IOException {
        byte[] scratch = new byte[JsonNumberWriter.MAX_LONG_BYTES];
        ClassicJsonNumberWriter.writeLong(null, scratch, values[index++ & (N - 1)]);
        bh.consume(scratch[0]);
        return scratch.length;
    }

    @Override
    public String toString() {
        return "LongToStringBenchmark";
    }

    @Override
    public boolean equals(Object obj) {
        return obj == this;
    }
}
