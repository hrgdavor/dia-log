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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the dev writer variant {@link JsonLogWriterDev}: the additive
 * {@code missingKeys} field (always-on, no boolean), and that the plain
 * {@link JsonLogWriter} never emits it.
 */
class JsonLogWriterDevTest {

    private final JsonLogWriterDev devWriter = new JsonLogWriterDev();
    private final JsonLogWriter plainWriter = new JsonLogWriter();
    private final ObjectMapper mapper = new ObjectMapper();

    private LoggingEvent event(String message) {
        LoggerContext context = new LoggerContext();
        Logger logger = context.getLogger("test.dev");
        logger.setLevel(Level.INFO);

        LoggingEvent event = new LoggingEvent("test.dev", logger, Level.INFO, message, null, null);
        event.setTimeStamp(123456789L);
        return event;
    }

    private String write(JsonLogWriter writer, LoggingEvent event) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writer.writeJsonEventStream(mapper, event, out);
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void missingKey_isReported() throws Exception {
        LoggingEvent event = event("User {user} logged from {ip}");
        applyIfPresent(event, "setKeyValuePairs", new Class<?>[]{List.class}, List.of(
            new KeyValuePair("user", "alice")
        ));

        String json = write(devWriter, event);

        assertTrue(json.contains("\"missingKeys\":[\"ip\"]"),
                "missing placeholder must be reported: " + json);
        assertTrue(json.endsWith("}"), "event must stay valid JSON: " + json);
    }

    @Test
    void allKeysPresent_noMissingKeysField() throws Exception {
        LoggingEvent event = event("User {user} logged from {ip}");
        applyIfPresent(event, "setKeyValuePairs", new Class<?>[]{List.class}, List.of(
            new KeyValuePair("user", "alice"),
            new KeyValuePair("ip", "10.0.0.1")
        ));

        String json = write(devWriter, event);

        assertFalse(json.contains("missingKeys"), "no field when all keys present: " + json);
        assertTrue(json.contains("\"user\":\"alice\""), "kv pairs still written: " + json);
    }

    @Test
    void mdcKey_countsAsPresent() throws Exception {
        LoggingEvent event = event("request {requestId}");
        applyIfPresent(event, "setMDCPropertyMap", new Class<?>[]{Map.class}, Map.of(
            "requestId", "req-42"
        ));

        String json = write(devWriter, event);

        assertFalse(json.contains("missingKeys"), "MDC key must satisfy placeholder: " + json);
    }

    @Test
    void nullValue_countsAsPresent() throws Exception {
        LoggingEvent event = event("state {state}");
        applyIfPresent(event, "setKeyValuePairs", new Class<?>[]{List.class}, List.of(
            new KeyValuePair("state", null)
        ));

        String json = write(devWriter, event);

        // "missing, null is ok" — a null-valued key is present, so no warning
        assertFalse(json.contains("missingKeys"), "null value must count as present: " + json);
    }

    @Test
    void positionalAndEscapedBraces_areIgnored() throws Exception {
        LoggingEvent positional = event("value {} and {}");
        LoggingEvent escaped = event("literal {{name}} stays");

        String json1 = write(devWriter, positional);
        String json2 = write(devWriter, escaped);

        assertFalse(json1.contains("missingKeys"), "SLF4J {} must not warn: " + json1);
        assertFalse(json2.contains("missingKeys"), "escaped braces must not warn: " + json2);
    }

    @Test
    void multipleMissingKeys_dedupedInOrder() throws Exception {
        LoggingEvent event = event("{a} {b} {a} {c}");
        applyIfPresent(event, "setKeyValuePairs", new Class<?>[]{List.class}, List.of(
            new KeyValuePair("a", 1)
        ));

        String json = write(devWriter, event);

        assertTrue(json.contains("\"missingKeys\":[\"b\",\"c\"]"),
                "deduped, first-occurrence order: " + json);
    }

    @Test
    void plainWriter_neverEmitsMissingKeys() throws Exception {
        LoggingEvent event = event("User {user} logged from {ip}");
        applyIfPresent(event, "setKeyValuePairs", new Class<?>[]{List.class}, List.of(
            new KeyValuePair("user", "alice")
        ));

        String json = write(plainWriter, event);

        assertFalse(json.contains("missingKeys"),
                "production writer must be unaffected by the dev variant: " + json);
    }

    @Test
    void findMissingKeys_nullEventOrMessage_isEmpty() {
        assertTrue(JsonLogWriterDev.findMissingKeys(null, null, null).isEmpty());
        assertTrue(JsonLogWriterDev.findMissingKeys(event(null), null, null).isEmpty());
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
