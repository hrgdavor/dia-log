package hr.hrg.dialog.logback;

import javax.annotation.concurrent.NotThreadSafe;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import ch.qos.logback.classic.spi.StackTraceElementProxy;
import hr.hrg.dialog.core.EscapedJsonStringWriter;
import hr.hrg.dialog.core.JsonNumberWriter;
import hr.hrg.dialog.core.RawJsonSelfWriter;
import hr.hrg.dialog.core.ReusableByteArrayOutputStream;
import hr.hrg.dialog.core.StringByteExtractor;
import hr.hrg.dialog.core.Wyhash64;
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
@NotThreadSafe
public class JsonLogWriter {

    /** Newline bytes (UTF-8) - strictly Unix LF (\n). */
    public static final byte[] NL = new byte[]{ 0x0A };

    public record RawJsonBytes(byte[] bytes){}


    private static final StringByteExtractor.ByteWriter STRING_STRATEGY = StringByteExtractor.getStrategy();

    // T6: fixed field prefixes packed as little-endian long words (ported from
    // Apache Fory commit 585eb16f, "feat(java): optimize json perf", PR #3871,
    // Utf8WriterCodegen). Each prefix is precomputed once at class init; the
    // direct-buffer path stores it with 1-2 packed 8-byte stores and one
    // inlined capacity check instead of a virtual write(byte[]) call.
    private static final PackedKey KEY_TS = new PackedKey("\"ts\":");
    private static final PackedKey KEY_LEVEL = new PackedKey("\"level\":");
    private static final PackedKey KEY_LOGGER = new PackedKey("\"logger\":");
    private static final PackedKey KEY_THREAD = new PackedKey("\"thread\":");
    private static final PackedKey KEY_MSG = new PackedKey("\"msg\":");
    private static final PackedKey KEY_ERR_CLASS = new PackedKey("\"errClass\":");
    private static final PackedKey KEY_ERR_MESSAGE = new PackedKey("\"errMessage\":");
    private static final PackedKey KEY_ERR_HASH = new PackedKey("\"errHash\":");
    private static final PackedKey KEY_STACK = new PackedKey("\"stack\":");
    private static final byte[] JSON_NULL = "null".getBytes(StandardCharsets.UTF_8);
    private static final byte[] JSON_TRUE = "true".getBytes(StandardCharsets.UTF_8);
    private static final byte[] JSON_FALSE = "false".getBytes(StandardCharsets.UTF_8);

     private final byte[] intNumberBuffer = JsonNumberWriter.makeIntBuffer();
     private final byte[] longNumberBuffer = JsonNumberWriter.makeLongBuffer();
    private final byte[] floatNumberBuffer = JsonNumberWriter.makeFloatBuffer();
    private final byte[] doubleNumberBuffer = JsonNumberWriter.makeDoubleBuffer();

    /** Filter applied to stack trace frame class names during fingerprinting. Defaults to accepting all frames. */
    private Predicate<String> stackTraceFilter = null;

    /**
     * Reusable hasher for the single-pass stack-trace fingerprint, owned by this
     * writer like the number buffers below and passed as a parameter to the
     * single-pass methods (which reset it internally). No hidden ThreadLocal
     * state — the writer is {@link NotThreadSafe}, so an instance must not be
     * used from several threads at once, exactly like the number buffers.
     */
    private final Wyhash64.Streaming fingerprintStream = new Wyhash64.Streaming(0);

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
            writeObjectStartAndField(out, KEY_TS);
            JsonNumberWriter.writeLong(out, longNumberBuffer, event.getTimeStamp());

            writeFieldPrefix(out, KEY_LEVEL);
            writeJsonStringOrNull(out, event.getLevel() != null ? event.getLevel().toString() : null);

            writeFieldPrefix(out, KEY_LOGGER);
            writeJsonStringOrNull(out, event.getLoggerName());

            writeFieldPrefix(out, KEY_THREAD);
            writeJsonStringOrNull(out, event.getThreadName());

            writeFieldPrefix(out, KEY_MSG);
            writeJsonStringOrNull(out, event.getFormattedMessage());

            // MDC is fetched first: the KV key-tracking set below is only needed
            // when MDC keys could collide with statement keys, so it is skipped
            // entirely (no allocation) when there is no MDC.
            Map<String, String> mdcMap = null;
            try {
                mdcMap = event.getMDCPropertyMap();
            } catch (Exception ignored) {}

