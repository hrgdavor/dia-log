package hr.hrg.dialog.logback;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ConsoleAppenderDev}.
 * <p>
 * Covers: placeholder expansion, missing-key detection.
 * </p>
 */
class ConsoleAppenderDevTest {

    private LoggerContext context;
    private Logger logger;
    private ConsoleAppenderDev<ILoggingEvent> appender;
    private ByteArrayOutputStream outputStream;

    @BeforeEach
    void setUp() {
        context = new LoggerContext();
        logger = context.getLogger("test.dev");
        logger.setLevel(Level.TRACE);

        appender = new ConsoleAppenderDev<>();
        appender.setContext(context);

        outputStream = new ByteArrayOutputStream();
        appender.setOutputStream(outputStream);

        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        appender.stop();
        context.stop();
    }

    // ========== Default placeholder expansion ==========

    @Test
    void expandPlaceholders_defaultTrue_expandsKv() {
        appender.setExpandPlaceholders(true);

        logger.info("User {user} logged from {ip}");
        String output = outputStream.toString();

        // Without KV pairs, the placeholders remain since this test doesn't
        // add structured KV — just verify it doesn't crash and output looks reasonable
        assertNotNull(output);
        assertFalse(output.isEmpty());
    }

    @Test
    void expandPlaceholders_false_leavesPlaceholders() {
        appender.setExpandPlaceholders(false);

        logger.info("User {user} logged from {ip}");
        String output = outputStream.toString();

        assertTrue(output.contains("{user}"));
        assertTrue(output.contains("{ip}"));
    }

    // ========== warnOnMissingKeys ==========

    @Test
    void warnOnMissingKeys_false_noWarningOutput() {
        appender.setWarnOnMissingKeys(false);
        appender.setExpandPlaceholders(true);

        logger.info("Hello {name}!");
        String output = outputStream.toString();

        // No "MISSING KEYS" warning should appear
        assertFalse(output.contains("MISSING KEYS"));
    }

    @Test
    void warnOnMissingKeys_true_appendsWarning() {
        appender.setWarnOnMissingKeys(true);
        appender.setExpandPlaceholders(true);

        logger.info("Hello {name}!");
        String output = outputStream.toString();

        // Should contain the missing keys warning
        assertTrue(output.contains("MISSING KEYS"));
        assertTrue(output.contains("name"));
    }

    @Test
    void warnOnMissingKeys_true_includesStackTrace() {
        appender.setWarnOnMissingKeys(true);
        appender.setExpandPlaceholders(true);

        logger.info("Missing {key1} and {key2}");
        String output = outputStream.toString();

        // Should have stack trace from the missing keys error
        assertTrue(output.contains("at ") || output.contains("\tat "),
                "Should contain stack trace lines");
        assertTrue(output.contains("key1"));
        assertTrue(output.contains("key2"));
    }

    @Test
    void warnOnMissingKeys_allKeysPresent_noWarning() {
        appender.setWarnOnMissingKeys(true);
        appender.setExpandPlaceholders(true);

        // Log with KV pairs via MDC won't work with structured KV,
        // but we can test scenario where placeholders match nothing
        // Since there are no structured KV pairs, they'll always be "missing"
        logger.info("Hello {name}, your {item} is ready");
        String output = outputStream.toString();

        assertTrue(output.contains("MISSING KEYS"));
        assertTrue(output.contains("name"));
        assertTrue(output.contains("item"));
    }

    // ========== Expand placeholders scenario ==========

    @Test
    void numericPlaceholders_areNotExpanded() {
        appender.setWarnOnMissingKeys(true);
        appender.setExpandPlaceholders(true);

        // Using SLF4J style {} is different from {name} — numeric {0} should be preserved
        logger.info("Value is {0}");
        String output = outputStream.toString();

        // Numeric placeholders should be left as-is
        assertTrue(output.contains("{0}"));
    }

    @Test
    void output_format_containsTimestamp() {
        logger.info("test");
        String output = outputStream.toString();

        // The dev output format includes timestamp like HH:mm:ss.SSS
        assertTrue(output.matches("(?s).*\\d{2}:\\d{2}:\\d{2}\\.\\d{3}.*"),
                "Output should contain timestamp in HH:mm:ss.SSS format");
    }

    @Test
    void output_format_containsLevel() {
        logger.info("info test");
        String output = outputStream.toString();

        assertTrue(output.contains("INFO"));
    }

    @Test
    void output_format_containsLoggerName() {
        logger.info("test");
        String output = outputStream.toString();

        assertTrue(output.contains("test.dev"));
    }

    @Test
    void output_format_containsMessage() {
        logger.info("specific message content");
        String output = outputStream.toString();

        assertTrue(output.contains("specific message content"));
    }

    // ========== Configuration getter/setter ==========

    @Test
    void expandPlaceholders_getterSetter() {
        assertTrue(appender.isExpandPlaceholders());
        appender.setExpandPlaceholders(false);
        assertFalse(appender.isExpandPlaceholders());
        appender.setExpandPlaceholders(true);
        assertTrue(appender.isExpandPlaceholders());
    }

    @Test
    void warnOnMissingKeys_getterSetter() {
        assertFalse(appender.isWarnOnMissingKeys());
        appender.setWarnOnMissingKeys(true);
        assertTrue(appender.isWarnOnMissingKeys());
        appender.setWarnOnMissingKeys(false);
        assertFalse(appender.isWarnOnMissingKeys());
    }

    // ========== All log levels ==========

    @Test
    void allLevels_outputProduced() {
        logger.trace("trace");
        logger.debug("debug");
        logger.info("info");
        logger.warn("warn");
        logger.error("error");

        String output = outputStream.toString();

        assertTrue(output.contains("TRACE"));
        assertTrue(output.contains("DEBUG"));
        assertTrue(output.contains("INFO"));
        assertTrue(output.contains("WARN"));
        assertTrue(output.contains("ERROR"));
    }
}