package hr.hrg.dialog.logback;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ConsoleAppenderJson}.
 * <p>
 * Covers: JSON structure for all log levels, key-value inclusion/exclusion,
 * MDC inclusion/exclusion, exception serialization, special character escaping.
 * </p>
 */
class ConsoleAppenderJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String LOGGER_NAME = "test.logger";

    private LoggerContext context;
    private Logger logger;
    private ConsoleAppenderJson<ILoggingEvent> appender;
    private ByteArrayOutputStream outputStream;
    private PrintStream originalOut;

    @BeforeEach
    void setUp() {
        originalOut = System.out;
        outputStream = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outputStream));

        context = new LoggerContext();
        logger = context.getLogger(LOGGER_NAME);
        logger.setLevel(Level.TRACE);

        appender = new ConsoleAppenderJson<>();
        appender.setContext(context);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        appender.stop();
        context.stop();
        System.setOut(originalOut);
    }

    // ========== JSON structure for all log levels ==========

    @Test
    void json_containsRequiredFields() throws Exception {
        logger.info("test message");
        JsonNode json = parseLastJsonLine();

        assertTrue(json.has("ts"), "Should have 'ts' (epoch millis)");
        assertTrue(json.has("level"), "Should have 'level'");
        assertTrue(json.has("logger"), "Should have 'logger'");
        assertTrue(json.has("thread"), "Should have 'thread'");
        assertTrue(json.has("msg"), "Should have 'msg'");
    }

    @Test
    void json_levelIsCorrect() throws Exception {
        logger.trace("trace msg");
        assertEquals("TRACE", parseLastJsonLine().get("level").asText());

        logger.debug("debug msg");
        assertEquals("DEBUG", parseLastJsonLine().get("level").asText());

        logger.info("info msg");
        assertEquals("INFO", parseLastJsonLine().get("level").asText());

        logger.warn("warn msg");
        assertEquals("WARN", parseLastJsonLine().get("level").asText());

        logger.error("error msg");
        assertEquals("ERROR", parseLastJsonLine().get("level").asText());
    }

    @Test
    void json_loggerAndThreadAreCorrect() throws Exception {
        logger.info("message");
        JsonNode json = parseLastJsonLine();

        assertEquals(LOGGER_NAME, json.get("logger").asText());
        assertNotNull(json.get("thread").asText());
    }

    @Test
    void json_msgContainsFormattedMessage() throws Exception {
        logger.info("Hello {}!", "world");
        JsonNode json = parseLastJsonLine();
        assertEquals("Hello world!", json.get("msg").asText());
    }

    // ========== MDC inclusion/exclusion ==========

    @Test
    void mdcIncluded_whenIncludeMdcTrue() throws Exception {
        appender.setIncludeMDC(true);
        MDC.put("mdcKey", "mdcValue");

        logger.info("test");
        JsonNode json = parseLastJsonLine();

        assertEquals("mdcValue", json.get("mdcKey").asText());
    }

    @Test
    void mdcExcluded_whenIncludeMdcFalse() throws Exception {
        appender.setIncludeMDC(false);
        MDC.put("mdcKey", "mdcValue");

        logger.info("test");
        String output = outputStream.toString();
        assertFalse(output.isEmpty());
        assertFalse(output.contains("mdcKey"));
    }

    @Test
    void mdcCleared_doesNotLeakBetweenEvents() throws Exception {
        MDC.put("sessionId", "session1");
        logger.info("first event");
        MDC.clear();

        MDC.put("sessionId", "session2");
        logger.info("second event");

        String output = outputStream.toString();
        String[] lines = output.trim().split("\\R");
        assertEquals(2, lines.length);

        JsonNode first = MAPPER.readTree(lines[0]);
        JsonNode second = MAPPER.readTree(lines[1]);

        assertEquals("session1", first.get("sessionId").asText());
        assertEquals("session2", second.get("sessionId").asText());
    }

    // ========== customFields ==========

    @Test
    void customFields_areMergedIntoOutput() throws Exception {
        appender.setCustomFields("{\"env\":\"test\",\"version\":\"2.0\"}");

        logger.info("hello");
        JsonNode json = parseLastJsonLine();

        assertEquals("test", json.get("env").asText());
        assertEquals("2.0", json.get("version").asText());
    }

    @Test
    void customFields_null_doesNotAddFields() throws Exception {
        appender.setCustomFields(null);

        logger.info("hello");
        JsonNode json = parseLastJsonLine();

        assertFalse(json.has("env"));
    }

    // ========== includeSource ==========

    @Test
    void includeSource_true_addsSourceObject() throws Exception {
        appender.setIncludeSource(true);

        logger.info("test source");
        JsonNode json = parseLastJsonLine();

        assertTrue(json.has("source"));
        assertTrue(json.get("source").has("class"));
        assertTrue(json.get("source").has("method"));
    }

    @Test
    void includeSource_false_omitsSource() throws Exception {
        appender.setIncludeSource(false);

        logger.info("test");
        JsonNode json = parseLastJsonLine();

        assertFalse(json.has("source"));
    }

    // ========== prettyPrint ==========

    @Test
    void prettyPrint_true_producesMultiLine() throws Exception {
        appender.setPrettyPrint(true);

        logger.info("test");
        String output = outputStream.toString();

        assertTrue(output.contains("\n") || output.contains("\r"));
    }

    @Test
    void prettyPrint_false_singleLine() throws Exception {
        appender.setPrettyPrint(false);

        logger.info("test");
        String output = outputStream.toString().trim();

        assertTrue(output.startsWith("{"));
        assertTrue(output.endsWith("}"));
    }

    // ========== Exception serialization ==========

    @Test
    void exception_serializes_errObject() throws Exception {
        logger.error("error occurred", new RuntimeException("test exception"));
        JsonNode json = parseLastJsonLine();

        assertTrue(json.has("err"), "Should have 'err' object");
        JsonNode err = json.get("err");

        assertTrue(err.has("class"), "Should have exception class");
        assertTrue(err.has("msg"), "Should have exception message");
        assertTrue(err.has("hash"), "Should have stack hash");
        assertEquals("java.lang.RuntimeException", err.get("class").asText());
    }

    @Test
    void exception_hash_isHexString() throws Exception {
        logger.error("error", new RuntimeException("hash test"));
        JsonNode json = parseLastJsonLine();
        JsonNode err = json.get("err");

        String hash = err.get("hash").asText();
        assertNotNull(hash);
        assertTrue(hash.matches("[0-9a-fA-F]{1,16}"), "Hash should be a hex string up to 16 chars");
    }

    @Test
    void exception_withCause_includesCauseObject() throws Exception {
        RuntimeException cause = new RuntimeException("root cause");
        RuntimeException ex = new RuntimeException("wrapper", cause);

        logger.error("error with cause", ex);
        JsonNode json = parseLastJsonLine();
        JsonNode err = json.get("err");

        assertTrue(err.has("cause"), "Should have 'cause' object");
        assertEquals("java.lang.RuntimeException", err.get("cause").get("class").asText());
        assertEquals("root cause", err.get("cause").get("msg").asText());
    }

    @Test
    void noException_omitsErrObject() throws Exception {
        logger.info("no error");
        JsonNode json = parseLastJsonLine();

        assertFalse(json.has("err"));
    }

    // ========== Special character escaping ==========

    @Test
    void specialCharacters_areEscaped() throws Exception {
        logger.info("line1\nline2\ttab\"quote\\backslash");
        JsonNode json = parseLastJsonLine();

        String msg = json.get("msg").asText();
        assertEquals("line1\nline2\ttab\"quote\\backslash", msg);
    }

    @Test
    void unicodeCharacters_arePreserved() throws Exception {
        String unicode = "日本語 émojí 🎉";
        logger.info(unicode);
        JsonNode json = parseLastJsonLine();

        assertEquals(unicode, json.get("msg").asText());
    }

    // ========== maxStackFrames delegation ==========

    @Test
    void maxStackFrames_getterSetter_delegatesToJsonWriter() {
        appender.setMaxStackFrames(50);
        assertEquals(50, appender.getMaxStackFrames());

        appender.setMaxStackFrames(10);
        assertEquals(10, appender.getMaxStackFrames());
    }

    // ========== Lifecycle ==========

    @Test
    void appender_startAndStop_works() {
        assertTrue(appender.isStarted());

        appender.stop();
        assertFalse(appender.isStarted());

        appender.start();
        assertTrue(appender.isStarted());
    }

    // ========== Helpers ==========

    private JsonNode parseLastJsonLine() throws IOException {
        String output = outputStream.toString().trim();
        assertFalse(output.isEmpty(), "Output should not be empty");

        String[] lines = output.split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.startsWith("{")) {
                return MAPPER.readTree(line);
            }
        }

        // Fallback: try parsing the entire output as a single JSON
        return MAPPER.readTree(output);
    }
}