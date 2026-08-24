package hr.hrg.dialog.core;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Decides the shape of the partial-word store for statically-known JSON field
 * prefixes. A packed key occupies one 8-byte window per word; when the key is
 * not a multiple of 8 bytes the last word only delivers {@code tailLen} bytes.
 * Three tail strategies are compared (plus a full-word baseline):
 * <ul>
 *   <li>{@code fullWord} — a full 8-byte window via {@link WriteOps#LE_LONG}
 *       (VarHandle store), the baseline for fully-written longs</li>
 *   <li>{@code tailArraycopy} — {@code System.arraycopy} of a precomputed tail byte[]</li>
 *   <li>{@code tailGeneric} — {@link WriteOps#writePackedLE} with a runtime {@code n}</li>
 *   <li>{@code tailSpecialized} — compile-time-specialized {@code writePackedLE1..7}</li>
 *   <li>{@code tailFull8AdvancePartial} — store the whole 8-byte word via VarHandle
 *       and advance the cursor by only {@code tailLen} (the overwrite trick; the
 *       buffer must reserve the full word slot, i.e. the generated {@code *_LEN_BUF})</li>
 * </ul>
 * Alignment is covered by the {@code offset} parameter: {@code 0} is 8-byte
 * aligned, {@code 7} is a misaligned odd offset.
 *
 * <p>Run: {@code java -cp <classpath> org.openjdk.jmh.Main
 * hr.hrg.dialog.core.PackedWordWriteBenchmark -wi 3 -i 5 -f 1}
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class PackedWordWriteBenchmark {

    /** Tail byte counts present in the real key set: logger/thread=1, errHash=2, errClass=3, errMessage=5, ts/msg=6. */
    @Param({"1", "2", "3", "5", "6", "7"})
    public int tailLen;

    /** 0 = aligned, 7 = misaligned odd offset (also try 1..6 for finer alignment data). */
    @Param({"0", "7"})
    public int offset;

    private byte[] buf;
    private byte[] tailBytes;
    private long w0;
    private long w1;

    @Setup(org.openjdk.jmh.annotations.Level.Trial)
    public void setup() {
        buf = new byte[1 << 13];
        w0 = 0x6e6f4c656d6f7322L;
        w1 = 0x656d614e79654b67L;
        tailBytes = new byte[8];
        for (int i = 0; i < 8; i++) {
            tailBytes[i] = (byte) (w1 >>> (i * 8));
        }
    }

    /** Baseline: a full 8-byte window via VarHandle. */
    @Benchmark
    public int fullWord(Blackhole bh) {
        int pos = offset;
        WriteOps.LE_LONG.set(buf, pos, w0);
        pos += 8;
        bh.consume(buf[pos - 1]);
        return pos;
    }

    /** Tail written by arraycopy of a precomputed byte[]. */
    @Benchmark
    public int tailArraycopy(Blackhole bh) {
        int pos = offset;
        WriteOps.LE_LONG.set(buf, pos, w0);
        pos += 8;
        System.arraycopy(tailBytes, 0, buf, pos, tailLen);
        pos += tailLen;
        bh.consume(buf[pos - 1]);
        return pos;
    }

    /** Tail written by the generic runtime-{@code n} writePackedLE. */
    @Benchmark
    public int tailGeneric(Blackhole bh) {
        int pos = offset;
        WriteOps.LE_LONG.set(buf, pos, w0);
        pos += 8;
        pos = PackedWriteTestOps.writePackedLE(buf, pos, w1, tailLen);
        bh.consume(buf[pos - 1]);
        return pos;
    }

    /** Tail written by compile-time-specialized writePackedLE1..7. */
    @Benchmark
    public int tailSpecialized(Blackhole bh) {
        int pos = offset;
        WriteOps.LE_LONG.set(buf, pos, w0);
        pos += 8;
        pos = switch (tailLen) {
            case 1 -> PackedWriteTestOps.writePackedLE1(buf, pos, w1);
            case 2 -> PackedWriteTestOps.writePackedLE2(buf, pos, w1);
            case 3 -> PackedWriteTestOps.writePackedLE3(buf, pos, w1);
            case 4 -> PackedWriteTestOps.writePackedLE4(buf, pos, w1);
            case 5 -> PackedWriteTestOps.writePackedLE5(buf, pos, w1);
            case 6 -> PackedWriteTestOps.writePackedLE6(buf, pos, w1);
            default -> PackedWriteTestOps.writePackedLE7(buf, pos, w1);
        };
        bh.consume(buf[pos - 1]);
        return pos;
    }

    /** Tail written as a full 8-byte VarHandle store, cursor advanced by tailLen only. */
    @Benchmark
    public int tailFull8AdvancePartial(Blackhole bh) {
        int pos = offset;
        WriteOps.LE_LONG.set(buf, pos, w0);
        pos += 8;
        WriteOps.LE_LONG.set(buf, pos, w1);
        pos += tailLen;
        bh.consume(buf[pos - 1]);
        return pos;
    }
}
