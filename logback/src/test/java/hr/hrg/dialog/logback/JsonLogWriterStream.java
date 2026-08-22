package hr.hrg.dialog.logback;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import hr.hrg.dialog.core.EscapedJsonStringWriter;
import hr.hrg.dialog.core.JsonNumberWriter;
import hr.hrg.dialog.core.StringByteExtractor;
import hr.hrg.dialog.core.Wyhash64;
import org.slf4j.event.KeyValuePair;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// Test-only, byte-identical reimplementation of the naive stream-fallback event
/// writer that previously lived on {@link JsonLogWriter}. Production uses the
/// optimized {@link JsonLogWriter#writeJsonEventDirect direct-buffer path}; this
/// class exists only so benchmarks and unit tests can exercise the fallback and
/// confirm it produces the same bytes. Not part of the published API.
///
/// <p>Value-type dispatch ({@code String}, {@code Long}, {@code RawValue}, ...) is
/// delegated to the writer's own {@link JsonLogWriter#addKey protected addKey}
/// (same-package access), so this class never replicates the type switch. The
/// {@link JsonLogWriter#writeExtraFields extension point} is invoked through the
/// writer, so the {@link JsonLogWriterDev dev variant}'s {@code missingKeys} field
/// is still emitted.
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

        Set<String> allKeys = null;
        List<KeyValuePair> pairs = event.getKeyValuePairs();
        if (pairs != null && !pairs.isEmpty()) {
            boolean trackForMdcDedup = mdcMap != null && !mdcMap.isEmpty();
            for (KeyValuePair kvPair : pairs) {
                if (kvPair.key != null) {
                    if (trackForMdcDedup) {
                        if (allKeys == null) {
                            allKeys = new HashSet<>();
                        }
                        allKeys.add(kvPair.key);
                    }
                    writer.addKey(out, kvPair.key, kvPair.value, mapper);
                }
            }
        }

        if (mdcMap != null && !mdcMap.isEmpty()) {
            for (Map.Entry<String, String> entry : mdcMap.entrySet()) {
                if (entry.getKey() != null
                        && !isReserved(entry.getKey())
                        && (allKeys == null || !allKeys.contains(entry.getKey()))) {
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

        writer.writeExtraFields(event, out, pairs, mdcMap);

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
}
