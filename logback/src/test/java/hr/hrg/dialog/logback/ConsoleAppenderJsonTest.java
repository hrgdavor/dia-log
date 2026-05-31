package hr.hrg.dialog.logback;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.ContextBase;
import org.slf4j.event.KeyValuePair;

/**
 * Unit tests for {@link JsonLogWriter} output format.
 * Tests ensure {@link ConsoleAppenderJson} and {@link RollingFileAppenderJson}
 * produce the same JSON via the shared writer.
 */
public class ConsoleAppenderJsonTest {

    private JsonLogWriter jsonWriter;
    private ByteArrayOutputStream baos;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeEach
    void setUp() {
        jsonWriter = new JsonLogWriter();
        jsonWriter.setContext(new ContextBase());
        jsonWriter.start();

        baos = new ByteArrayOutputStream();
    }

    @Test
    void testSimpleInfoLog() throws Exception {
        LoggingEvent event = createEvent(Level.INFO, "Hello, world!");

        jsonWriter.writeJsonEvent(event, baos);
        String json = baos.toString("UTF-8");

        JsonNode root = MAPPER.readTree(json);
        assertEquals("INFO", root.get("level").asText());
        assertEquals("test.Logger", root.get("logger").asText());
        assertEquals("Hello, world!", root.get("msg").asText());
        assertTrue(root.has("ts"), "Should have timestamp");
    }

    @Test
    void testAllLogLevels() throws Exception {
        for (Level level : List.of(Level.TRACE, Level.DEBUG, Level.INFO, Level.WARN, Level.ERROR)) {
            baos.reset();
            LoggingEvent event = createEvent(level, "Level test: " + level);

            jsonWriter.writeJsonEvent(event, baos);
            String json = baos.toString("UTF-8");

            JsonNode root = MAPPER.readTree(json);
            assertEquals(level.toString(), root.get("level").asText(),
                    "Level should be " + level);
        }
    }

    @Test
    void testStructuredKeyValuePairs() throws Exception {
        LoggingEvent event = createEvent(Level.INFO, "KV test");
        event.addKeyValuePair(new KeyValuePair("userId", 42));
        event.addKeyValuePair(new KeyValuePair("durationMs", 150));
        event.addKeyValuePair(new KeyValuePair("success", true));

        jsonWriter.writeJsonEvent(event, baos);
        String json = baos.toString("UTF-8");

        JsonNode root = MAPPER.readTree(json);
        JsonNode kv = root.get("kv");
        assertNotNull(kv, "Should have kv object");
        assertEquals(42, kv.get("userId").asInt());
        assertEquals(150, kv.get("durationMs").asInt());
        assertTrue(kv.get("success").asBoolean());
    }

    @Test
    void testMdcContextInclusion() throws Exception {
        LoggingEvent event = createEvent(Level.WARN, "MDC test");
        event.setMDCPropertyMap(Map.of(
                "requestId", "req-123",
                "userId", "alice",
                "tenant", "acme"
        ));

        jsonWriter.writeJsonEvent(event, baos);
        String json = baos.toString("UTF-8");

        JsonNode root = MAPPER.readTree(json);
        JsonNode ctx = root.get("ctx");
        assertNotNull(ctx, "Should have ctx object");
        assertEquals("req-123", ctx.get("requestId").asText());
        assertEquals("alice", ctx.get("userId").asText());
        assertEquals("acme", ctx.get("tenant").asText());
    }

    @Test
    void testMdcExclusion() throws Exception {
        jsonWriter.setIncludeMDC(false);

        LoggingEvent event = createEvent(Level.INFO, "No MDC");
        event.setMDCPropertyMap(Map.of("requestId", "req-123"));

        jsonWriter.writeJsonEvent(event, baos);
        String json = baos.toString("UTF-8");

        JsonNode root = MAPPER.readTree(json);
        assertNull(root.get("ctx"), "Should NOT include ctx when includeMDC=false");
    }

    @Test
    void testKeysExclusion() throws Exception {
        jsonWriter.setIncludeKeys(false);

        LoggingEvent event = createEvent(Level.INFO, "No KV");
        event.addKeyValuePair(new KeyValuePair("secret", "should-not-appear"));

        jsonWriter.writeJsonEvent(event, baos);
        String json = baos.toString("UTF-8");

        JsonNode root = MAPPER.readTree(json);
        assertNull(root.get("kv"), "Should NOT include kv when includeKeys=false");
    }

    @Test
    void testExceptionSerialization() throws Exception {
        RuntimeException ex = new RuntimeException("Something broke");
        LoggingEvent event = createErrorEvent(ex);

        jsonWriter.writeJsonEvent(event, baos);
        String json = baos.toString("UTF-8");

        JsonNode root = MAPPER.readTree(json);
        JsonNode err = root.get("err");
        assertNotNull(err, "Should have err object");
        assertEquals("java.lang.RuntimeException", err.get("class").asText());
        assertEquals("Something broke", err.get("msg").asText());
        assertTrue(err.has("stack"), "Should have stack array");
        assertTrue(err.get("stack").isArray(), "stack should be an array");
        assertTrue(err.get("stack").size() > 0, "stack should have frames");
    }

