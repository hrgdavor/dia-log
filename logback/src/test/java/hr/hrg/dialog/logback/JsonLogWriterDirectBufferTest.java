package hr.hrg.dialog.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
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
        assertDirectMatchesStream(event, 1024);
    }

    /** Writes {@code event} through each path with the given direct-buffer capacity and asserts identical bytes. */
    private void assertDirectMatchesStream(LoggingEvent event, int initialCapacity) throws Exception {
        ReusableByteArrayOutputStream direct = new ReusableByteArrayOutputStream(initialCapacity);
        writer.writeJsonEventDirect(mapper, event, direct);

        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        JsonLogWriterStream.writeJsonEvent(writer, mapper, event, stream, hasher);

        assertArrayEquals(stream.toByteArray(), java.util.Arrays.copyOf(direct.buffer(), direct.size()),
                "direct-buffer output must equal stream output (capacity " + initialCapacity + ")");
        String json = new String(stream.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(json.startsWith("{") && json.endsWith("}"), "valid object: " + json);
    }

    @Test
    void tinyBufferForcesEveryGrowPath_matchesAcrossPaths() throws Exception {
        // An 8-byte initial buffer is smaller than the very first inline reserve
        // (ts prefix word slot + bounded long), so every packed-key capacity
        // check must round the key up to whole 8-byte word slots and grow
        // before storing (no ArrayIndexOutOfBounds, byte-identical output).
        assertDirectMatchesStream(event("grow me"), 8);
    }

    @Test
    void tinyBufferThrowableEvent_matchesAcrossPaths() throws Exception {
        // Exercises the stack and errHash inline checks on the grow path too.
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
        assertDirectMatchesStream(event, 8);
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
