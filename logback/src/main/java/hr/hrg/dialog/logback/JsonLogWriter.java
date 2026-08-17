package hr.hrg.dialog.logback;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import ch.qos.logback.classic.spi.StackTraceElementProxy;
import hr.hrg.dialog.core.EscapedJsonStringWriter;
import hr.hrg.dialog.core.JsonNumberWriter;
import hr.hrg.dialog.core.RawJsonSelfWriter;
import hr.hrg.dialog.core.StringByteExtractor;
import org.slf4j.event.KeyValuePair;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.util.RawValue;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;


/// Reusable JSON log event writer used internally.
///
/// It tries to minimize allocations
/// - if add-opens available, uses direct string data access to avoid allocating byte[] for each string
/// - holds byte[] for common keys, to again avoid allocation byte[] caused by writing string in Java
/// - delegates custom key/value and MDC to jackson
/// - writes stack trace into JSON with optimized low allocation code
///
public class JsonLogWriter {

    /** Newline bytes (UTF-8) - strictly Unix LF (\n). */
    public static final byte[] NL = new byte[]{ 0x0A };

    public record RawJsonBytes(byte[] bytes){}


    private static final StringByteExtractor.ByteWriter STRING_STRATEGY = StringByteExtractor.getStrategy();
    private static final byte[] KEY_TS = "\"ts\":".getBytes(StandardCharsets.UTF_8);
    private static final byte[] KEY_LEVEL = "\"level\":".getBytes(StandardCharsets.UTF_8);
    private static final byte[] KEY_LOGGER = "\"logger\":".getBytes(StandardCharsets.UTF_8);
    private static final byte[] KEY_THREAD = "\"thread\":".getBytes(StandardCharsets.UTF_8);
    private static final byte[] KEY_MSG = "\"msg\":".getBytes(StandardCharsets.UTF_8);
    private static final byte[] KEY_ERR_CLASS = "\"errClass\":".getBytes(StandardCharsets.UTF_8);
    private static final byte[] KEY_ERR_MESSAGE = "\"errMessage\":".getBytes(StandardCharsets.UTF_8);
    private static final byte[] KEY_ERR_HASH = "\"errHash\":".getBytes(StandardCharsets.UTF_8);
    private static final byte[] KEY_STACK = "\"stack\":".getBytes(StandardCharsets.UTF_8);
    private static final byte[] JSON_NULL = "null".getBytes(StandardCharsets.UTF_8);
    private static final byte[] JSON_TRUE = "true".getBytes(StandardCharsets.UTF_8);
    private static final byte[] JSON_FALSE = "false".getBytes(StandardCharsets.UTF_8);

     private final byte[] intNumberBuffer = JsonNumberWriter.makeIntBuffer();
     private final byte[] longNumberBuffer = JsonNumberWriter.makeLongBuffer();
    private final byte[] floatNumberBuffer = JsonNumberWriter.makeFloatBuffer();
    private final byte[] doubleNumberBuffer = JsonNumberWriter.makeDoubleBuffer();

    /** Filter applied to stack trace frame class names during fingerprinting. Defaults to accepting all frames. */
    private Predicate<String> stackTraceFilter = null;

    public JsonLogWriter() {}

    /**
     * Sets the predicate used to decide which stack trace frames are included in the {@code errHash} fingerprint.
     * <p>
     * Only the class name of each frame is passed to the predicate. Frames that fail the test are excluded
     * from both the written {@code stack} field and the fingerprint.
     *
     * @param filter frame class filter; {@code null} resets to the default accept-all predicate
     */
    public void setStackTraceFilter(Predicate<String> filter) {
        this.stackTraceFilter = filter;
    }