    @Test
    void testExceptionWithCause() throws Exception {
        IllegalArgumentException cause = new IllegalArgumentException("Invalid input");
        RuntimeException ex = new RuntimeException("Outer failure", cause);
        LoggingEvent event = createErrorEvent(ex);

        jsonWriter.writeJsonEvent(event, baos);
        String json = baos.toString("UTF-8");

        JsonNode root = MAPPER.readTree(json);
        JsonNode err = root.get("err");
        assertNotNull(err, "Should have err object");

        JsonNode causeNode = err.get("cause");
        assertNotNull(causeNode, "Should have cause object");
        assertEquals("java.lang.IllegalArgumentException", causeNode.get("class").asText());
        assertEquals("Invalid input", causeNode.get("msg").asText());
    }

    @Test
    void testCustomFields() throws Exception {
        jsonWriter.setCustomFields("{\"env\":\"test\",\"version\":\"1.0\",\"region\":\"eu-west\"}");

        LoggingEvent event = createEvent(Level.INFO, "Custom fields test");

        jsonWriter.writeJsonEvent(event, baos);
        String json = baos.toString("UTF-8");

        JsonNode root = MAPPER.readTree(json);
        assertEquals("test", root.get("env").asText());
        assertEquals("1.0", root.get("version").asText());
        assertEquals("eu-west", root.get("region").asText());
    }

    @Test
    void testSpecialCharactersEscaping() throws Exception {
        LoggingEvent event = createEvent(Level.INFO,
                "Message with \"quotes\" and \t tabs and \n newlines and \\ backslashes");

        jsonWriter.writeJsonEvent(event, baos);
        String json = baos.toString("UTF-8");

        // The JSON should be parseable
        JsonNode root = MAPPER.readTree(json);
        String msg = root.get("msg").asText();
        assertTrue(msg.contains("quotes"), "Should contain quotes text");
        assertTrue(msg.contains("tabs"), "Should contain tabs text");
        assertTrue(msg.contains("backslashes"), "Should contain backslashes text");
    }

    @Test
    void testTimestampIsNumeric() throws Exception {
        LoggingEvent event = createEvent(Level.INFO, "Timestamp test");

        jsonWriter.writeJsonEvent(event, baos);
        String json = baos.toString("UTF-8");

        JsonNode root = MAPPER.readTree(json);
        assertTrue(root.get("ts").isNumber(), "ts should be a numeric epoch millis timestamp");
        assertTrue(root.get("ts").asLong() > 0, "ts should be a positive epoch millis value");
    }

    @Test
    void testWithoutException() throws Exception {
        LoggingEvent event = createEvent(Level.INFO, "No exception");

        jsonWriter.writeJsonEvent(event, baos);
        String json = baos.toString("UTF-8");

        JsonNode root = MAPPER.readTree(json);
        assertNull(root.get("err"), "Should NOT have err when no exception");
    }

    @Test
    void testThreadNameIncluded() throws Exception {
        String threadName = Thread.currentThread().getName();
        LoggingEvent event = createEvent(Level.INFO, "Thread test");

        jsonWriter.writeJsonEvent(event, baos);
        String json = baos.toString("UTF-8");

        JsonNode root = MAPPER.readTree(json);
        assertTrue(root.has("thread"), "Should have thread field");
        assertEquals(threadName, root.get("thread").asText());
    }

    @Test
    void testIncludeSource() throws Exception {
        jsonWriter.setIncludeSource(true);

        LoggingEvent event = createEvent(Level.INFO, "Source test");
        // Simulate caller data
        event.setCallerData(new StackTraceElement[]{
                new StackTraceElement("com.example.MyService", "doSomething", "MyService.java", 42)
        });

        jsonWriter.writeJsonEvent(event, baos);
        String json = baos.toString("UTF-8");

        JsonNode root = MAPPER.readTree(json);
        JsonNode source = root.get("source");
        assertNotNull(source, "Should have source object when includeSource=true");
        assertEquals("com.example.MyService", source.get("class").asText());
        assertEquals("doSomething", source.get("method").asText());
        assertEquals(42, source.get("line").asInt());
    }

    @Test
    void testValidJsonLineOutput() throws Exception {
        // Write multiple events, each should be a valid JSON line
        for (int i = 0; i < 3; i++) {
            LoggingEvent event = createEvent(Level.INFO, "Line " + i);
            event.addKeyValuePair(new KeyValuePair("index", i));
            jsonWriter.writeJsonEvent(event, baos);
            baos.write(System.lineSeparator().getBytes("UTF-8"));
        }

        String output = baos.toString("UTF-8");
        String[] lines = output.split(System.lineSeparator());

        assertEquals(3, lines.length, "Should have 3 JSON lines");

        for (int i = 0; i < 3; i++) {
            JsonNode root = MAPPER.readTree(lines[i]);
            assertEquals("INFO", root.get("level").asText());
            assertEquals(i, root.get("kv").get("index").asInt());
        }
    }

    // ---- Helper methods ----

    private LoggingEvent createEvent(Level level, String message) {
        LoggingEvent event = new LoggingEvent();
        event.setLevel(level);
        event.setLoggerName("test.Logger");
        event.setMessage(message);
        event.setTimeStamp(System.currentTimeMillis());
        event.setThreadName(Thread.currentThread().getName());
        return event;
    }

    private LoggingEvent createErrorEvent(Throwable throwable) {
        LoggingEvent event = createEvent(Level.ERROR, throwable.getMessage());
        event.setThrowableProxy(new ThrowableProxy(throwable));
        return event;
    }
}