package hr.hrg.dialog.logback;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.slf4j.event.KeyValuePair;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.spi.ContextAwareBase;
import hr.hrg.dialog.core.JavaStackSanitizer;
import hr.hrg.dialog.core.Wyhash64;

/**
 * Reusable JSON log event writer that can be used by any appender
 * ({@link ConsoleAppenderJson}, {@link RollingFileAppenderJson}, etc.).
 * <p>
 * Holds all JSON-related configuration (includeMDC, includeKeys, prettyPrint, etc.)
 * and provides a single {@link #writeJsonEvent(ILoggingEvent, OutputStream)} method.
 * </p>
 *
 * <pre>{@code
 * JsonLogWriter writer = new JsonLogWriter();
 * writer.setIncludeMDC(true);
 * writer.setIncludeKeys(true);
 * writer.start();
 *
 * writer.writeJsonEvent(event, outputStream);
 * }</pre>
 */
public class JsonLogWriter extends ContextAwareBase {

    /** Newline bytes (UTF-8). */
    public static final byte[] NL = System.lineSeparator().getBytes(StandardCharsets.UTF_8);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonFactory JSON_FACTORY = new JsonFactory();

    // --- Configuration properties ---

    private boolean includeMDC = true;
    private boolean includeKeys = true;
    private boolean includeSource = false;
    private boolean prettyPrint = false;
    private String customFieldsJson = null;
    private ObjectNode parsedCustomFields = null;

    /** Maximum number of stack frames to include in sanitized traces. */
    private int maxStackFrames = 255;

    private volatile boolean started = false;

    public JsonLogWriter() {
    }

    // ========== Configuration setters ==========

    public void setIncludeMDC(boolean includeMDC) {
        this.includeMDC = includeMDC;
    }

    public boolean isIncludeMDC() {
        return includeMDC;
    }

    public void setIncludeKeys(boolean includeKeys) {
        this.includeKeys = includeKeys;
    }

    public boolean isIncludeKeys() {
        return includeKeys;
    }

    public void setIncludeSource(boolean includeSource) {
        this.includeSource = includeSource;
    }

    public boolean isIncludeSource() {
        return includeSource;
    }

    public void setPrettyPrint(boolean prettyPrint) {
        this.prettyPrint = prettyPrint;
    }

    public boolean isPrettyPrint() {
        return prettyPrint;
    }

    /**
     * Set custom static JSON fields to include in every event.
     * Expected format: a JSON object string like {@code {"env":"prod","version":"1.0"}}.
     * Parsed once and merged into every output line. Set to {@code null} or empty to disable.
     */
    public void setCustomFields(String customFieldsJson) {
        this.customFieldsJson = customFieldsJson;
        if (customFieldsJson != null && !customFieldsJson.isBlank()) {
            try {
                this.parsedCustomFields = (ObjectNode) MAPPER.readTree(customFieldsJson);
            } catch (Exception e) {
                addError("Failed to parse customFields JSON: " + customFieldsJson, e);
                this.parsedCustomFields = null;
            }
        } else {
            this.parsedCustomFields = null;
        }
    }

    public String getCustomFields() {
        return customFieldsJson;
    }

    public void setMaxStackFrames(int maxStackFrames) {
        this.maxStackFrames = maxStackFrames;
    }

    public int getMaxStackFrames() {
        return maxStackFrames;
    }

    // ========== Lifecycle ==========

    /**
     * Initialize this writer. Must be called before first use.
     */
    public void start() {
        started = true;
    }

    /**
     * Stop this writer and release resources.
     */
    public void stop() {
        started = false;
    }

    public boolean isStarted() {
        return started;
    }

    // ========== JSON Serialization ==========

