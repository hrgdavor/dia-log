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

import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Int writer comparison: the two jeaiii-style division-free variants
 * ({@code jeaiiiPairs} / {@code jeaiiiQuad}), the current production
 * {@link JsonNumberWriter#writeInt(byte[], int, int)}, and the plain JDK
 * {@link Integer#toString(int)} round-trip most commonly found in the wild.
 * All custom writers emit straight into a caller-supplied {@code byte[]} with
 * no allocation; {@code standardToString} allocates a {@code String} + bytes.
 *
 * <p>Run:
 * {@code java -cp <test-classpath> org.openjdk.jmh.Main
 * hr.hrg.dialog.core.IntWriteBenchmark -wi 3 -i 5 -f 1}
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class IntWriteBenchmark {

    /** Value distribution driving the digit-count mix. */
    @Param({"tiny", "small", "medium", "full", "negative"})
    public String distribution;

    private static final int N = 256;

    private int[] values;
    private byte[] buf;
    private int index;

    @Setup(Level.Trial)
    public void setup() {
        values = new int[N];
        buf = new byte[JsonNumberWriter.MAX_INT_BYTES];
        Random rnd = new Random(0x5EED_CAFEL);
        for (int i = 0; i < N; i++) {
            values[i] = switch (distribution) {
                case "tiny" -> rnd.nextInt(10);
                case "small" -> rnd.nextInt(100);
                case "medium" -> rnd.nextInt(1_000_000);
                case "negative" -> -1 - rnd.nextInt(Integer.MAX_VALUE);
                default -> rnd.nextInt();
            };
        }
    }

    /** Current production path: Fory digit tables with hardware division. */
    @Benchmark
    public int jsonNumberWriter(Blackhole bh) {
        int pos = JsonNumberWriter.writeInt(buf, 0, values[index++ & (N - 1)]);
        bh.consume(buf[pos - 1]);
        return pos;
    }

    /** Jeaiii pair variant (200-byte table, one short store per pair). */
    @Benchmark
    public int jeaiiiPairs(Blackhole bh) {
        int len = JeaiiiPairsWriter.writeIntToBytes(buf, 0, values[index++ & (N - 1)]);
        bh.consume(buf[len - 1]);
        return len;
    }

    /** Jeaiii quad variant (40 KB table, one int store per 4-digit group). */
    @Benchmark
    public int jeaiiiQuad(Blackhole bh) {
        int len = JeaiiiFastWriter.writeIntToBytes(buf, 0, values[index++ & (N - 1)]);
        bh.consume(buf[len - 1]);
        return len;
    }

    /** Plain JDK baseline (allocates a String + byte[] each call). */
    @Benchmark
    public int standardToString(Blackhole bh) {
        byte[] b = Integer.toString(values[index++ & (N - 1)]).getBytes(StandardCharsets.UTF_8);
        bh.consume(b[b.length - 1]);
        return b.length;
    }
}
