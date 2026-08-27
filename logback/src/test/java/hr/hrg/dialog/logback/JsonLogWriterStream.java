package hr.hrg.dialog.logback;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import hr.hrg.dialog.core.*;
import org.slf4j.event.KeyValuePair;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.util.RawValue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/// Test-only, byte-identical reimplementation of the naive stream-fallback event
/// writer that previously lived on {@link JsonLogWriter}. Production uses the
/// optimized {@link JsonLogWriter#writeJsonEventDirect direct-buffer path}; this
/// class exists only so benchmarks and unit tests can exercise the fallback and
/// confirm it produces the same bytes. Not part of the published API.
///
/// <p>Value-type dispatch ({@code String}, {@code Long}, {@code RawValue}, ...) is
/// handled by this class's own {@link #addKey} switch, mirroring the direct path
/// exactly. The {@link JsonLogWriterDev dev variant}'s {@code missingKeys} field
/// cannot be delegated anymore (the writer's extension point now writes straight
/// into the no-grow buffer), so the stream path replicates it via
/// {@link JsonLogWriterDev#findMissingKeys} and the stream escaping writers.
///
/// <p>The hasher is caller-owned and passed in (single-pass methods reset it
/// internally) — exactly the reusable-state discipline the production writer uses.
public final class JsonLogWriterStream {

    private JsonLogWriterStream() {}

    private static final String KEY_TS = "{\"ts\":";
    private static final String KEY_LEVEL = "\"level\":";
    private static final String KEY_LOGGER = "\"logger\":";
    private static final String KEY_THREAD = "\"thread\":";
    private static final String KEY_MSG = "\"msg\":";
    private static final String KEY_ERR_CLASS = "\"errClass\":";
    private static final String KEY_ERR_MESSAGE = "\"errMessage\":";
    private static final String KEY_STACK = "\"stack\":";
    private static final String KEY_ERR_HASH = "\"errHash\":";

    public static void writeJsonEvent(JsonLogWriter writer, ObjectMapper mapper, ILoggingEvent event,
            OutputStream out, Wyhash64.Streaming hasher) throws IOException {
        out.write(KEY_TS.getBytes(StandardCharsets.UTF_8));
        JsonNumberWriter.writeLong(out, event.getTimeStamp());

        writeFieldPrefix(out, KEY_LEVEL);
        EscapedJsonStringWriter.writeJsonStringOrNull(out, event.getLevel() != null ? event.getLevel().toString() : null);

        writeFieldPrefix(out, KEY_LOGGER);
        EscapedJsonStringWriter.writeJsonStringOrNull(out, event.getLoggerName());

        writeFieldPrefix(out, KEY_THREAD);
        EscapedJsonStringWriter.writeJsonStringOrNull(out, event.getThreadName());

        writeFieldPrefix(out, KEY_MSG);
        EscapedJsonStringWriter.writeJsonStringOrNull(out, event.getFormattedMessage());

        Map<String, String> mdcMap = null;
        try {
            mdcMap = event.getMDCPropertyMap();
        } catch (Exception ignored) {
        }

        List<KeyValuePair> pairs = event.getKeyValuePairs();
        if (pairs != null && !pairs.isEmpty()) {
            for (KeyValuePair kvPair : pairs) {
                if (kvPair.key != null) {
                    addKey(out, kvPair.key, kvPair.value, mapper);
                }
            }
        }

        if (mdcMap != null && !mdcMap.isEmpty()) {
            for (Map.Entry<String, String> entry : mdcMap.entrySet()) {
                if (entry.getKey() != null && !isReserved(entry.getKey())) {
                    writeFieldPrefixRawKey(out, entry.getKey());
                    EscapedJsonStringWriter.writeJsonStringOrNull(out, entry.getValue());
                }
            }
        }

        IThrowableProxy tp = event.getThrowableProxy();
        if (tp != null) {
            String throwableClassName = tp.getClassName();
            String throwableMessage = tp.getMessage();

            writeFieldPrefix(out, KEY_ERR_CLASS);
            EscapedJsonStringWriter.writeJsonStringOrNull(out, throwableClassName);

            writeFieldPrefix(out, KEY_ERR_MESSAGE);
            EscapedJsonStringWriter.writeJsonStringOrNull(out, throwableMessage);

            writeFieldPrefix(out, KEY_STACK);
            out.write('"');
            if (throwableClassName != null) {
                StringByteExtractor.getStrategy().write(out, throwableClassName);
            }
            StackTraceElementProxy[] arrProxy = tp.getStackTraceElementProxyArray();
            java.util.function.Predicate<String> filter = writer.getStackTraceFilter();
            long fingerPrint = filter == null
                    ? JavaStackWriterLogback.addFromTraceToOutputStreamJsonAndFingerprint(
                            arrProxy, out, throwableClassName, hasher)
                    : JavaStackSanitizerLogback.addFromTraceToOutputStreamJsonAndFingerprint(
                            arrProxy, filter, out, throwableClassName, hasher);
            out.write('"');

            writeFieldPrefix(out, KEY_ERR_HASH);
            JsonNumberWriter.writeLong(out, fingerPrint);
        }

        // Dev extension point: the writer's writeExtraFields now writes into the
        // direct buffer, so the stream path replicates the dev variant's
        // missingKeys field byte-identically.
        if (writer instanceof JsonLogWriterDev dev) {
            List<String> missing = JsonLogWriterDev.findMissingKeys(event, pairs, mdcMap);
            if (!missing.isEmpty()) {
                out.write(',');
                EscapedJsonStringWriter.writeJsonStringOrNull(out, "missingKeys");
                out.write(':');
                out.write('[');
                for (int i = 0; i < missing.size(); i++) {
                    if (i > 0) out.write(',');
                    EscapedJsonStringWriter.writeJsonStringOrNull(out, missing.get(i));
                }
                out.write(']');
            }
        }

        out.write('}');
    }

