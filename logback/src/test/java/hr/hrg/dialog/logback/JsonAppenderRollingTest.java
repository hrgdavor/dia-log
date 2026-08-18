package hr.hrg.dialog.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link JsonAppenderRolling}: the writeOut JSON path (same schema as
 * {@link JsonAppender}) and the stackTraceFilter wiring.
 */
class JsonAppenderRollingTest {

    @Test
    void writeOut_writesJsonEventWithNewline() throws Exception {
        // SLF4J-bound context: LoggingEvent needs a valid MDC adapter.
        LoggerContext context = (LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
        Logger logger = context.getLogger("test.rolling");
        logger.setLevel(Level.INFO);

        LoggingEvent event = new LoggingEvent("test.rolling", logger, Level.INFO, "rolling hello", null, null);
        event.setTimeStamp(123456789L);

        JsonAppenderRolling appender = new JsonAppenderRolling();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        appender.setOutputStream(out);

        appender.writeOut(event);

        String json = out.toString(StandardCharsets.UTF_8);
        assertTrue(json.contains("\"msg\":\"rolling hello\""), "msg missing: " + json);
        assertTrue(json.contains("\"level\":\"INFO\""), "level missing: " + json);
        assertTrue(json.endsWith("\n"), "must end with newline: " + json);
    }

    @Test
    void writeOut_exceptionEvent_includesErrHash() throws Exception {
        LoggerContext context = (LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
        Logger logger = context.getLogger("test.rolling");
        logger.setLevel(Level.ERROR);

        LoggingEvent event = new LoggingEvent("test.rolling", logger, Level.ERROR, "boom",
                new RuntimeException("boom"), null);
        event.setTimeStamp(123456789L);

        JsonAppenderRolling appender = new JsonAppenderRolling();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        appender.setOutputStream(out);

        appender.writeOut(event);

        String json = out.toString(StandardCharsets.UTF_8);
        assertTrue(json.contains("\"errClass\":\"java.lang.RuntimeException\""), "errClass missing: " + json);
        assertTrue(json.contains("\"errHash\":"), "errHash missing: " + json);
    }

    @Test
    void stackTraceFilter_validPredicate_isAccepted() {
        JsonAppenderRolling appender = new JsonAppenderRolling();
        appender.setStackTraceFilter(JsonAppenderTest.AcceptAllFilter.class.getName());
        // no exception; filter wired into the internal writer
    }

    @Test
    void stackTraceFilter_invalidClass_throws() {
        JsonAppenderRolling appender = new JsonAppenderRolling();
        assertThrows(IllegalArgumentException.class,
                () -> appender.setStackTraceFilter("no.such.Class"));
    }
}
