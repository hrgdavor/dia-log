package hr.hrg.dialog.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import hr.hrg.dialog.core.RawJsonSelfWriter;
import hr.hrg.dialog.core.ReusableByteArrayOutputStream;
import hr.hrg.dialog.core.Wyhash64;
import org.junit.jupiter.api.Test;
import org.slf4j.event.KeyValuePair;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.util.RawValue;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * T6 integration test: {@link JsonLogWriter} must produce byte-identical JSON
 * through the direct-buffer path (writing into a
 * {@link ReusableByteArrayOutputStream}, which activates the packed-prefix
 * fast path ported from Apache Fory commit 585eb16f) and through the plain
 * {@code OutputStream} fallback (which retains the old {@code write(byte[])}
 * prefix behavior). The event shapes cover every fixed field prefix
 * ({@code ts}, {@code level}, {@code logger}, {@code thread}, {@code msg},
 * {@code errClass}, {@code errMessage}, {@code stack}, {@code errHash}) plus
 * KV/MDC user keys and the escaping-heavy string path.
 * <p>
 * The direct buffer is fixed-capacity (no-grow, T12): a buffer too small for
 * an event never grows and never throws — the assembly finalizes with a
 * minimal {@code {}}, the packed {@code "V2BIG"} placeholder for an overflowing
 * value, or a {@code '}'} close when a field key no longer fits.
 */
class JsonLogWriterDirectBufferTest {

    private final JsonLogWriter writer = new JsonLogWriter();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Wyhash64.Streaming hasher = new Wyhash64.Streaming(0);

    private LoggingEvent event(String message) {
        LoggerContext context = new LoggerContext();
        Logger logger = context.getLogger("test.direct");
        logger.setLevel(Level.INFO);

        LoggingEvent event = new LoggingEvent("test.direct", logger, Level.INFO, message, null, null);
        event.setTimeStamp(123456789L);
        return event;
    }

    private static void applyIfPresent(Object target, String methodName, Class<?>[] argTypes, Object arg) {
        try {
            Method method = target.getClass().getMethod(methodName, argTypes);
            method.invoke(target, arg);
        } catch (ReflectiveOperationException e) {
            fail("setter " + methodName + " not available on LoggingEvent: " + e);
        }
    }

    /** Writes {@code event} once through each path and asserts identical bytes. */
    private void assertDirectMatchesStream(LoggingEvent event) throws Exception {
        // 64 KiB: generous enough for the throwable-event stack traces without
        // relying on buffer growth (the buffer never grows).
        assertDirectMatchesStream(event, 64 * 1024);
    }