            // Structured key-value pairs
            Set<String> allKeys = null;
            List<KeyValuePair> pairs = event.getKeyValuePairs();
            if (pairs != null && !pairs.isEmpty()) {
                boolean trackForMdcDedup = mdcMap != null && !mdcMap.isEmpty();
                for (KeyValuePair kvPair : pairs) {
                    if (kvPair.key != null) {
                        if (trackForMdcDedup) {
                            if (allKeys == null) allKeys = new HashSet<>();
                            allKeys.add(kvPair.key);
                        }
                        addKey(out, kvPair.key, kvPair.value, mapper);
                    }
                }
            }

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
                // Reuse this writer's hasher (owned like the number buffers) so
                // exception events allocate nothing — the single-pass methods
                // reset the stream internally.
                Wyhash64.Streaming stream = fingerprintStream;
                // micro optimization to call variant without filter
                long fingerPrint = stackTraceFilter == null ?
                JavaStackWriterLogback.addFromTraceToOutputStreamJsonAndFingerprint(
                    arrProxy,
                    out,
                    throwableClassName,
                    stream
                ) : 
                JavaStackSanitizerLogback.addFromTraceToOutputStreamJsonAndFingerprint(
                    arrProxy,
                    stackTraceFilter,
                    out,
                    throwableClassName,
                    stream
                );
                out.write('"');

                writeFieldPrefix(out, KEY_ERR_HASH);
                JsonNumberWriter.writeLong(out, longNumberBuffer, fingerPrint);

            }

            // Dev/diagnostic extension point — no-op in this production writer.
            writeExtraFields(event, out, pairs, mdcMap);

            out.write('}');
        } catch (IOException e) {
            // Error reporting is the caller's job: logback appenders report write
            // failures through their StatusManager (AppenderBase.doAppend -> addError).
            throw e;
        }
    }

    /**
     * Extension point for diagnostic/dev writer variants (e.g. missing-key
     * warnings). Called after all regular fields but before the closing brace.
     * The default implementation writes nothing, so production output is
     * unaffected; implementations must write a complete field including its own
     * leading comma (like {@code writeFieldPrefix}) when they want to add data.
     *
     * @param event  the event being serialized
     * @param out    the target stream, positioned after the last regular field
     * @param pairs  the event's statement key/value pairs, or {@code null}
     * @param mdcMap MDC map, or {@code null} if none was available
     */
    protected void writeExtraFields(ILoggingEvent event, OutputStream out, List<KeyValuePair> pairs, Map<String, String> mdcMap) throws IOException {
        // no-op in the production writer
    }

    private boolean isReserved(String key) {
        return switch (key) {
            case "ts", "level", "logger", "thread", "msg", "errClass", "errHash", "errMessage" -> true;
            default -> false;
        };
    }

    private static void writeFieldPrefix(OutputStream out, PackedKey key) throws IOException {
        if (out instanceof ReusableByteArrayOutputStream rbo) {
            rbo.write(',');
            key.writeDirect(rbo);
        } else {
            out.write(',');
            out.write(key.bytes());
        }
    }

    /** Writes '{' fused with the first field's prefix (T6 object-start fusion). */
    private static void writeObjectStartAndField(OutputStream out, PackedKey key) throws IOException {
        if (out instanceof ReusableByteArrayOutputStream rbo) {
            rbo.write('{');
            key.writeDirect(rbo);
        } else {
            out.write('{');
            out.write(key.bytes());
        }
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

    /**
     * A fixed JSON field prefix (e.g. {@code "level":}) precomputed once as its
     * UTF-8 bytes plus up to two little-endian packed {@code long} words.
     * <p>
     * T6 (from Apache Fory, see class header): the direct-buffer path stores the
     * prefix with 1-2 packed 8-byte stores and one inlined capacity check; the
     * stream fallback keeps the original {@code write(byte[])} behavior.
     */
    private record PackedKey(byte[] bytes, long word0, long word1) {

        PackedKey(String json) {
            this(json.getBytes(StandardCharsets.UTF_8));
        }

        PackedKey(byte[] bytes) {
            this(bytes, pack(bytes, 0), pack(bytes, 8));
        }

        /** Packs up to 8 bytes starting at {@code off} little-endian into one long. */
        private static long pack(byte[] bytes, int off) {
            long v = 0;
            int end = Math.min(off + 8, bytes.length);
            for (int i = off; i < end; i++) {
                v |= (bytes[i] & 0xFFL) << ((i - off) << 3);
            }
            return v;
        }

        /** Stores the packed prefix into the buffer with one inlined capacity check. */
        void writeDirect(ReusableByteArrayOutputStream rbo) {
            rbo.writeLongPrefixLE(word0, Math.min(8, bytes.length));
            if (bytes.length > 8) {
                rbo.writeLongPrefixLE(word1, bytes.length - 8);
            }
        }
    }
}