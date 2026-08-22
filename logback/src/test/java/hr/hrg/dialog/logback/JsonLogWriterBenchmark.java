package hr.hrg.dialog.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
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
import hr.hrg.dialog.core.Wyhash64;
import org.openjdk.jmh.infra.Blackhole;
import org.slf4j.event.KeyValuePair;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Compare direct byte writer vs classic Jackson generator writer for complete log-line serialization.
 *
 * Suggested run for allocation/GC metrics:
 * java -cp <classpath> org.openjdk.jmh.Main hr.hrg.dialog.logback.JsonLogWriterBenchmark.* -prof gc -wi 3 -i 5 -f 1
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class JsonLogWriterBenchmark {

    @Param({"false", "true"})
    public boolean includeThrowable;

    private JsonLogWriter writer;
    private JsonLogWriterClassic classicWriter;
    private ObjectMapper mapper;
    private JsonFactory jsonFactory;
    private ReusableByteArrayOutputStream output;
    private LoggingEvent event;
    private Wyhash64.Streaming hasher;

    @Setup(org.openjdk.jmh.annotations.Level.Trial)
    public void setup() {
        writer = new JsonLogWriter();
        classicWriter = new JsonLogWriterClassic();
        mapper = new ObjectMapper();
        jsonFactory = JsonFactory.builder().build();
        output = new ReusableByteArrayOutputStream(16 * 1024);
        hasher = new Wyhash64.Streaming(0);

        LoggerContext context = new LoggerContext();
        Logger logger = context.getLogger("bench.logger");
        logger.setLevel(Level.INFO);

        Throwable throwable = includeThrowable ? createThrowable() : null;
        event = new LoggingEvent(
            JsonLogWriterBenchmark.class.getName(),
            logger,
            Level.INFO,
            "Benchmark log message with value={} and user={} and status={}",
            throwable,
            new Object[]{42, "alice", "ok"}
        );
        event.setTimeStamp(System.currentTimeMillis());

        applyIfPresent(event, "setThreadName", new Class<?>[]{String.class}, "bench-thread-1");
        applyIfPresent(event, "setMDCPropertyMap", new Class<?>[]{Map.class}, Map.of(
            "traceId", "a1f9d4dbf8ec4b42",
            "spanId", "6ca7f1d10c8b4ab1",
            "tenant", "acme"
        ));
        applyIfPresent(event, "setKeyValuePairs", new Class<?>[]{List.class}, List.of(
            new KeyValuePair("requestId", "req-92"),
            new KeyValuePair("attempt", 3),
            new KeyValuePair("cacheHit", true),
            new KeyValuePair("latencyMs", 12.75d)
        ));

        if (throwable != null) {
            applyIfPresent(event, "setThrowableProxy", new Class<?>[]{ch.qos.logback.classic.spi.ThrowableProxy.class}, new ThrowableProxy(throwable));
        }
    }

    @Benchmark
    public void writeWithJsonLogWriter(Blackhole blackhole) throws IOException {
        output.reset();
        JsonLogWriterStream.writeJsonEvent(writer, mapper, event, output, hasher);
        output.write('\n');
        blackhole.consume(output.size());
        blackhole.consume(output.tailChecksum());
    }

    @Benchmark
    public void writeWithJsonLogWriterClassic(Blackhole blackhole) throws IOException {
        output.reset();
        try (JsonGenerator gen = jsonFactory.createGenerator(output)) {
            classicWriter.writeJsonEvent(gen, event, output);
            gen.flush();
        }
        output.write('\n');
        blackhole.consume(output.size());
        blackhole.consume(output.tailChecksum());
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
        try {
            throw new IllegalStateException("root-cause");
        } catch (IllegalStateException e) {
            throw new RuntimeException("wrapping-exception", e);
        }
    }

    private static void applyIfPresent(Object target, String methodName, Class<?>[] argTypes, Object arg) {
        try {
            Method method = target.getClass().getMethod(methodName, argTypes);
            method.invoke(target, arg);
        } catch (Exception ignored) {
            // Keep benchmark compatible with multiple Logback versions where some setters are absent.
        }
    }

    public static class ReusableByteArrayOutputStream extends ByteArrayOutputStream {
        public ReusableByteArrayOutputStream(int size) {
            super(size);
        }

        public int tailChecksum() {
            if (count == 0) {
                return 0;
            }
            int first = buf[0] & 0xFF;
            int last = buf[count - 1] & 0xFF;
            return (count * 31) ^ (first << 8) ^ last;
        }
    }
}