    /** Writes {@code event} through each path with the given direct-buffer capacity and asserts identical bytes. */
    private void assertDirectMatchesStream(LoggingEvent event, int initialCapacity) throws Exception {
        ReusableByteArrayOutputStream direct = new ReusableByteArrayOutputStream(initialCapacity);
        int pos = writer.writeJsonEventDirect(mapper, event, direct);

        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        JsonLogWriterStream.writeJsonEvent(writer, mapper, event, stream, hasher);

        assertArrayEquals(stream.toByteArray(), java.util.Arrays.copyOf(direct.buffer(), pos),
                "direct-buffer output must equal stream output (capacity " + initialCapacity + ")");
        String json = new String(stream.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(json.startsWith("{") && json.endsWith("}"), "valid object: " + json);
    }

    @Test
    void tinyBuffer_producesMinimalObject() throws Exception {
        // 8-byte buffer: not even the very first inline reserve (ts prefix
        // word slot + bounded long) fits, so the defensive branch emits a
        // minimal "{}" — no growth, no exception.
        ReusableByteArrayOutputStream direct = new ReusableByteArrayOutputStream(8);
        int pos = writer.writeJsonEventDirect(mapper, event("grow me"), direct);
        assertEquals(2, pos);
        assertArrayEquals("{}".getBytes(StandardCharsets.US_ASCII),
                java.util.Arrays.copyOf(direct.buffer(), pos));
        assertEquals(8, direct.buffer().length, "buffer must not grow");
    }

    @Test
    void tinyBufferThrowableEvent_producesMinimalObject() throws Exception {
        // Same no-grow contract with a throwable event: the whole event is
        // dropped into a minimal "{}" — valid JSON, no growth, no exception.
        LoggerContext context = new LoggerContext();
        Logger logger = context.getLogger("test.direct");
        logger.setLevel(Level.ERROR);

        LoggingEvent event = new LoggingEvent("test.direct", logger, Level.ERROR, "boom",
                new RuntimeException("boom"), null);
        event.setTimeStamp(123456789L);
        event.setThreadName("t-1");
        applyIfPresent(event, "setKeyValuePairs", new Class<?>[]{List.class}, List.of(
            new KeyValuePair("k", "v"),
            new KeyValuePair("n", 42)
        ));
        applyIfPresent(event, "setMDCPropertyMap", new Class<?>[]{Map.class}, Map.of("m", "1"));

        ReusableByteArrayOutputStream direct = new ReusableByteArrayOutputStream(8);
        int pos = writer.writeJsonEventDirect(mapper, event, direct);
        assertEquals(2, pos);
        assertArrayEquals("{}".getBytes(StandardCharsets.US_ASCII),
                java.util.Arrays.copyOf(direct.buffer(), pos));
        assertEquals(8, direct.buffer().length, "buffer must not grow");
    }

    @Test
    void smallBuffer_longMessage_valueReplacedByV2BIG() throws Exception {
        // 128-byte buffer: the fixed fields fit, but the long msg value
        // overflows — it is replaced by "V2BIG" and the object stays valid.
        ReusableByteArrayOutputStream direct = new ReusableByteArrayOutputStream(128);
        String msg = "this message is far too long to fit in a small buffer";
        int pos = writer.writeJsonEventDirect(mapper, event(msg), direct);
        String json = new String(direct.buffer(), 0, pos, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"msg\":\"V2BIG\""), "value must be replaced: " + json);
        assertTrue(json.endsWith("}"), "must end with '}': " + json);
    }

    @Test
    void smallBuffer_whenKeyDoesNotFit_closesObjectCleanly() throws Exception {
        // 64-byte buffer: the logger key no longer fits after ts+level, so the
        // object closes with '}' — no dangling comma, no stray placeholder.
        ReusableByteArrayOutputStream direct = new ReusableByteArrayOutputStream(64);
        int pos = writer.writeJsonEventDirect(mapper, event("hello"), direct);
        String json = new String(direct.buffer(), 0, pos, StandardCharsets.UTF_8);
        assertTrue(json.endsWith("}"), "must end with '}': " + json);
        assertFalse(json.contains("V2BIG"), "key overflow must not write a placeholder: " + json);
        assertFalse(json.contains(",}"), "no dangling comma: " + json);
        assertTrue(json.contains("\"ts\":"), "ts must be present: " + json);
        assertTrue(json.contains("\"level\":\"INFO\""), "level must be present: " + json);
    }

