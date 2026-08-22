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
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * T7 — cursor-locality writer benchmark.
 *
 * <p>Compares three ways of serializing a mixed primitive/string workload into a
 * {@code byte[]}:
 * <ul>
 *   <li>{@code pure} — the composable {@link WriteOps} facade on a plain
 *       {@code byte[] buf, int pos} (the generalized cursor-locality shape).</li>
 *   <li>{@code cursor} — {@link WriteOps} over a grow-capable {@link ReusableByteArrayOutputStream}
 *       (what {@code JsonLogWriter}'s direct path now uses).</li>
 *   <li>{@code stream} — the baseline {@link ByteArrayOutputStream} with
 *       stream-mediated {@link JsonNumberWriter}/{@link EscapedJsonStringWriter}
 *       calls (per-value virtual dispatch, no cursor locality).</li>
 * </ul>
 *
 * <p>Expected: the cursor paths are materially faster than the stream-mediated
 * baseline for mixed workloads, with 0 B/op allocation on the hot path (numbers
 * are written straight into the cursor, no scratch buffers).
 *
 * <p>Suggested run:
 * {@code java -cp <test-classpath> org.openjdk.jmh.Main hr.hrg.dialog.core.CursorBufferWriterBenchmark.* -wi 3 -i 5 -f 1}
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class CursorBufferWriterBenchmark {

    private static final String[] SAMPLES = {
        "com.example.service.UserService",
        "handleRequest(HttpServletRequest req)",
        "org.apache.kafka.clients.producer.KafkaProducer",
        "a simple log message with some words in it",
        "level=INFO user=alice status=ok duration=42ms",
        "quote \" and backslash \\ inside",
        "héllo wörld — naïve café",
        "tab\there and newline\nhere",
        "short",
        "x",
        ""
    };

    private static final int N = 64;

    private byte[] buf;
    private ReusableByteArrayOutputStream rbo;
    private ByteArrayOutputStream baos;
    private int[] ints;
    private long[] longs;
    private String[] strings;

    @Setup(Level.Trial)
    public void setup() {
        buf = new byte[1 << 16];
        rbo = new ReusableByteArrayOutputStream(1 << 16);
        baos = new ByteArrayOutputStream(1 << 16);

        ints = new int[N];
        longs = new long[N];
        strings = new String[N];
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < N; i++) {
            ints[i] = rnd.nextInt();
            longs[i] = rnd.nextLong();
            strings[i] = SAMPLES[i % SAMPLES.length];
        }
    }

    @Benchmark
    public void pureWriteOps(Blackhole bh) {
        int pos = 0;
        for (int i = 0; i < N; i++) {
            pos = JsonNumberWriter.writeInt(buf, pos, ints[i]);
            pos = JsonNumberWriter.writeLong(buf, pos, longs[i]);
            pos = WriteOps.writeEscapedJsonString(buf, pos, strings[i]);
        }
        bh.consume(pos);
    }

    @Benchmark
    public void cursorWriteOps(Blackhole bh) {
        rbo.reset();
        for (int i = 0; i < N; i++) {
            rbo.ensure(JsonNumberWriter.MAX_INT_BYTES);
            rbo.pos = JsonNumberWriter.writeInt(rbo.buf, rbo.pos, ints[i]);
            rbo.ensure(JsonNumberWriter.MAX_LONG_BYTES);
            rbo.pos = JsonNumberWriter.writeLong(rbo.buf, rbo.pos, longs[i]);
            WriteOps.writeEscapedJsonString(rbo, strings[i]);
        }
        bh.consume(rbo.position());
    }

    @Benchmark
    public void streamDataOutput(Blackhole bh) throws IOException {
        baos.reset();
        for (int i = 0; i < N; i++) {
            JsonNumberWriter.writeInt(baos, ints[i]);
            JsonNumberWriter.writeLong(baos, longs[i]);
            EscapedJsonStringWriter.writeJsonStringOrNull(baos, strings[i]);
        }
        bh.consume(baos.size());
    }
}
