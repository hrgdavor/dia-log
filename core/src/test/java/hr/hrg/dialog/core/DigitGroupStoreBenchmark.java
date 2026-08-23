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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Isolates the store-width vs. table-size tradeoff behind a digit-quad
 * variant of the jeaiii writer, holding everything else constant:
 * <ul>
 *   <li>{@code pairs} — write 19 digits as 9 two-digit pairs + 1 byte, sourced
 *       from a 200-byte pair table (always L1-resident).</li>
 *   <li>{@code quads} — write 19 digits as 1 triple + 4 four-digit quads,
 *       sourced from a 4 KB triple table + 40 KB quad table (spills L1).</li>
 * </ul>
 * The {@code workingSet} param sweeps the group indices over either the full
 * table or a 256-entry slice, so the difference between L1-resident and
 * L2-resident table access is visible directly.
 *
 * <p>Run:
 * {@code java -cp <test-classpath> org.openjdk.jmh.Main
 * hr.hrg.dialog.core.DigitGroupStoreBenchmark -wi 3 -i 5 -f 1}
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class DigitGroupStoreBenchmark {

    /** {@code full} sweeps the whole 40 KB quad table; {@code small} touches a 1 KB slice. */
    @Param({"full", "small"})
    public String workingSet;

    private static final VarHandle LE_SHORT =
            MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle LE_INT =
            MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);

    private static final short[] PAIRS = new short[100];     // 200 B
    private static final int[] TRIPLES = new int[1000];      // 4 KB
    private static final int[] QUADS = new int[10000];       // 40 KB

    static {
        for (int i = 0; i < 100; i++) {
            PAIRS[i] = (short) (('0' + i / 10) | (('0' + i % 10) << 8));
        }
        for (int i = 0; i < 1000; i++) {
            TRIPLES[i] = ('0' + i / 100) | (('0' + (i / 10) % 10) << 8) | (('0' + i % 10) << 16);
        }
        for (int i = 0; i < 10000; i++) {
            int h = i / 100;
            int l = i % 100;
            QUADS[i] = ('0' + h / 10) | (('0' + h % 10) << 8) | (('0' + l / 10) << 16) | (('0' + l % 10) << 24);
        }
    }

    private static final int N = 65536;
    private int[] pairSeq;    // indices into PAIRS, [0, 100)
    private int[] tripleSeq;  // indices into TRIPLES, [0, 1000)
    private int[] quadSeq;    // indices into QUADS, [0, span)
    private byte[] buf = new byte[32];
    private int index;

    @Setup(Level.Trial)
    public void setup() {
        pairSeq = new int[N];
        tripleSeq = new int[N];
        quadSeq = new int[N];
        Random rnd = new Random(0xC0FFEEL);
        int span = workingSet.equals("small") ? 256 : 10000;
        for (int i = 0; i < N; i++) {
            pairSeq[i] = rnd.nextInt(100);
            tripleSeq[i] = rnd.nextInt(1000);
            quadSeq[i] = rnd.nextInt(span);
        }
    }

    @Benchmark
    public int pairs(Blackhole bh) {
        int pos = 0;
        int base = index;
        int[] s = pairSeq;
        for (int i = 0; i < 9; i++) {
            LE_SHORT.set(buf, pos, PAIRS[s[(base + i) & (N - 1)]]);
            pos += 2;
        }
        buf[pos++] = '7';
        bh.consume(buf[pos - 1]);
        index = (base + 9) & (N - 1);
        return pos;
    }

    @Benchmark
    public int quads(Blackhole bh) {
        int pos = 0;
        int base = index;
        LE_INT.set(buf, pos, TRIPLES[tripleSeq[base & (N - 1)]]);
        pos += 3;
        int[] s = quadSeq;
        for (int i = 0; i < 4; i++) {
            LE_INT.set(buf, pos, QUADS[s[(base + 1 + i) & (N - 1)]]);
            pos += 4;
        }
        bh.consume(buf[pos - 1]);
        index = (base + 5) & (N - 1);
        return pos;
    }
}
