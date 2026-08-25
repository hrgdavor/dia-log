package hr.hrg.dialog.logback;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import hr.hrg.dialog.core.ReusableByteArrayOutputStream;
import hr.hrg.dialog.core.Wyhash64;
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
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Event-level before/after benchmark for the performance techniques ported
 * from Apache Fory commit 585eb16f ("feat(java): optimize json perf",
 * PR #3871).
 *
 * <p>All three legs produce byte-identical JSON for the same event:
 *
 * <ul>
 *   <li>{@code eventClassicRbo} — the <b>before</b>: {@link ClassicJsonLogEventWriter}
 *       (byte[] field prefixes, per-char escape scan, digit-by-digit numbers,
 *       all through the {@code OutputStream} interface) into the reusable buffer;</li>
 *   <li>{@code eventNewStream} — the <b>new internals</b> (SWAR scan, packed
 *       digit tables) but the old <b>stream mechanism</b>
 *       (plain {@link ByteArrayOutputStream}, so the direct-buffer paths stay
 *       dormant);</li>
 *   <li>{@code eventNewDirect} — the <b>after</b>: the production
 *       {@link JsonLogWriter} into a {@link ReusableByteArrayOutputStream},
 *       activating the T4 direct-buffer + T6 packed-prefix paths.</li>
 * </ul>
 *
 * <p>Suggested run:
 * {@code java -cp <test-classpath> --add-opens java.base/java.lang=ALL-UNNAMED
 * org.openjdk.jmh.Main hr.hrg.dialog.logback.ForyPerfEventBenchmark.* -wi 3 -i 5 -f 1 -prof gc}
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class ForyPerfEventBenchmark {

    private JsonLogWriter writer;
    private ClassicJsonLogEventWriter classicWriter;
    private ObjectMapper mapper;
    private ReusableByteArrayOutputStream rbo;
    private ByteArrayOutputStream baos;
    private LoggingEvent event;
    private Wyhash64.Streaming hasher;

    @Setup(Level.Trial)
    public void setup() {
        writer = new JsonLogWriter();
        classicWriter = new ClassicJsonLogEventWriter();
        mapper = new ObjectMapper();
        rbo = new ReusableByteArrayOutputStream(16 * 1024);
        baos = new ByteArrayOutputStream(16 * 1024);
        hasher = new Wyhash64.Streaming(0);

        LoggerContext context = new LoggerContext();
        // A fresh LoggerContext has no MDC adapter; initialize one so
        // event.getMDCPropertyMap() works and caches like in production
        // (otherwise it throws per event and the writer's try/catch swallows it).
        context.setMDCAdapter(new ch.qos.logback.classic.util.LogbackMDCAdapter());
        Logger logger = context.getLogger("bench.app.service");
        logger.setLevel(ch.qos.logback.classic.Level.INFO);

        event = new LoggingEvent(
                "bench.app.service",
                logger,
                ch.qos.logback.classic.Level.INFO,
                "Benchmark log message with value={} and user={} and status={}",
                null,
                new Object[]{42, "alice", "ok"});
        event.setTimeStamp(1_750_000_000_000L);
        applyIfPresent(event, "setThreadName", new Class<?>[]{String.class}, "bench-thread-1");

        // Warm the formatted-message cache once, outside the measured loop.
        event.getFormattedMessage();

        // Sanity: the classic fixture must emit exactly what the production writer emits.
        // 64 KiB buffers — the no-grow LIMIT_MARGIN guard (1024 B) rejects long
        // strings once the free space drops below 1024 bytes, so a sanity buffer
        // must be well above that (the measured legs use 16 KiB).
        try {
            ReusableByteArrayOutputStream a = new ReusableByteArrayOutputStream(64 * 1024);
            classicWriter.writeEvent(event, a);
            ReusableByteArrayOutputStream b = new ReusableByteArrayOutputStream(64 * 1024);
            int bPos = writer.writeJsonEventDirect(mapper, event, b);
            assertArrayEquals(
                    java.util.Arrays.copyOf(a.buffer(), a.size()),
                    java.util.Arrays.copyOf(b.buffer(), bPos),
                    "classic fixture output must equal production output");
        } catch (IOException e) {
            throw new IllegalStateException("setup sanity check failed", e);
        }
    }

    @Benchmark
    public void eventClassicRbo(Blackhole bh) throws IOException {
        rbo.reset();
        classicWriter.writeEvent(event, rbo);
        bh.consume(rbo);
    }

    @Benchmark
    public void eventNewStream(Blackhole bh) throws IOException {
        baos.reset();
        JsonLogWriterStream.writeJsonEvent(writer, mapper, event, baos, hasher);
        bh.consume(baos);
    }

    @Benchmark
    public void eventNewDirect(Blackhole bh) throws IOException {
        rbo.reset();
        writer.writeJsonEventDirect(mapper, event, rbo);
        bh.consume(rbo);
    }

    // ==========================================
    // Isolation legs: cost of the logback event-API accessors called by
    // JsonLogWriter but not by the raw byte-emission work itself.
    // ==========================================

    @Benchmark
    public void mdcPropertyMapAccess(Blackhole bh) {
        bh.consume(event.getMDCPropertyMap());
    }

    @Benchmark
    public void keyValuePairsAccess(Blackhole bh) {
        bh.consume(event.getKeyValuePairs());
    }

    @Benchmark
    public void throwableProxyAccess(Blackhole bh) {
        bh.consume(event.getThrowableProxy());
    }

    @Benchmark
    public void formattedMessageAccess(Blackhole bh) {
        bh.consume(event.getFormattedMessage());
    }

    private static void applyIfPresent(Object target, String methodName, Class<?>[] argTypes, Object arg) {
        try {
            Method method = target.getClass().getMethod(methodName, argTypes);
            method.invoke(target, arg);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("setter " + methodName + " not available on LoggingEvent", e);
        }
    }
}
