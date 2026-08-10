package hr.hrg.dialog.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.classic.spi.ThrowableProxy;
import hr.hrg.dialog.core.JavaStackTraceWriter;
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
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Benchmark focused on throwable serialization and fingerprint generation.
 *
 * Three variants are compared:
 * 1) JsonLogWriter (direct byte writer)
 * 2) JsonLogWriterClassic (Jackson generator writer)
 * 3) printStackTrace into StringWriter + JSON emission
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class StacktraceFingerprintBenchmark {

    private JsonLogWriter writer;
    private JsonLogWriterClassic classicWriter;
    private ObjectMapper mapper;
    private JsonFactory jsonFactory;
    private ReusableByteArrayOutputStream output;

    private LoggingEvent event;
    private Throwable rawThrowable;
    private IThrowableProxy throwableProxy;
    private StackTraceElementProxy[] proxyFrames;

    @Setup(org.openjdk.jmh.annotations.Level.Trial)
    public void setup() {
        writer = new JsonLogWriter();
        classicWriter = new JsonLogWriterClassic();
        mapper = new ObjectMapper();
        jsonFactory = JsonFactory.builder().build();
        output = new ReusableByteArrayOutputStream(32 * 1024);

        LoggerContext context = new LoggerContext();
        Logger logger = context.getLogger("bench.stacktrace");
        logger.setLevel(Level.ERROR);

        rawThrowable = createThrowable();
        throwableProxy = new ThrowableProxy(rawThrowable);
        proxyFrames = throwableProxy.getStackTraceElementProxyArray();

        event = new LoggingEvent(
            StacktraceFingerprintBenchmark.class.getName(),
            logger,
            Level.ERROR,
            "Failed request execution",
            rawThrowable,
            null
        );
        event.setTimeStamp(System.currentTimeMillis());
    }

    @Benchmark
    public void writeWithJsonLogWriter(Blackhole blackhole) throws IOException {
        output.reset();
        writer.writeJsonEvent(mapper, event, output);
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

    @Benchmark
    public void writeWithPrintStackTraceStringWriter(Blackhole blackhole) throws IOException {
        output.reset();

        long errHash = JavaStackSanitizerLogback.fingerprint(throwableProxy, te -> true);

        StringWriter sw = new StringWriter(4096);
        try (PrintWriter pw = new PrintWriter(sw)) {
            rawThrowable.printStackTrace(pw);
        }
        String stackText = sw.toString();

        try (JsonGenerator gen = jsonFactory.createGenerator(output)) {
            gen.writeStartObject();

            gen.writeName("ts");
            gen.writeNumber(event.getTimeStamp());

            gen.writeName("level");
            gen.writeString(event.getLevel().toString());

            gen.writeName("logger");
            gen.writeString(event.getLoggerName());

            gen.writeName("thread");
            gen.writeString(event.getThreadName());

            gen.writeName("msg");
            gen.writeString(event.getFormattedMessage());

            gen.writeName("errClass");
            gen.writeString(throwableProxy.getClassName());

            gen.writeName("errMessage");
            gen.writeString(throwableProxy.getMessage());

            gen.writeName("errHash");
            gen.writeNumber(errHash);

            gen.writeName("stack");
            gen.writeString(stackText);

            gen.writeEndObject();
            gen.flush();
        }

        output.write('\n');
        blackhole.consume(output.size());
        blackhole.consume(output.tailChecksum());
    }

    @Benchmark
    public void fingerprintOnly(Blackhole blackhole) {
        long errHash = JavaStackSanitizerLogback.fingerprint(throwableProxy, te -> true);
        blackhole.consume(errHash);
    }

    @Benchmark
    public void printStackTraceToStringOnly(Blackhole blackhole) {
        StringWriter sw = new StringWriter(4096);
        try (PrintWriter pw = new PrintWriter(sw)) {
            rawThrowable.printStackTrace(pw);
        }
        blackhole.consume(sw.getBuffer().length());
    }

    @Benchmark
    public void proxyToStackTraceArrayOnly(Blackhole blackhole) {
        StackTraceElement[] arr = new StackTraceElement[proxyFrames.length];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = proxyFrames[i].getStackTraceElement();
        }
        blackhole.consume(arr.length);
        blackhole.consume(arr[0].getClassName());
        blackhole.consume(arr[arr.length - 1].getMethodName());
    }

    @Benchmark
    public void sanitizedStackWriteOnly(Blackhole blackhole) throws IOException {
        output.reset();
        StackTraceElement[] arr = new StackTraceElement[proxyFrames.length];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = proxyFrames[i].getStackTraceElement();
        }
        JavaStackTraceWriter.addFromTraceToOutputStreamJson(arr, output);
        blackhole.consume(output.size());
        blackhole.consume(output.tailChecksum());
    }

    @Benchmark
    public void eventGetThrowableProxyOnly(Blackhole blackhole) {
        IThrowableProxy proxy = event.getThrowableProxy();
        blackhole.consume(proxy);
        if (proxy != null) {
            blackhole.consume(proxy.getClassName());
            blackhole.consume(proxy.getMessage());
        }
    }

    @Benchmark
    public void eventGetFormattedMessageOnly(Blackhole blackhole) {
        blackhole.consume(event.getFormattedMessage());
    }

    @Benchmark
    public void sanitizedStackToStringOnly(Blackhole blackhole) throws IOException {
        String stackText = buildSanitizedStackString();
        blackhole.consume(stackText.length());
    }

    @Benchmark
    public void writeWithSanitizedStackStringWriterControlled(Blackhole blackhole) throws IOException {
        output.reset();

        long errHash = JavaStackSanitizerLogback.fingerprint(throwableProxy, te -> true);
        String stackText = buildSanitizedStackString();

        try (JsonGenerator gen = jsonFactory.createGenerator(output)) {
            writeSharedJsonFields(gen, errHash, stackText);
            gen.flush();
        }

        output.write('\n');
        blackhole.consume(output.size());
        blackhole.consume(output.tailChecksum());
    }

    @Benchmark
    public void writeWithPrintStackTraceStringWriterControlled(Blackhole blackhole) throws IOException {
        output.reset();

        long errHash = JavaStackSanitizerLogback.fingerprint(throwableProxy, te -> true);
        String stackText = buildPrintStackTraceString();

        try (JsonGenerator gen = jsonFactory.createGenerator(output)) {
            writeSharedJsonFields(gen, errHash, stackText);
            gen.flush();
        }

        output.write('\n');
        blackhole.consume(output.size());
        blackhole.consume(output.tailChecksum());
    }

    private String buildSanitizedStackString() throws IOException {
        StackTraceElement[] arr = new StackTraceElement[proxyFrames.length];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = proxyFrames[i].getStackTraceElement();
        }
        ByteArrayOutputStream tmp = new ByteArrayOutputStream(2048);
        JavaStackTraceWriter.addFromTraceToOutputStreamJson(arr, tmp);
        return throwableProxy.getClassName() + new String(tmp.toByteArray(), StandardCharsets.UTF_8);
    }

    private String buildPrintStackTraceString() {
        StringWriter sw = new StringWriter(4096);
        try (PrintWriter pw = new PrintWriter(sw)) {
            rawThrowable.printStackTrace(pw);
        }
        return sw.toString();
    }

    private void writeSharedJsonFields(JsonGenerator gen, long errHash, String stackText) throws IOException {
        gen.writeStartObject();

        gen.writeName("ts");
        gen.writeNumber(event.getTimeStamp());

        gen.writeName("level");
        gen.writeString(event.getLevel().toString());

        gen.writeName("logger");
        gen.writeString(event.getLoggerName());

        gen.writeName("thread");
        gen.writeString(event.getThreadName());

        gen.writeName("msg");
        gen.writeString(event.getFormattedMessage());

        gen.writeName("errClass");
        gen.writeString(throwableProxy.getClassName());

        gen.writeName("errMessage");
        gen.writeString(throwableProxy.getMessage());

        gen.writeName("errHash");
        gen.writeNumber(errHash);

        gen.writeName("stack");
        gen.writeString(stackText);

        gen.writeEndObject();
    }

    private static Throwable createThrowable() {
        try {
            layer1();
            return null;
        } catch (RuntimeException e) {
            return e;
        }
    }

    private static void layer1() {
        layer2();
    }

    private static void layer2() {
        layer3();
    }

    private static void layer3() {
        try {
            throw new IllegalArgumentException("root-cause");
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("wrapper-runtime", e);
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
