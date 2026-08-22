package hr.hrg.dialog.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import hr.hrg.dialog.core.Wyhash64;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.slf4j.event.KeyValuePair;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Allocation-focused benchmark for {@link JsonLogWriter} and the dev variant
 * {@link JsonLogWriterDev} (missing-key reporting).
 * <p>
 * Run with the gc profiler:
 * <pre>
 * java -cp &lt;test classpath&gt; org.openjdk.jmh.Main JsonLogWriterDevBenchmark \
 *     -prof gc -wi 2 -i 3 -f 1 -t 1
 * </pre>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
@State(Scope.Thread)
public class JsonLogWriterDevBenchmark {

    private final JsonLogWriter plainWriter = new JsonLogWriter();
    private final JsonLogWriterDev devWriter = new JsonLogWriterDev();
    private final ObjectMapper mapper = new ObjectMapper();
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private final Wyhash64.Streaming hasher = new Wyhash64.Streaming(0);

    private LoggingEvent noKv;
    private LoggingEvent allPresent;
    private LoggingEvent oneMissing;
    private LoggingEvent withThrowable;

    @Setup(org.openjdk.jmh.annotations.Level.Trial)
    public void setup() {
        LoggerContext context = (LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
        Logger logger = context.getLogger("bench.dev");
        logger.setLevel(Level.INFO);

        noKv = event(context, logger, "plain message", null, null);
        allPresent = event(context, logger, "User {user} logged from {ip}",
                List.of(new KeyValuePair("user", "alice"), new KeyValuePair("ip", "10.0.0.1")), null);
        oneMissing = event(context, logger, "User {user} logged from {ip}",
                List.of(new KeyValuePair("user", "alice")), null);
        withThrowable = event(context, logger, "operation failed",
                List.of(new KeyValuePair("orderId", 42L)),
                new RuntimeException("boom"));
    }

    private static LoggingEvent event(LoggerContext context, Logger logger, String msg,
                                      List<KeyValuePair> kv, Throwable throwable) {
        LoggingEvent event = new LoggingEvent("bench.dev", logger, Level.INFO, msg, throwable, null);
        event.setTimeStamp(123456789L);
        if (kv != null) {
            applyIfPresent(event, "setKeyValuePairs", new Class<?>[]{List.class}, kv);
        }
        return event;
    }

    @Benchmark
    public void plainWriter_noKv(Blackhole blackhole) throws IOException {
        write(plainWriter, noKv, blackhole);
    }

    @Benchmark
    public void plainWriter_withKv(Blackhole blackhole) throws IOException {
        write(plainWriter, allPresent, blackhole);
    }

    @Benchmark
    public void plainWriter_exception(Blackhole blackhole) throws IOException {
        write(plainWriter, withThrowable, blackhole);
    }

    @Benchmark
    public void devWriter_allPresent(Blackhole blackhole) throws IOException {
        write(devWriter, allPresent, blackhole);
    }

    @Benchmark
    public void devWriter_oneMissing(Blackhole blackhole) throws IOException {
        write(devWriter, oneMissing, blackhole);
    }

    private void write(JsonLogWriter writer, LoggingEvent event, Blackhole blackhole) throws IOException {
        output.reset();
        JsonLogWriterStream.writeJsonEvent(writer, mapper, event, output, hasher);
        blackhole.consume(output.size());
    }

    private static void applyIfPresent(Object target, String methodName, Class<?>[] argTypes, Object arg) {
        try {
            Method method = target.getClass().getMethod(methodName, argTypes);
            method.invoke(target, arg);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
