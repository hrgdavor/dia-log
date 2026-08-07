package hr.hrg.dialog.logback;

import java.io.IOException;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.event.KeyValuePair;

import tools.jackson.core.JsonGenerator;
import tools.jackson.core.ObjectWriteContext;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.ObjectMapper;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.spi.ContextAwareBase;

/**
 * Reusable JSON log event writer used internally by {@link CustomJsonEncoder}.
 */
public class JsonLogWriter extends ContextAwareBase {

    /** Newline bytes (UTF-8) - strictly Unix LF (\n). */
    public static final byte[] NL = new byte[]{ 0x0A };

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonFactory JSON_FACTORY = JsonFactory.builder().build();

    // --- Configuration properties ---
    private boolean includeMDC = true;
    private boolean includeKeys = true;
    private boolean includeSource = false;
    private boolean prettyPrint = false;
    private String customFields = null;

    private int maxStackFrames = 255;
    private volatile boolean started = false;
    private final ObjectWriteContext writeCtxt = ObjectWriteContext.empty();

    public JsonLogWriter() {}

    // ========== Configuration setters ==========

    public void setIncludeMDC(boolean includeMDC) { this.includeMDC = includeMDC; }
    public boolean isIncludeMDC() { return includeMDC; }

    public void setIncludeKeys(boolean includeKeys) { this.includeKeys = includeKeys; }
    public boolean isIncludeKeys() { return includeKeys; }

    public void setIncludeSource(boolean includeSource) { this.includeSource = includeSource; }
    public boolean isIncludeSource() { return includeSource; }

    public void setPrettyPrint(boolean prettyPrint) { this.prettyPrint = prettyPrint; }
    public boolean isPrettyPrint() { return prettyPrint; }

    public void setCustomFields(String customFields) { this.customFields = customFields; }
    public String getCustomFields() { return customFields; }

    public void setMaxStackFrames(int maxStackFrames) { this.maxStackFrames = maxStackFrames; }
    public int getMaxStackFrames() { return maxStackFrames; }

    // ========== Lifecycle ==========

    public void start() { started = true; }
    public void stop() { started = false; }
    public boolean isStarted() { return started; }

    // ========== JSON Serialization ==========

    public void writeJsonEvent(ILoggingEvent event, OutputStream out) throws IOException {
        try (JsonGenerator gen = JSON_FACTORY.createGenerator(writeCtxt, out)) {
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
            if (includeKeys) {
                List<KeyValuePair> pairs = event.getKeyValuePairs();
                if (pairs != null && !pairs.isEmpty()) {
                    for (KeyValuePair kvPair : pairs) {
                        if (kvPair.key != null) {
                            allKeys.add(kvPair.key);
                            addKey(gen, kvPair.key, kvPair.value);
                        }
                    }
                }
            }

            // MDC context
            if (includeMDC) {
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
            }

            // Exception info
            IThrowableProxy tp = event.getThrowableProxy();
            if (tp != null) {
                gen.writeName("err");
                gen.writeStartObject();

                gen.writeName("class");
                gen.writeString(tp.getClassName());

                gen.writeName("msg");
                gen.writeString(tp.getMessage());

                gen.writeName("stack");
                gen.writeString("");

                IThrowableProxy cause = tp.getCause();
                if (cause != null) {
                    gen.writeName("cause");
                    gen.writeStartObject();

                    gen.writeName("class");
                    gen.writeString(cause.getClassName());

                    gen.writeName("msg");
                    gen.writeString(cause.getMessage());

                    gen.writeEndObject();
                }

                gen.writeEndObject();

                gen.writeName("msgTpl");
                gen.writeString(event.getMessage());
            } else if (includeSource) {
                StackTraceElement[] callerData = event.getCallerData();
                if (callerData != null && callerData.length > 0) {
                    StackTraceElement caller = callerData[0];
                    gen.writeName("source");
                    gen.writeStartObject();

                    gen.writeName("class");
                    gen.writeString(caller.getClassName());

                    gen.writeName("method");
                    gen.writeString(caller.getMethodName());

                    gen.writeName("line");
                    gen.writeNumber(caller.getLineNumber());

                    gen.writeEndObject();
                }
            }

            gen.writeEndObject();
        } catch (IOException e) {
            addError("Failed to write JSON log event for logger: " + event.getLoggerName(), e);
            throw e;
        }
    }

    private boolean isReserved(String key) {
        return switch (key) {
            case "ts", "level", "logger", "thread", "msg", "err", "source", "msgTpl", "hash" -> true;
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