    public void writeJsonEvent(ObjectMapper mapper, ILoggingEvent event, OutputStream out) throws IOException {
        try {
            out.write('{');

            out.write(KEY_TS);
            JsonNumberWriter.writeLong(out, longNumberBuffer, event.getTimeStamp());

            writeFieldPrefix(out, KEY_LEVEL);
            writeJsonStringOrNull(out, event.getLevel() != null ? event.getLevel().toString() : null);

            writeFieldPrefix(out, KEY_LOGGER);
            writeJsonStringOrNull(out, event.getLoggerName());

            writeFieldPrefix(out, KEY_THREAD);
            writeJsonStringOrNull(out, event.getThreadName());

            writeFieldPrefix(out, KEY_MSG);
            writeJsonStringOrNull(out, event.getFormattedMessage());

            Set<String> allKeys = null;

            // Structured key-value pairs
            List<KeyValuePair> pairs = event.getKeyValuePairs();
            if (pairs != null && !pairs.isEmpty()) {
                allKeys = new HashSet<>();
                for (KeyValuePair kvPair : pairs) {
                    if (kvPair.key != null) {
                        allKeys.add(kvPair.key);
                        addKey(out, kvPair.key, kvPair.value, mapper);
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
                            && (allKeys == null || !allKeys.contains(entry.getKey()))) {
                        writeFieldPrefixRawKey(out, entry.getKey());
                        writeJsonStringOrNull(out, entry.getValue());
                    }
                }
            }

            // Exception info
            IThrowableProxy tp = event.getThrowableProxy();
            if (tp != null) {
                String throwableClassName = tp.getClassName();
                String throwableMessage = tp.getMessage();

                writeFieldPrefix(out, KEY_ERR_CLASS);
                writeJsonStringOrNull(out, throwableClassName);

                writeFieldPrefix(out, KEY_ERR_MESSAGE);
                writeJsonStringOrNull(out, throwableMessage);

                writeFieldPrefix(out, KEY_STACK);
                out.write('"');
                if (throwableClassName != null) {
                    STRING_STRATEGY.write(out, throwableClassName);
                }
                StackTraceElementProxy[] arrProxy = tp.getStackTraceElementProxyArray();
                // micro optimization to call variant without filter
                long fingerPrint = stackTraceFilter == null ? 
                JavaStackSanitizerLogback.addFromTraceToOutputStreamJsonAndFingerprint(
                    arrProxy,
                    stackTraceFilter,
                    out,
                    throwableClassName
                ) : 
                JavaStackWriterLogback.addFromTraceToOutputStreamJsonAndFingerprint(
                    arrProxy,
                    out,
                    throwableClassName
                );
                out.write('"');

                writeFieldPrefix(out, KEY_ERR_HASH);
                JsonNumberWriter.writeLong(out, longNumberBuffer, fingerPrint);

            }

            out.write('}');
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

    private static void writeFieldPrefix(OutputStream out, byte[] keyBytes) throws IOException {
        out.write(',');
        out.write(keyBytes);
    }

    private static void writeFieldPrefixRawKey(OutputStream out, String key) throws IOException {
        out.write(',');
        // Keys are user input — JSON-escape them (quotes, backslash, control chars).
        EscapedJsonStringWriter.writeJsonStringOrNull(out, key);
        out.write(':');
    }

    protected void addKey(OutputStream out, String key, Object value, ObjectMapper mapper) throws IOException {
        if (value == null) return;

        writeFieldPrefixRawKey(out, key);

        writeValue(out, value, mapper);
    }

    private void writeValue(OutputStream out, Object value, ObjectMapper mapper) throws IOException {
        switch (value) {
            case String s -> EscapedJsonStringWriter.writeJsonStringOrNull(out, s);
            case CharSequence cs -> EscapedJsonStringWriter.writeJsonStringOrNull(out, cs.toString());
            case Character c -> EscapedJsonStringWriter.writeJsonStringOrNull(out, c.toString());
            case Enum<?> e -> EscapedJsonStringWriter.writeJsonStringOrNull(out, e.name());
            case RawValue raw -> writeRawValue(out, raw, mapper);
            case Long l -> JsonNumberWriter.writeLong(out, longNumberBuffer, l);
            case Integer i -> JsonNumberWriter.writeInt(out, intNumberBuffer, i);
            case Short s -> JsonNumberWriter.writeInt(out, intNumberBuffer, s.intValue());
            case Byte b -> JsonNumberWriter.writeInt(out, intNumberBuffer, b.intValue());
            case Float f -> JsonNumberWriter.writeFloat(out, floatNumberBuffer, f);
            case Double d -> JsonNumberWriter.writeDouble(out, doubleNumberBuffer, d);
            case Number n -> JsonNumberWriter.writeNumber(out, intNumberBuffer, longNumberBuffer, floatNumberBuffer, doubleNumberBuffer, n);
            case Boolean b -> out.write(b ? JSON_TRUE : JSON_FALSE);
            case RawJsonSelfWriter w -> w.writeJson(out);
            case RawJsonBytes b -> out.write(b.bytes());
            default -> mapper.writeValue(out, value);
        }
    }

    private static void writeRawValue(OutputStream out, RawValue raw, ObjectMapper mapper) throws IOException {
        Object backing = raw.rawValue();
        if (backing == null) {
            out.write(JSON_NULL);
            return;
        }
        if (backing instanceof String s) {
            STRING_STRATEGY.write(out, s);
            return;
        }
        mapper.writeValue(out, raw);
    }

    private static void writeJsonStringOrNull(OutputStream out, String value) throws IOException {
        EscapedJsonStringWriter.writeJsonStringOrNull(out, value);
    }
}