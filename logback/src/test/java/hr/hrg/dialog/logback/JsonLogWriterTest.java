package hr.hrg.dialog.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.Test;
import org.slf4j.event.KeyValuePair;
import hr.hrg.dialog.core.RawJsonSelfWriter;
import hr.hrg.dialog.core.Wyhash64;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.util.RawValue;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Direct tests for {@link JsonLogWriter}: field layout, JSON escaping of user-supplied
 * keys, and MDC reserved-key skipping. (Planned coverage item from plans/analysis-report.md §11.)
 */
class JsonLogWriterTest {

    private final JsonLogWriter writer = new JsonLogWriter();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Wyhash64.Streaming hasher = new Wyhash64.Streaming(0);

    private LoggingEvent event(String message) {
        LoggerContext context = new LoggerContext();
        Logger logger = context.getLogger("test.json");
        logger.setLevel(Level.INFO);

        LoggingEvent event = new LoggingEvent("test.json", logger, Level.INFO, message, null, null);
        event.setTimeStamp(123456789L);
        return event;
    }

    private String write(LoggingEvent event) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        JsonLogWriterStream.writeJsonEvent(writer, mapper, event, out, hasher);
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void plainMessage_hasExpectedFields() throws Exception {
        String json = write(event("hello world"));

        assertTrue(json.startsWith("{"), "output must start with '{': " + json);
        assertTrue(json.endsWith("}"), "output must end with '}': " + json);
        assertTrue(json.contains("\"msg\":\"hello world\""), "msg field missing: " + json);
        assertTrue(json.contains("\"level\":\"INFO\""), "level field missing: " + json);
        assertTrue(json.contains("\"logger\":\"test.json\""), "logger field missing: " + json);
        assertTrue(json.contains("\"ts\":"), "ts field missing: " + json);
    }

    @Test
    void kvKeys_areJsonEscaped() throws Exception {
        LoggingEvent event = event("escaped keys");
        applyIfPresent(event, "setKeyValuePairs", new Class<?>[]{List.class}, List.of(
            new KeyValuePair("we\"ird\\key", "value")
        ));

        String json = write(event);

        // Expected escaped form: "we\"ird\\key":"value"
        assertTrue(json.contains("\"we\\\"ird\\\\key\":\"value\""),
                "key must be JSON-escaped: " + json);
    }

    @Test
    void kvKeyAndMdcKey_bothAppear() throws Exception {
        LoggingEvent event = event("dedup");
        applyIfPresent(event, "setKeyValuePairs", new Class<?>[]{List.class}, List.of(
            new KeyValuePair("shared", "from-kv")
        ));
        applyIfPresent(event, "setMDCPropertyMap", new Class<?>[]{Map.class}, Map.of(
            "shared", "from-mdc",
            "onlyMdc", "mdc-value"
        ));

        String json = write(event);

        assertTrue(json.contains("\"shared\":\"from-kv\""), "KV value must be present: " + json);
        assertTrue(json.contains("\"onlyMdc\":\"mdc-value\""), "MDC-only key must be present: " + json);
        // Duplicate keys are no longer deduped — both KV and MDC values appear.
        // Downstream log ingestion handles rare duplicate-key cases.
        assertTrue(json.contains("\"shared\":\"from-mdc\""), "MDC value also present: " + json);
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
        assertFalse(json.contains("\"msg\":\"should-not-appear\""),
                "reserved key must be skipped: " + json);
    }

    @Test
    void noKvNoMdc_isStillValidJson() throws Exception {
        String json = write(event("no extras"));

        assertTrue(json.startsWith("{") && json.endsWith("}"), "must remain a valid object: " + json);
        assertTrue(json.contains("\"msg\":\"no extras\""), "message must be present: " + json);
    }

    @Test
    void exceptionEvent_writesCompleteStackAndErrHash() throws Exception {
        LoggerContext context = new LoggerContext();
        Logger logger = context.getLogger("test.json");
        logger.setLevel(Level.ERROR);

        LoggingEvent event = new LoggingEvent("test.json", logger, Level.ERROR, "boom",
                new RuntimeException("boom"), null);
        event.setTimeStamp(123456789L);

        String json = write(event);

        // Regression: the event must not be truncated mid-stack (previously the
        // no-filter path called the filter variant with a null filter -> NPE).
        assertTrue(json.endsWith("}"), "event must be complete, not truncated: " + json);
        assertTrue(json.contains("\"errClass\":\"java.lang.RuntimeException\""), "errClass missing: " + json);
        assertTrue(json.contains("\"errMessage\":\"boom\""), "errMessage missing: " + json);
        assertTrue(json.contains("\"stack\":"), "stack field missing: " + json);
        assertTrue(json.contains("\"errHash\":"), "errHash missing: " + json);
    }