    @Test
    void unsizedValue_overflow_replacedByV2BIG_noException() throws Exception {
        // A jackson-serialized value larger than the buffer: the value is
        // replaced by "V2BIG", no exception escapes, the event stays valid.
        ReusableByteArrayOutputStream direct = new ReusableByteArrayOutputStream(128);
        LoggingEvent event = event("unsized");
        applyIfPresent(event, "setKeyValuePairs", new Class<?>[]{List.class}, List.of(
            new KeyValuePair("big", Map.of("data", "x".repeat(10_000)))
        ));
        int pos = writer.writeJsonEventDirect(mapper, event, direct);
        String json = new String(direct.buffer(), 0, pos, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"big\":\"V2BIG\""), "value must be replaced: " + json);
        assertTrue(json.endsWith("}"), "valid JSON: " + json);
    }

    @Test
    void rawSelfWriter_overflow_replacedByV2BIG_noException() throws Exception {
        // A RawJsonSelfWriter that emits more bytes than the buffer holds.
        ReusableByteArrayOutputStream direct = new ReusableByteArrayOutputStream(128);
        LoggingEvent event = event("self");
        RawJsonSelfWriter big = out -> {
            byte[] chunk = new byte[4096];
            java.util.Arrays.fill(chunk, (byte) 'z');
            try {
                out.write(chunk, 0, chunk.length);
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
        };
        applyIfPresent(event, "setKeyValuePairs", new Class<?>[]{List.class}, List.of(
            new KeyValuePair("self", big)
        ));
        int pos = writer.writeJsonEventDirect(mapper, event, direct);
        String json = new String(direct.buffer(), 0, pos, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"self\":\"V2BIG\""), "value must be replaced: " + json);
        assertTrue(json.endsWith("}"), "valid JSON: " + json);
    }

    @Test
    void stackOverflow_replacedByV2BIG_noException() throws Exception {
        // 192-byte buffer: errClass/errMessage fit, but the stack trace does
        // not — the whole stack field is replaced by "V2BIG"; no exception.
        ReusableByteArrayOutputStream direct = new ReusableByteArrayOutputStream(192);
        LoggerContext context = new LoggerContext();
        Logger logger = context.getLogger("test.direct");
        logger.setLevel(Level.ERROR);

        LoggingEvent event = new LoggingEvent("test.direct", logger, Level.ERROR, "boom",
                new RuntimeException("boom"), null);
        event.setTimeStamp(123456789L);
        int pos = writer.writeJsonEventDirect(mapper, event, direct);
        String json = new String(direct.buffer(), 0, pos, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"stack\":\"V2BIG\""), "stack must be replaced: " + json);
        assertTrue(json.endsWith("}"), "valid JSON: " + json);
    }

    @Test
    void plainEvent_matchesAcrossPaths() throws Exception {
        assertDirectMatchesStream(event("plain message"));
    }

    @Test
    void eventWithAllFieldTypes_matchesAcrossPaths() throws Exception {
        LoggingEvent event = event("héllo wörld \"quoted\" \\backslash\\ \n\t\u0001\u001F");
        event.setThreadName("t-42");
        applyIfPresent(event, "setKeyValuePairs", new Class<?>[]{List.class}, List.of(
            new KeyValuePair("str", "s"),
            new KeyValuePair("int", 3),
            new KeyValuePair("long", 4L),
            new KeyValuePair("float", 1.5f),
            new KeyValuePair("double", 2.5d),
            new KeyValuePair("bool", true),
            new KeyValuePair("big", new java.math.BigDecimal("3.25")),
            new KeyValuePair("chr", 'c'),
            new KeyValuePair("we\"ird\\key", "value"),
            new KeyValuePair("rawV", new RawValue("{\"raw\":1}")),
            new KeyValuePair("emoji", "🚀")
        ));
        applyIfPresent(event, "setMDCPropertyMap", new Class<?>[]{Map.class}, Map.of(
            "mdcKey", "mdc-value",
            "shared", "from-mdc"
        ));
        assertDirectMatchesStream(event);
    }

    @Test
    void throwableEvent_matchesAcrossPaths() throws Exception {
        LoggerContext context = new LoggerContext();
        Logger logger = context.getLogger("test.direct");
        logger.setLevel(Level.ERROR);

        LoggingEvent event = new LoggingEvent("test.direct", logger, Level.ERROR, "boom",
                new RuntimeException("boom"), null);
        event.setTimeStamp(123456789L);
        event.setThreadName("t-1");

        assertDirectMatchesStream(event);
    }

    @Test
    void longValues_matchesAcrossPaths() throws Exception {
        LoggingEvent event = event("longs");
        applyIfPresent(event, "setKeyValuePairs", new Class<?>[]{List.class}, List.of(
            new KeyValuePair("max", Long.MAX_VALUE),
            new KeyValuePair("min", Long.MIN_VALUE),
            new KeyValuePair("iMax", Integer.MAX_VALUE),
            new KeyValuePair("iMin", Integer.MIN_VALUE)
        ));
        assertDirectMatchesStream(event);
    }

    @Test
    void nullFields_matchesAcrossPaths() throws Exception {
        // loggerName/threadName/message nullability variations
        LoggerContext context = new LoggerContext();
        Logger logger = context.getLogger("test.direct");
        logger.setLevel(Level.INFO);

        LoggingEvent event = new LoggingEvent("test.direct", logger, Level.INFO, null, null, null);
        event.setTimeStamp(0L);
        assertDirectMatchesStream(event);
    }
}