    /**
     * Serializes a single {@link ILoggingEvent} as a JSON object to the given output stream.
     * This method can be called by any appender — console, rolling file, etc.
     *
     * @param event the logging event
     * @param out   the output stream to write to
     * @throws IOException if writing fails
     */
    public void writeJsonEvent(ILoggingEvent event, OutputStream out) throws IOException {
        JsonGenerator gen = JSON_FACTORY.createGenerator(out);
        if (prettyPrint) {
            gen.setPrettyPrinter(new DefaultPrettyPrinter());
        }

        try {
            gen.writeStartObject();

            // Timestamp (epoch millis)
            gen.writeNumberField("ts", event.getTimeStamp());

            // Level
            gen.writeStringField("level", event.getLevel().toString());

            // Logger name
            gen.writeStringField("logger", event.getLoggerName());

            // Thread name
            gen.writeStringField("thread", event.getThreadName());

            // Formatted message
            gen.writeStringField("msg", event.getFormattedMessage());

            java.util.Set<String> allKeys = new java.util.HashSet<>();

            // Structured key-value pairs and MDC context with kv priority over MDC for overlapping keys
            if (includeKeys) {
                // Collect all unique key names from both sources, prioritizing kv keys

                // Add kv keys first
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
            if (includeMDC) {
                // Add MDC keys, but only if not already in kv
                Map<String, String> mdcMap = event.getMDCPropertyMap();
                if (mdcMap != null && !mdcMap.isEmpty()) {
                    for (Map.Entry<String, String> entry : mdcMap.entrySet()) {
                        if (entry.getKey() != null) {
                            // Only add MDC key if it's not already in kv (kv takes priority)
                            if (!allKeys.contains(entry.getKey())) {
                                gen.writeStringField(entry.getKey(), entry.getValue());
                            }
                        }
                    }
                }
            }

            // Exception info
            IThrowableProxy tp = event.getThrowableProxy();
            if (tp != null) {
                gen.writeObjectFieldStart("err");
                gen.writeStringField("class", tp.getClassName());
                gen.writeStringField("msg", tp.getMessage());

                // Sanitized stack trace — deterministic frames for deduplication
                List<String> sanitizedFrames = getSanitizedFrames(tp);
                gen.writeArrayFieldStart("stack");
                for (String frame : sanitizedFrames) {
                    gen.writeString(frame);
                }
                gen.writeEndArray();

                // Hash of the sanitized trace for fast deduplication
                String fingerprint = String.join("|", sanitizedFrames);
                long hash = Wyhash64.hash(0, fingerprint.getBytes(StandardCharsets.UTF_8));
                gen.writeNumberField("hash", hash);

                // Cause chain
                IThrowableProxy cause = tp.getCause();
                if (cause != null) {
                    gen.writeObjectFieldStart("cause");
                    gen.writeStringField("class", cause.getClassName());
                    gen.writeStringField("msg", cause.getMessage());
                    gen.writeEndObject();
                }

                gen.writeEndObject();

                // Also include the raw message template for structured error analysis
                gen.writeStringField("msgTpl", event.getMessage());
            }else if (includeSource) {
                StackTraceElement[] callerData = event.getCallerData();
                if (callerData != null && callerData.length > 0) {
                    StackTraceElement caller = callerData[0];
                    gen.writeObjectFieldStart("source");
                    gen.writeStringField("class", caller.getClassName());
                    gen.writeStringField("method", caller.getMethodName());
                    gen.writeNumberField("line", caller.getLineNumber());
                    gen.writeEndObject();
                }
            }


            // Custom static fields
            if (parsedCustomFields != null) {
                var fields = parsedCustomFields.fields();
                while (fields.hasNext()) {
                    var field = fields.next();
                    gen.writeFieldName(field.getKey());
                    gen.writeTree(field.getValue());
                }
            }

            gen.writeEndObject();
        } catch (IOException e) {
            addError("Failed to write JSON log event for logger: " + event.getLoggerName(), e);
            // Fallback: write a minimal JSON with the error
            try {
                gen.writeEndObject();
            } catch (Exception ignored) {
                // If we can't close cleanly, that's okay — we tried our best
            }
        } finally {
            gen.close();
        }
    }

    protected void addKey(JsonGenerator gen, String key, Object value) throws IOException {
        if(value == null) return;

        gen.writeFieldName(key);
        if (value instanceof String) {
            gen.writeStringField(key, (String) value);
        } else {
            gen.writePOJO(value);
        }
    }

    // ========== Helper ==========

    /**
     * Extracts stack trace frames from an {@link IThrowableProxy} and sanitizes
     * them via {@link JavaStackSanitizer} for deterministic, hashable output.
     */
    private List<String> getSanitizedFrames(IThrowableProxy tp) {
        StackTraceElementProxy[] steArray = tp.getStackTraceElementProxyArray();
        if (steArray == null || steArray.length == 0) {
            return List.of();
        }
        StackTraceElement[] frames = new StackTraceElement[steArray.length];
        for (int i = 0; i < steArray.length; i++) {
            frames[i] = steArray[i].getStackTraceElement();
        }
        return JavaStackSanitizer.getSanitizedFrames(frames, maxStackFrames);
    }

    private List<String> getSanitizedFrames(IThrowableProxy tp) {
        StackTraceElementProxy[] steArray = tp.getStackTraceElementProxyArray();
        if (steArray == null || steArray.length == 0) {
            return List.of();
        }
        StackTraceElement[] frames = new StackTraceElement[steArray.length];
        for (int i = 0; i < steArray.length; i++) {
            frames[i] = steArray[i].getStackTraceElement();
        }
        return JavaStackSanitizer.getSanitizedFrames(frames, maxStackFrames);
    }

    public static void writeTraceString(JsonGenerator gen, StackTraceElement[] frames) throws IOException {
// 1. Manually open the JSON string quote
        gen.writeRaw(':');
        gen.writeRaw('"');

        // 2. Stream frames directly to Jackson's buffer
        for (StackTraceElement frame : frames) {
            if (isSpringOrJdk(frame)) {
                continue; // Your fingerprint filtering logic
            }

            // Stream components directly to avoid ANY string concatenation
            gen.writeRaw("\tat ");
            gen.writeRaw(frame.getClassName());
            gen.writeRaw('.');
            gen.writeRaw(frame.getMethodName());
            gen.writeRaw('(');
            if (frame.getFileName() != null) {
                gen.writeRaw(frame.getFileName());
                if (frame.getLineNumber() >= 0) {
                    gen.writeRaw(':');
                    gen.writeRaw(String.valueOf(frame.getLineNumber())); // Minimal allocation (cached integers)
                }
            } else {
                gen.writeRaw("Unknown Source");
            }
            gen.writeRaw("\\n"); // Escaped newline literal for JSON string compliance
        }

        // 3. Manually close the JSON string quote
        gen.writeRaw('"');
    }


}