    private static void writeFieldPrefix(OutputStream out, String key) throws IOException {
        out.write(',');
        out.write(key.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeFieldPrefixRawKey(OutputStream out, String key) throws IOException {
        out.write(',');
        EscapedJsonStringWriter.writeJsonStringOrNull(out, key);
        out.write(':');
    }

    private static boolean isReserved(String key) {
        return switch (key) {
            case "ts", "level", "logger", "thread", "msg", "errClass", "errHash", "errMessage" -> true;
            default -> false;
        };
    }

    protected static void addKey(OutputStream out, String key, Object value, ObjectMapper mapper) throws IOException {
        if (value == null) return;

        writeFieldPrefixRawKey(out, key);

        writeValue(out, value, mapper);
    }

    public static void writeRawValue(OutputStream out, RawValue raw, ObjectMapper mapper) throws IOException {
        Object backing = raw.rawValue();
        if (backing == null) {
            out.write(JsonLogWriter.JSON_NULL.getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (backing instanceof String s) {
            JsonLogWriter.STRING_STRATEGY.write(out, s);
            return;
        }
        mapper.writeValue(out, raw);
    }

    private static void writeValue(OutputStream out, Object value, ObjectMapper mapper) throws IOException {
        switch (value) {
            case String s -> EscapedJsonStringWriter.writeJsonStringOrNull(out, s);
            case CharSequence cs -> EscapedJsonStringWriter.writeJsonStringOrNull(out, cs.toString());
            case Character c -> EscapedJsonStringWriter.writeJsonStringOrNull(out, c.toString());
            case Enum<?> e -> EscapedJsonStringWriter.writeJsonStringOrNull(out, e.name());
            case RawValue raw -> writeRawValue(out, raw, mapper);
            case Long l -> JsonNumberWriter.writeLong(out, l);
            case Integer i -> JsonNumberWriter.writeInt(out, i);
            case Short s -> JsonNumberWriter.writeInt(out, s.intValue());
            case Byte b -> JsonNumberWriter.writeInt(out, b.intValue());
            case Float f -> JsonNumberWriter.writeFloat(out, f);
            case Double d -> JsonNumberWriter.writeDouble(out, d);
            case Number n -> JsonNumberWriter.writeNumber(out, n);
            case Boolean b -> out.write((b ? JsonLogWriter.JSON_TRUE : JsonLogWriter.JSON_FALSE).getBytes(StandardCharsets.UTF_8));
            case RawJsonSelfWriter w -> w.writeJson(out);
            case JsonLogWriter.RawJsonBytes b -> out.write(b.bytes());
            default -> mapper.writeValue(out, value);
        }
    }


}

