package hr.hrg.dialog.logback;

import javax.annotation.concurrent.NotThreadSafe;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ch.qos.logback.classic.spi.StackTraceElementProxy;
import hr.hrg.dialog.core.JavaStackTraceWriter;
import hr.hrg.dialog.core.StringByteExtractor;
import org.slf4j.event.KeyValuePair;

import tools.jackson.core.JsonGenerator;
import tools.jackson.core.ObjectWriteContext;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.ObjectMapper;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;


/**
 * Reusable JSON log event writer that delegates to a Jackson {@link JsonGenerator}.
 * <p>
 * This is the classic/baseline writer (used by benchmarks and as a reference
 * implementation). The optimized streaming writer is {@link JsonLogWriter}, which
 * writes straight to an {@link OutputStream} and reuses byte[] buffers for common
 * keys and low-allocation number/string serialization.
 * <p>
 * Thread-safety: instances hold reusable state and are not safe for concurrent use;
 * share one writer per appender/thread as {@link JsonAppender} does.
 */
@NotThreadSafe
public class JsonLogWriterClassic {

    /** Newline bytes (UTF-8) - strictly Unix LF (\n). */
    public static final byte[] NL = new byte[]{ 0x0A };


    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonFactory JSON_FACTORY = JsonFactory.builder().build();
    private static final StringByteExtractor.ByteWriter STRING_STRATEGY = StringByteExtractor.getStrategy();

 //   private int maxStackFrames = 255;
    private volatile boolean started = false;
    private final ObjectWriteContext writeCtxt = ObjectWriteContext.empty();

    public JsonLogWriterClassic() {}

    public void writeJsonEvent(JsonGenerator gen, ILoggingEvent event, OutputStream out) throws IOException {
        try {
            gen.writeStartObject();

            gen.writeName("ts"); // Timestamp
            gen.writeNumber(event.getTimeStamp());

            gen.writeName("level");
            gen.writeString(event.getLevel().toString());

            gen.writeName("logger");// Logger name
            gen.writeString(event.getLoggerName());

            gen.writeName("thread");
            gen.writeString(event.getThreadName());

            gen.writeName("msg");
            gen.writeString(event.getFormattedMessage());

            Set<String> allKeys = new HashSet<>();

            // Structured key-value pairs
            List<KeyValuePair> pairs = event.getKeyValuePairs();
            if (pairs != null && !pairs.isEmpty()) {
                for (KeyValuePair kvPair : pairs) {
                    if (kvPair.key != null) {
                        allKeys.add(kvPair.key);
                        addKey(gen, kvPair.key, kvPair.value);
                    }
                }
            }

            // MDC context
            Map<String, String> mdcMap = null;
            try {
                mdcMap = event.getMDCPropertyMap();
            } catch (Exception ignored) {}

            if (mdcMap != null && !mdcMap.isEmpty()) {
                for (Map.Entry<String, String> entry : mdcMap.entrySet()) {
                    if (entry.getKey() != null
                            && !isReserved(entry.getKey())
                            && !allKeys.contains(entry.getKey())) {
                        gen.writeName(entry.getKey());
                        gen.writeString(entry.getValue());
                    }
                }
            }

            // Exception info
            IThrowableProxy tp = event.getThrowableProxy();
            if (tp != null) {
                gen.writeName("errClass");
                gen.writeString(tp.getClassName());

                gen.writeName("errMessage");
                gen.writeString(tp.getMessage());

                gen.writeName("errHash");
                gen.writeNumber(JavaStackSanitizerLogback.fingerprint(tp, te->true));

                gen.writeName("stack");
                gen.flush();// flush whatever is in buffer so we can safely dump stack trace into OutputStream
                out.write('"');
                STRING_STRATEGY.write(out, tp.getClassName());
                StackTraceElementProxy[] arrProxy = tp.getStackTraceElementProxyArray();
                StackTraceElement[] arr = new StackTraceElement[arrProxy.length];
                for(int i=0;i<arr.length;i++){
                    arr[i] = arrProxy[i].getStackTraceElement();
                }
                JavaStackTraceWriter.addFromTraceToOutputStreamJson(arr, out);

                out.write('"');
            }

            gen.writeEndObject();
        } catch (IOException e) {
            System.err.println(Instant.now() + " Failed to write JSON log event for logger: " + event.getLoggerName());
            e.printStackTrace(System.err);
            throw e;
        }
    }

    private boolean isReserved(String key) {
        return switch (key) {
            case "ts", "level", "logger", "thread", "msg", "errClass", "errHash", "errMessage" -> true;
            default -> false;
        };
    }

    protected void addKey(JsonGenerator gen, String key, Object value) throws IOException {
        if (value == null) return;

        gen.writeName(key);

        switch (value) {
            case String s -> gen.writeString(s);
            case Long l -> gen.writeNumber(l);
            case Integer i -> gen.writeNumber(i);
            case Double d -> gen.writeNumber(d);
            case Number n -> gen.writeNumber(n.toString());
            case Boolean b -> gen.writeBoolean(b);
            default -> MAPPER.writeValue(gen, value);
        }
    }
}