    @Test
    void exceptionEvent_withFilter_usesFilteredPath() throws Exception {
        LoggerContext context = new LoggerContext();
        Logger logger = context.getLogger("test.json");
        logger.setLevel(Level.ERROR);

        LoggingEvent event = new LoggingEvent("test.json", logger, Level.ERROR, "boom",
                new RuntimeException("boom"), null);
        event.setTimeStamp(123456789L);

        JsonLogWriter filtered = new JsonLogWriter();
        filtered.setStackTraceFilter(cls -> true);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        JsonLogWriterStream.writeJsonEvent(filtered, mapper, event, out, hasher);
        String json = out.toString(StandardCharsets.UTF_8);

        assertTrue(json.endsWith("}"), "filtered event must be complete: " + json);
        assertTrue(json.contains("\"errHash\":"), "errHash missing: " + json);
        assertTrue(json.contains("\"stack\":"), "stack missing: " + json);
    }

    @Test
    void mdcKeys_allReserved_areSkipped() throws Exception {
        LoggingEvent event = event("reserved2");
        Map<String, String> mdc = new LinkedHashMap<>();
        for (String reserved : List.of("ts", "level", "logger", "thread", "msg", "errClass", "errHash", "errMessage")) {
            mdc.put(reserved, "x-" + reserved);
        }
        mdc.put("custom", "ok");
        applyIfPresent(event, "setMDCPropertyMap", new Class<?>[]{Map.class}, mdc);

        String json = write(event);

        assertTrue(json.contains("\"custom\":\"ok\""), "custom key must be present: " + json);
        for (String reserved : List.of("ts", "level", "logger", "thread", "msg", "errClass", "errHash", "errMessage")) {
            assertFalse(json.contains("\"" + reserved + "\":\"x-"),
                    "reserved key must be skipped: " + reserved + " in " + json);
        }
    }

    @Test
    void mdcNullKey_isSkipped() throws Exception {
        LoggingEvent event = event("nullkey");
        Map<String, String> mdc = new HashMap<>();
        mdc.put(null, "null-key-value");
        mdc.put("ok", "fine");
        applyIfPresent(event, "setMDCPropertyMap", new Class<?>[]{Map.class}, mdc);

        String json = write(event);

        assertTrue(json.contains("\"ok\":\"fine\""), "non-null key must be present: " + json);
    }

    @Test
    void kvNullValue_isSkipped() throws Exception {
        LoggingEvent event = event("nullval");
        applyIfPresent(event, "setKeyValuePairs", new Class<?>[]{List.class}, List.of(
            new KeyValuePair("nullKey", null),
            new KeyValuePair("real", "v")
        ));

        String json = write(event);

        assertTrue(json.contains("\"real\":\"v\""), "non-null value must be present: " + json);
        assertFalse(json.contains("\"nullKey\""), "null value must be skipped: " + json);
    }

    @Test
    void valueTypes_areSerialized() throws Exception {
        LoggingEvent event = event("types");
        applyIfPresent(event, "setKeyValuePairs", new Class<?>[]{List.class}, List.of(
            new KeyValuePair("str", "s"),
            new KeyValuePair("chr", 'c'),
            new KeyValuePair("bool", true),
            new KeyValuePair("byte", (byte) 1),
            new KeyValuePair("short", (short) 2),
            new KeyValuePair("int", 3),
            new KeyValuePair("long", 4L),
            new KeyValuePair("float", 1.5f),
            new KeyValuePair("double", 2.5d),
            new KeyValuePair("big", new java.math.BigDecimal("3.25")),
            new KeyValuePair("enum", java.util.concurrent.TimeUnit.SECONDS),
            new KeyValuePair("cs", new StringBuilder("charsq")),
            new KeyValuePair("rawV", new RawValue("{\"raw\":1}")),
            new KeyValuePair("rawBytes", new JsonLogWriter.RawJsonBytes(new byte[]{'{', '}'})),
            new KeyValuePair("rawSelf", (RawJsonSelfWriter) out -> {
                try {
                    out.write('[');
                } catch (java.io.IOException e) {
                    throw new RuntimeException(e);
                }
            }),
            new KeyValuePair("pojo", new Object())
        ));

        String json = write(event);

        assertTrue(json.contains("\"str\":\"s\""), "string: " + json);
        assertTrue(json.contains("\"chr\":\"c\""), "char: " + json);
        assertTrue(json.contains("\"bool\":true"), "boolean: " + json);
        assertTrue(json.contains("\"byte\":1"), "byte: " + json);
        assertTrue(json.contains("\"short\":2"), "short: " + json);
        assertTrue(json.contains("\"int\":3"), "int: " + json);
        assertTrue(json.contains("\"long\":4"), "long: " + json);
        assertTrue(json.contains("\"float\":1.5"), "float: " + json);
        assertTrue(json.contains("\"double\":2.5"), "double: " + json);
        assertTrue(json.contains("\"big\":3.25"), "number: " + json);
        assertTrue(json.contains("\"enum\":\"SECONDS\""), "enum: " + json);
        assertTrue(json.contains("\"cs\":\"charsq\""), "charsequence: " + json);
        assertTrue(json.contains("\"rawV\":{\"raw\":1}"), "raw value: " + json);
        assertTrue(json.contains("\"rawBytes\":{}"), "raw bytes: " + json);
        assertTrue(json.contains("\"rawSelf\":["), "raw self writer: " + json);
        assertTrue(json.contains("\"pojo\":{}"), "pojo via mapper: " + json);
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
