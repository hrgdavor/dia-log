package hr.hrg.dialog.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.LoggingEvent;
import hr.hrg.dialog.core.Wyhash64;
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
import org.slf4j.event.KeyValuePair;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Head-to-head comparison of the three logback writing paths:
 *
 * <ol>
 *   <li><b>defaultPatternLog</b> — stock logback {@link PatternLayoutEncoder} with the
 *       default console pattern ({@code %d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n}),
 *       plus {@code %ex} when a throwable is attached.</li>
 *   <li><b>optimizedJsonLog</b> — the production {@link JsonLogWriter} (direct
 *       {@code OutputStream} writer, pre-encoded keys, zero-allocation fast paths).</li>
 *   <li><b>jacksonEncoderLog</b> — {@link JsonLogWriterClassic}, the Jackson
 *       {@link JsonGenerator}-based encoder (used here purely as a testing baseline for
 *       "a Jackson encoder").</li>
 * </ol>
 *
 * Each path is measured with and without a throwable via the {@code includeThrowable}
 * parameter. The same {@link LoggingEvent} (timestamp, level, logger, thread, message with
 * arguments, a small MDC map and two statement key/value pairs, optional throwable) is fed
 * to all three writers, so the comparison isolates the serialization strategy.
 *
 * <pre>
 * java --add-opens java.base/java.lang=ALL-UNNAMED -cp &lt;test classpath&gt; \
 *      org.openjdk.jmh.Main LogbackWriterComparisonBenchmark -prof gc -wi 2 -i 3 -f 1 -t 1
 * </pre>
 *
 * Results are documented in {@code doc/logback-writer-comparison-benchmark-results.md}.
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
@State(Scope.Thread)
public class LogbackWriterComparisonBenchmark {

    /** Default logback console pattern (logback's default when no pattern is configured). */
    private static final String DEFAULT_PATTERN = "%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n";

    @Param({"false", "true"})
    public boolean includeThrowable;

    // Real (started) context, as production uses — so MDC access never throws.
    private final LoggerContext context = (LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
    private final Logger logger = context.getLogger("bench.writer-comparison");

    private PatternLayoutEncoder defaultEncoder;
    private JsonLogWriter jsonWriter;
    private JsonLogWriterClassic classicWriter;
    private ObjectMapper mapper;
    private JsonFactory jsonFactory;
    private ReusableByteArrayOutputStream output;
    private LoggingEvent event;
    private Wyhash64.Streaming hasher;

    @Setup(org.openjdk.jmh.annotations.Level.Trial)
    public void setup() {
        logger.setLevel(Level.INFO);

        defaultEncoder = new PatternLayoutEncoder();
        defaultEncoder.setContext(context);
        defaultEncoder.setPattern(includeThrowable ? DEFAULT_PATTERN + "%ex" : DEFAULT_PATTERN);
        defaultEncoder.start();

        jsonWriter = new JsonLogWriter();
        classicWriter = new JsonLogWriterClassic();
        mapper = new ObjectMapper();
        jsonFactory = JsonFactory.builder().build();
        output = new ReusableByteArrayOutputStream(16 * 1024);
        hasher = new Wyhash64.Streaming(0);

        Throwable throwable = includeThrowable ? createThrowable() : null;
        event = new LoggingEvent(
            LogbackWriterComparisonBenchmark.class.getName(),
            logger,
            Level.INFO,
            "Benchmark log message with value={} and user={} and status={}",
            throwable,
            new Object[]{42, "alice", "ok"}
        );
        event.setTimeStamp(System.currentTimeMillis());
        event.setMDCPropertyMap(Map.of(
            "traceId", "a1f9d4dbf8ec4b42",
            "tenant", "acme"
        ));
        event.setKeyValuePairs(List.of(
            new KeyValuePair("requestId", "req-92"),
            new KeyValuePair("latencyMs", 12.75d)
        ));
    }

    /** Stock logback pattern encoder — the default console format (with %ex for traces). */
    @Benchmark
    public void defaultPatternLog(Blackhole blackhole) {
        byte[] bytes = defaultEncoder.encode(event);
        output.reset();
        output.write(bytes, 0, bytes.length);
        blackhole.consume(output.size());
    }

    /** Dia-Log's optimized direct-OutputStream JSON writer. */
    @Benchmark
    public void optimizedJsonLog(Blackhole blackhole) throws IOException {
        output.reset();
        JsonLogWriterStream.writeJsonEvent(jsonWriter, mapper, event, output, hasher);
        output.write('\n');
        blackhole.consume(output.size());
        blackhole.consume(output.tailChecksum());
    }

    /** Jackson {@link JsonGenerator}-based encoder (JsonLogWriterClassic) — testing baseline. */
    @Benchmark
    public void jacksonEncoderLog(Blackhole blackhole) throws IOException {
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
