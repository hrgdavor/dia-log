package hr.hrg.dialog.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.Test;
import org.slf4j.event.KeyValuePair;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.json.JsonFactory;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Direct tests for the classic Jackson-based {@link JsonLogWriterClassic}:
 * field layout, key/value + MDC handling, and exception fields.
 */
class JsonLogWriterClassicTest {

    private final JsonLogWriterClassic writer = new JsonLogWriterClassic();
    private final JsonFactory factory = JsonFactory.builder().build();

    private LoggingEvent event(String message) {
        LoggerContext context = new LoggerContext();
        Logger logger = context.getLogger("test.classic");
        logger.setLevel(Level.INFO);

        LoggingEvent event = new LoggingEvent("test.classic", logger, Level.INFO, message, null, null);
        event.setTimeStamp(123456789L);
        return event;
    }

    private String write(LoggingEvent event) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        JsonGenerator gen = factory.createGenerator(out);
        writer.writeJsonEvent(gen, event, out);
        gen.flush();
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void plainMessage_hasExpectedFields() throws Exception {
        String json = write(event("hello classic"));

        assertTrue(json.contains("\"msg\":\"hello classic\""), "msg field missing: " + json);
        assertTrue(json.contains("\"level\":\"INFO\""), "level field missing: " + json);
        assertTrue(json.contains("\"logger\":\"test.classic\""), "logger field missing: " + json);
        assertTrue(json.contains("\"ts\":"), "ts field missing: " + json);
        assertTrue(json.contains("\"thread\""), "thread field missing: " + json);
    }

    @Test
    void kvKeys_areJsonEscaped() throws Exception {
        LoggingEvent event = event("escaped");
        applyIfPresent(event, "setKeyValuePairs", new Class<?>[]{List.class}, List.of(
            new KeyValuePair("we\"ird\\key", "value")
        ));

        String json = write(event);

        assertTrue(json.contains("\"we\\\"ird\\\\key\":\"value\""),
                "key must be JSON-escaped: " + json);
    }

    @Test
    void kvKeyOverridesMdcKey() throws Exception {
        LoggingEvent event = event("dedup");
        applyIfPresent(event, "setKeyValuePairs", new Class<?>[]{List.class}, List.of(
            new KeyValuePair("shared", "from-kv")
        ));
        applyIfPresent(event, "setMDCPropertyMap", new Class<?>[]{Map.class}, Map.of(
            "shared", "from-mdc",
            "onlyMdc", "mdc-value"
        ));

        String json = write(event);

        assertTrue(json.contains("\"shared\":\"from-kv\""), "KV value must win: " + json);
        assertTrue(json.contains("\"onlyMdc\":\"mdc-value\""), "MDC-only key must be present: " + json);
        assertFalse(json.contains("\"shared\":\"from-mdc\""), "MDC must not override KV: " + json);
    }

    @Test
    void mdcKeys_skipReserved() throws Exception {
        LoggingEvent event = event("reserved");
        applyIfPresent(event, "setMDCPropertyMap", new Class<?>[]{Map.class}, Map.of(
            "msg", "should-not-appear",
            "custom", "ok"
        ));

        String json = write(event);

        assertTrue(json.contains("\"custom\":\"ok\""), "non-reserved MDC key must be present: " + json);
        assertFalse(json.contains("\"msg\":\"should-not-appear\""), "reserved key must be skipped: " + json);
    }

    @Test
    void exceptionEvent_writesStackAndErrHash() throws Exception {
        LoggerContext context = new LoggerContext();
        Logger logger = context.getLogger("test.classic");
        logger.setLevel(Level.ERROR);

        LoggingEvent event = new LoggingEvent("test.classic", logger, Level.ERROR, "boom",
                new RuntimeException("boom"), null);
        event.setTimeStamp(123456789L);

        String json = write(event);

        assertTrue(json.contains("\"errClass\":\"java.lang.RuntimeException\""), "errClass missing: " + json);
        assertTrue(json.contains("\"errMessage\":\"boom\""), "errMessage missing: " + json);
        assertTrue(json.contains("\"errHash\":"), "errHash missing: " + json);
        assertTrue(json.contains("\"stack\":"), "stack field missing: " + json);
    }

    @Test
    void addKey_typeSpecificValues() throws Exception {
        LoggingEvent event = event("types");
        applyIfPresent(event, "setKeyValuePairs", new Class<?>[]{List.class}, List.of(
            new KeyValuePair("long", 42L),
            new KeyValuePair("int", 7),
            new KeyValuePair("double", 1.5d),
            new KeyValuePair("bool", true),
            new KeyValuePair("str", "s")
        ));

        String json = write(event);

        assertTrue(json.contains("\"long\":42"), "long value: " + json);
        assertTrue(json.contains("\"int\":7"), "int value: " + json);
        assertTrue(json.contains("\"double\":1.5"), "double value: " + json);
        assertTrue(json.contains("\"bool\":true"), "boolean value: " + json);
        assertTrue(json.contains("\"str\":\"s\""), "string value: " + json);
    }

    private static void applyIfPresent(Object target, String methodName, Class<?>[] argTypes, Object arg) {
        try {
            Method method = target.getClass().getMethod(methodName, argTypes);
            method.invoke(target, arg);
        } catch (ReflectiveOperationException e) {
            fail("setter " + methodName + " not available on LoggingEvent: " + e);
        }
    }
}
