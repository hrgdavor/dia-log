package hr.hrg.dialog.logback;

import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.classic.spi.ThrowableProxy;
import hr.hrg.dialog.core.JavaStackTraceWriter;
import org.openjdk.jmh.annotations.*;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.io.PrintWriter;
import java.util.concurrent.TimeUnit;

@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class StackTraceBenchmark {

    private JsonFactory jsonFactory;
    private ObjectMapper standardMapper;
    private IThrowableProxy throwableProxy;
    private Throwable rawThrowable;
    private ReusableByteArrayOutputStream outputStream;

    @Setup(Level.Trial)
    public void setup() {
        jsonFactory = new JsonFactory();
        standardMapper = new JsonMapper();

        // Create a realistic synthetic exception with nested cause
        try {
            nestedCallLevel1();
        } catch (Throwable t) {
            rawThrowable = t;
            throwableProxy = new ThrowableProxy(t);
        }

        outputStream = new ReusableByteArrayOutputStream(8192);
    }

    // -------------------------------------------------------------------------
    // Baseline 1: Standard Throwable.printStackTrace() into StringWriter
    // -------------------------------------------------------------------------
    @Benchmark
    public byte[] benchmarkStandardStringWriter() throws IOException {
        outputStream.reset();

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        rawThrowable.printStackTrace(pw);
        String stackTraceStr = sw.toString();

        try (JsonGenerator g = jsonFactory.createGenerator(outputStream)) {
            g.writeStartObject();
            g.writeStringProperty("stackTrace", stackTraceStr);
            g.writeEndObject();
            g.flush();
        }

        return outputStream.toByteArrayCopy();
    }

    // -------------------------------------------------------------------------
    // Baseline 2: Standard Jackson Object Mapping (Serializes element array)
    // -------------------------------------------------------------------------
    @Benchmark
    public byte[] benchmarkStandardJacksonObjectMapper() throws IOException {
        outputStream.reset();

        try (JsonGenerator g = jsonFactory.createGenerator(outputStream)) {
            g.writeStartObject();
            g.writeName("stackTrace");
            standardMapper.writeValue(g, rawThrowable.getStackTrace());
            g.writeEndObject();
            g.flush();
        }

        return outputStream.toByteArrayCopy();
    }

    // -------------------------------------------------------------------------
    // Optimized Target: Direct Byte Streaming (Zero Allocation + Unescaped ASCII)
    // -------------------------------------------------------------------------
    @Benchmark
    public byte[] benchmarkDirectByteStreaming() throws IOException {
        outputStream.reset();

        try (JsonGenerator g = jsonFactory.createGenerator(outputStream)) {
            g.writeStartObject();
            g.writeName("stackTrace");
            g.flush();
            outputStream.write('"');
            g.writeString(throwableProxy.getClassName());

            StackTraceElementProxy[] arrProxy = throwableProxy.getStackTraceElementProxyArray();
            StackTraceElement[] arr = new StackTraceElement[arrProxy.length];
            for (int i = 0; i < arr.length; i++) {
                arr[i] = arrProxy[i].getStackTraceElement();
            }
            JavaStackTraceWriter.addFromTraceToOutputStreamJson(arr, outputStream);

            outputStream.write('"');
            g.writeEndObject();
            g.flush();
        }

        return outputStream.toByteArrayCopy();
    }

    // Helper methods to generate a multi-frame stack trace
    private void nestedCallLevel1() {
        nestedCallLevel2();
    }

    private void nestedCallLevel2() {
        nestedCallLevel3();
    }

    private void nestedCallLevel3() {
        try {
            throw new IllegalArgumentException("Root cause error");
        } catch (Exception e) {
            throw new RuntimeException("Outer wrapper exception", e);
        }
    }

    // Resettable stream helper to avoid benchmark setup pollution
    public static class ReusableByteArrayOutputStream extends ByteArrayOutputStream {
        public ReusableByteArrayOutputStream(int size) {
            super(size);
        }

        public byte[] toByteArrayCopy() {
            byte[] copy = new byte[count];
            System.arraycopy(buf, 0, copy, 0, count);
            return copy;
        }
    }
}