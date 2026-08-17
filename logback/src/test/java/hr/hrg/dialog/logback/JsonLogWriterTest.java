package hr.hrg.dialog.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.Test;
import org.slf4j.event.KeyValuePair;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Direct tests for {@link JsonLogWriter}: field layout, JSON escaping of user-supplied
 * keys, and key-value / MDC dedup. (Planned coverage item from plans/analysis-report.md §11.)
 */
class JsonLogWriterTest {

    private final JsonLogWriter writer = new JsonLogWriter();
    private final ObjectMapper mapper = new ObjectMapper();

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
        writer.writeJsonEvent(mapper, event, out);
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

    private static void applyIfPresent(Object target, String methodName, Class<?>[] argTypes, Object arg) {
        try {
            Method method = target.getClass().getMethod(methodName, argTypes);
            method.invoke(target, arg);
        } catch (ReflectiveOperationException e) {
            fail("setter " + methodName + " not available on LoggingEvent: " + e);
        }
    }
}
